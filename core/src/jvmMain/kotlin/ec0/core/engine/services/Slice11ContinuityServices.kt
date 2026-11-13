/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Slice 11 Production-Grade Continuity Services (`jvmMain`):
 *   1. UsbAdbTunnelCoordinator — Tier-0 USB-C `adb forward` zero-Wi-Fi deterministic
 *      transport tunnel for both `ec0-engine` (`:49880`) and `CamDroid` (`:4747`).
 *   2. ContinuityVirtualMicBridge — PipeWire / PulseAudio virtual microphone
 *      (`pactl load-module module-pipe-source`) + `CamDroidV2Packet` A/V timestamp
 *      drift calculator.
 *   3. HandoffAndProximityService — Seamless browser/app URL Handoff (`xdg-open`),
 *      remote workstation lock (`loginctl lock-session`), and "Find My Phone" DND-override
 *      siren (`find_my_ring`).
 *   4. LocalDaemonIpcServer & LocalDaemonIpcClient — Authenticated loopback IPC so
 *      KDE Dolphin / GNOME Nautilus context-menu invocations (`--send-files`) hand off
 *      file paths to the running desktop daemon in <5 ms.
 *   5. TransferThroughputTracker — Exponential moving-average (EMA) transfer speed
 *      (`MB/s`) and remaining-time (`ETA mm:ss`) calculator.
 */
package ec0.core.engine.services

import ec0.core.engine.CanonicalJson
import ec0.core.engine.Ec0Envelope
import ec0.core.engine.Ec0EnvelopeCodec
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.protocol.CamDroidConstants
import ec0.core.engine.protocol.FindMyRingCommand
import ec0.core.engine.protocol.HandoffActivityInfo
import ec0.core.engine.protocol.HandoffOpenCommand
import ec0.core.engine.protocol.HandoffProtocol
import ec0.core.engine.protocol.ProximitySecurityProtocol
import ec0.core.engine.protocol.WorkstationLockCommand
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UsbConnectedAndroidDevice(
    val serial: String,
    val model: String,
)

data class UsbTunnelStatus(
    val serial: String,
    val model: String,
    val engineLocalPort: Int,
    val camDroidLocalPort: Int,
    val establishedAtMs: Long,
)

/**
 * Probes `adb devices -l` for physical USB-C Android devices and establishes
 * deterministic `adb -s <serial> forward tcp:<local> tcp:<remote>` tunnels for
 * `ec0-engine` (`49880`) and `CamDroid` (`4747`).
 */
class UsbAdbTunnelCoordinator(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val adbBinary: String = "adb",
) {
    private val _activeTunnel = MutableStateFlow<UsbTunnelStatus?>(null)
    val activeTunnel: StateFlow<UsbTunnelStatus?> = _activeTunnel.asStateFlow()

    /**
     * Parses `adb devices -l` stdout into physical USB-C connected devices, ignoring
     * TCP/IP wireless ADB endpoints (`<ip>:<port>`).
     */
    fun parseUsbDevicesOutput(stdout: String): List<UsbConnectedAndroidDevice> {
        val result = mutableListOf<UsbConnectedAndroidDevice>()
        for (rawLine in stdout.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("List of devices") || line.startsWith("*")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val serial = parts[0]
            val state = parts[1]
            if (state != "device") continue
            // Exclude wireless ADB (host:port) so only physical USB-C cables are treated as Tier-0
            if (serial.contains(":")) continue
            if (!SAFE_SERIAL_REGEX.matches(serial)) continue
            val modelToken = parts.firstOrNull { it.startsWith("model:") }
                ?.substringAfter("model:")
                ?.replace('_', ' ')
                ?: "Android USB Device"
            result.add(UsbConnectedAndroidDevice(serial = serial, model = modelToken))
        }
        return result
    }

    fun buildForwardCommand(serial: String, localPort: Int, remotePort: Int): List<String> {
        require(SAFE_SERIAL_REGEX.matches(serial)) { "Invalid ADB serial: $serial" }
        require(localPort in 1..65535 && remotePort in 1..65535) { "Invalid port range" }
        return listOf(adbBinary, "-s", serial, "forward", "tcp:$localPort", "tcp:$remotePort")
    }

    fun probeAndEstablishUsbTunnel(
        enginePort: Int = EngineConstants.PRIMARY_PORT,
        camDroidPort: Int = CamDroidConstants.DEFAULT_TCP_PORT,
        nowMs: Long = System.currentTimeMillis(),
    ): UsbTunnelStatus? {
        val listRes = runCatching {
            launcher.runBlockingCommand(listOf(adbBinary, "devices", "-l"), timeoutMs = 2_500L)
        }.getOrNull() ?: return null
        if (listRes.exitCode != 0) return null

        val usbDevice = parseUsbDevicesOutput(listRes.stdout).firstOrNull()
        if (usbDevice == null) {
            _activeTunnel.value = null
            return null
        }

        val engineOk = runCatching {
            launcher.runBlockingCommand(
                buildForwardCommand(usbDevice.serial, enginePort, EngineConstants.PRIMARY_PORT),
                timeoutMs = 2_500L,
            ).exitCode == 0
        }.getOrDefault(false)

        if (!engineOk) {
            _activeTunnel.value = null
            return null
        }

        runCatching {
            launcher.runBlockingCommand(
                buildForwardCommand(usbDevice.serial, camDroidPort, CamDroidConstants.DEFAULT_TCP_PORT),
                timeoutMs = 2_500L,
            )
        }

        val status = UsbTunnelStatus(
            serial = usbDevice.serial,
            model = usbDevice.model,
            engineLocalPort = enginePort,
            camDroidLocalPort = camDroidPort,
            establishedAtMs = nowMs,
        )
        _activeTunnel.value = status
        return status
    }

    fun tearDownUsbTunnel(): Boolean {
        val current = _activeTunnel.value ?: return false
        _activeTunnel.value = null
        return runCatching {
            launcher.runBlockingCommand(
                listOf(adbBinary, "-s", current.serial, "forward", "--remove-all"),
                timeoutMs = 2_000L,
            ).exitCode == 0
        }.getOrDefault(false)
    }

    companion object {
        private val SAFE_SERIAL_REGEX = Regex("^[A-Za-z0-9._-]+$")
    }
}

/**
 * Manages a Linux PipeWire / PulseAudio virtual microphone source (`CamDroidMic`)
 * backed by a named PCM pipe (`module-pipe-source`) and computes A/V timestamp
 * drift from `CamDroidV2Packet` capture timestamps.
 */
class ContinuityVirtualMicBridge(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val pactlBinary: String = "pactl",
    val pipeFilePath: String = "/tmp/ec0-camdroid-mic.pcm",
) {
    private val _virtualMicActive = MutableStateFlow(false)
    val virtualMicActive: StateFlow<Boolean> = _virtualMicActive.asStateFlow()

    private val _loadedModuleId = MutableStateFlow<Int?>(null)
    val loadedModuleId: StateFlow<Int?> = _loadedModuleId.asStateFlow()

    private val _avDriftMs = MutableStateFlow(0L)
    val avDriftMs: StateFlow<Long> = _avDriftMs.asStateFlow()

    fun buildLoadPipeSourceCommand(
        sourceName: String = "CamDroidMic",
        sampleRateHz: Int = 48_000,
        channels: Int = 1,
    ): List<String> = listOf(
        pactlBinary,
        "load-module",
        "module-pipe-source",
        "source_name=$sourceName",
        "file=$pipeFilePath",
        "format=s16le",
        "rate=$sampleRateHz",
        "channels=$channels",
        "source_properties=device.description=CamDroid_Continuity_Mic",
    )

    fun buildUnloadModuleCommand(moduleId: Int): List<String> =
        listOf(pactlBinary, "unload-module", moduleId.toString())

    fun enableVirtualMic(): Boolean {
        if (_virtualMicActive.value) return true
        val res = runCatching {
            launcher.runBlockingCommand(buildLoadPipeSourceCommand(), timeoutMs = 3_000L)
        }.getOrNull() ?: return false
        if (res.exitCode != 0) return false
        val modId = res.stdout.trim().toIntOrNull()
        _loadedModuleId.value = modId
        _virtualMicActive.value = true
        return true
    }

    fun disableVirtualMic(): Boolean {
        val modId = _loadedModuleId.value
        _virtualMicActive.value = false
        _loadedModuleId.value = null
        if (modId == null) return true
        return runCatching {
            launcher.runBlockingCommand(buildUnloadModuleCommand(modId), timeoutMs = 3_000L).exitCode == 0
        }.getOrDefault(false)
    }

    /**
     * Computes A/V drift in milliseconds (`(videoCaptureNs - audioCaptureNs) / 1_000_000`)
     * from `CamDroidV2Packet` hardware timestamps.
     */
    fun recordCaptureTimestamps(videoCaptureNs: Long, audioCaptureNs: Long): Long {
        val drift = (videoCaptureNs - audioCaptureNs) / 1_000_000L
        _avDriftMs.value = drift
        return drift
    }
}

/**
 * Coordinates Seamless App/Browser URL Handoff (`xdg-open`), remote workstation lock
 * (`loginctl lock-session`), and "Find My Phone" DND-override siren (`find_my_ring`).
 */
class HandoffAndProximityService(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val onHandoffOpenRequested: (HandoffOpenCommand) -> Unit = {},
    private val onWorkstationLockRequested: (WorkstationLockCommand) -> Unit = {},
    private val onFindMyRingRequested: (FindMyRingCommand) -> Unit = {},
) {
    private val _remoteHandoffActivity = MutableStateFlow<HandoffActivityInfo?>(null)
    val remoteHandoffActivity: StateFlow<HandoffActivityInfo?> = _remoteHandoffActivity.asStateFlow()

    private val _findMyRinging = MutableStateFlow(false)
    val findMyRinging: StateFlow<Boolean> = _findMyRinging.asStateFlow()

    fun buildXdgOpenCommand(rawUrl: String): List<String> {
        val safeUrl = HandoffProtocol.validateSafeHandoffUrl(rawUrl)
        return listOf("xdg-open", safeUrl)
    }

    fun buildLoginctlLockCommand(): List<String> =
        listOf("loginctl", "lock-session")

    fun publishHandoffActivity(
        activity: HandoffActivityInfo,
        session: Ec0Session?,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? {
        return session?.sendInfo(
            infoType = HandoffProtocol.INFO_HANDOFF_ACTIVITY,
            infoBody = activity.toJsonObject(),
            category = HandoffProtocol.CATEGORY_HANDOFF,
            timestampMs = nowMs,
        )
    }

    fun sendHandoffOpen(
        url: String,
        title: String = "",
        scrollPermille: Int = 0,
        session: Ec0Session?,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? {
        val cmd = HandoffOpenCommand(url = url, title = title, scrollPermille = scrollPermille)
        return session?.sendCmd(
            cmdType = HandoffProtocol.CMD_HANDOFF_OPEN,
            cmdBody = cmd.toJsonObject(),
            category = HandoffProtocol.CATEGORY_HANDOFF,
            timestampMs = nowMs,
        )
    }

    fun openActiveRemoteHandoffLocally(): Boolean {
        val activity = _remoteHandoffActivity.value ?: return false
        val argv = buildXdgOpenCommand(activity.url)
        return runCatching {
            launcher.runBlockingCommand(argv, timeoutMs = 3_000L).exitCode == 0
        }.getOrDefault(false)
    }

    fun triggerFindMyPhoneRing(
        start: Boolean = true,
        durationSeconds: Int = 15,
        session: Ec0Session?,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? {
        val cmd = FindMyRingCommand(
            action = if (start) FindMyRingCommand.ACTION_START_RING else FindMyRingCommand.ACTION_STOP_RING,
            durationSeconds = durationSeconds,
            overrideDnd = true,
        )
        _findMyRinging.value = start
        return session?.sendCmd(
            cmdType = ProximitySecurityProtocol.CMD_FIND_MY_RING,
            cmdBody = cmd.toJsonObject(),
            category = ProximitySecurityProtocol.CATEGORY_SECURITY,
            timestampMs = nowMs,
        )
    }

    fun lockLocalWorkstation(): Boolean {
        return runCatching {
            launcher.runBlockingCommand(buildLoginctlLockCommand(), timeoutMs = 3_000L).exitCode == 0
        }.getOrDefault(false)
    }

    fun sendRemoteWorkstationLock(
        session: Ec0Session?,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? {
        val cmd = WorkstationLockCommand(action = WorkstationLockCommand.ACTION_LOCK)
        return session?.sendCmd(
            cmdType = ProximitySecurityProtocol.CMD_WORKSTATION_LOCK,
            cmdBody = cmd.toJsonObject(),
            category = ProximitySecurityProtocol.CATEGORY_SECURITY,
            timestampMs = nowMs,
        )
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            HandoffProtocol.INFO_HANDOFF_ACTIVITY -> {
                _remoteHandoffActivity.value = HandoffActivityInfo.fromJsonObject(body)
                true
            }
            HandoffProtocol.CMD_HANDOFF_OPEN -> {
                val cmd = HandoffOpenCommand.fromJsonObject(body)
                onHandoffOpenRequested(cmd)
                runCatching {
                    launcher.runBlockingCommand(buildXdgOpenCommand(cmd.url), timeoutMs = 3_000L)
                }
                true
            }
            ProximitySecurityProtocol.CMD_WORKSTATION_LOCK -> {
                val cmd = WorkstationLockCommand.fromJsonObject(body)
                onWorkstationLockRequested(cmd)
                if (cmd.action == WorkstationLockCommand.ACTION_LOCK) {
                    lockLocalWorkstation()
                }
                true
            }
            ProximitySecurityProtocol.CMD_FIND_MY_RING -> {
                val cmd = FindMyRingCommand.fromJsonObject(body)
                _findMyRinging.value = (cmd.action == FindMyRingCommand.ACTION_START_RING)
                onFindMyRingRequested(cmd)
                true
            }
            else -> false
        }
    }
}

/**
 * Authenticated loopback CLI IPC server (`127.0.0.1`) so Dolphin / Nautilus
 * right-click "AirDrop" scripts (`ec0-desktop --send-files ...`) can hand file paths
 * to an already-running `ec0` desktop daemon in <5 ms without launching a second JVM.
 */
class LocalDaemonIpcServer(
    private val descriptorFile: File = File(EncryptedIdentityStore.defaultConfigDir(), "daemon_ipc.json"),
    private val onSendFilesRequested: (List<File>) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ec0-daemon-ipc").apply { isDaemon = true }
    }

    var boundPort: Int = 0
        private set

    var authTokenHex: String = ""
        private set

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return boundPort
        val tokenBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        authTokenHex = tokenBytes.toHex()

        val sock = ServerSocket()
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        serverSocket = sock
        boundPort = sock.localPort

        val descriptorJson = buildJsonObject {
            put("port", boundPort)
            put("token", authTokenHex)
        }
        EncryptedIdentityStore.ensureDirectoryPermissions(descriptorFile.parentFile ?: File("."))
        EncryptedIdentityStore.atomicWriteText(descriptorFile, CanonicalJson.encode(descriptorJson))

        executor.execute {
            while (running.get() && !sock.isClosed) {
                val client = runCatching { sock.accept() }.getOrNull() ?: break
                handleClient(client)
            }
        }
        return boundPort
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 2_000
            val line = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)).readLine()
                ?: return
            val obj = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
            val token = (obj["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
            if (!token.equals(authTokenHex, ignoreCase = true)) {
                writeResponse(sock, ok = false, message = "unauthorized")
                return
            }
            val pathsArr = obj["files"] as? JsonArray ?: JsonArray(emptyList())
            val files = pathsArr.mapNotNull { el ->
                val raw = (el as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                File(raw).takeIf { it.exists() }
            }
            if (files.isNotEmpty()) {
                onSendFilesRequested(files)
            }
            writeResponse(sock, ok = true, message = "queued:${files.size}")
        }
    }

    private fun writeResponse(sock: Socket, ok: Boolean, message: String) {
        runCatching {
            val resp = buildJsonObject {
                put("message", message)
                put("ok", ok)
            }
            val writer = OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)
            writer.write(CanonicalJson.encode(resp) + "\n")
            writer.flush()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        runCatching { descriptorFile.delete() }
        executor.shutdownNow()
    }
}

object LocalDaemonIpcClient {
    /**
     * Attempts to hand off [filePaths] to an already-running local `ec0` daemon
     * via [descriptorFile]. Returns `true` if the running daemon accepted the batch.
     */
    fun trySendFilesToRunningDaemon(
        filePaths: List<String>,
        descriptorFile: File = File(EncryptedIdentityStore.defaultConfigDir(), "daemon_ipc.json"),
        timeoutMs: Int = 1_500,
    ): Boolean {
        if (!descriptorFile.isFile) return false
        return try {
            val obj = Json.parseToJsonElement(descriptorFile.readText()) as? JsonObject ?: return false
            val port = (obj["port"] as? JsonPrimitive)?.intOrNull ?: return false
            val token = (obj["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false

            Socket().use { sock ->
                sock.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                sock.soTimeout = timeoutMs
                val req = buildJsonObject {
                    put("cmd", "send_files")
                    put(
                        "files",
                        buildJsonArray {
                            for (p in filePaths) add(JsonPrimitive(p))
                        },
                    )
                    put("token", token)
                }
                val writer = OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)
                writer.write(CanonicalJson.encode(req) + "\n")
                writer.flush()

                val respLine = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)).readLine()
                    ?: return false
                respLine.contains("\"ok\":true")
            }
        } catch (_: Exception) {
            false
        }
    }
}

data class TransferSpeedSample(
    val bytesPerSecond: Long,
    val etaSeconds: Long,
) {
    fun formatTabular(): String {
        val mbps = bytesPerSecond.toDouble() / (1024.0 * 1024.0)
        val mbpsFormatted = ((mbps * 10.0).toLong() / 10.0).toString()
        val mins = (etaSeconds / 60L).coerceAtLeast(0L).toString().padStart(2, '0')
        val secs = (etaSeconds % 60L).coerceAtLeast(0L).toString().padStart(2, '0')
        return "$mbpsFormatted MB/s · ETA $mins:$secs"
    }
}

/**
 * Exponential Moving Average (EMA) transfer throughput & ETA tracker.
 */
class TransferThroughputTracker(
    private val alphaPermille: Int = 350, // 0.35 EMA weight
) {
    private data class State(
        val lastBytes: Long,
        val lastTimestampMs: Long,
        val emaBytesPerSec: Long,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun recordProgress(
        transferId: String,
        transferredBytes: Long,
        totalBytes: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): TransferSpeedSample {
        val prev = states[transferId]
        if (prev == null) {
            states[transferId] = State(transferredBytes, nowMs, 0L)
            return TransferSpeedSample(bytesPerSecond = 0L, etaSeconds = 0L)
        }
        val dtMs = (nowMs - prev.lastTimestampMs).coerceAtLeast(1L)
        val dBytes = (transferredBytes - prev.lastBytes).coerceAtLeast(0L)
        val instantBps = (dBytes * 1_000L) / dtMs
        val nextEma = if (prev.emaBytesPerSec == 0L) {
            instantBps
        } else {
            ((instantBps * alphaPermille) + (prev.emaBytesPerSec * (1_000 - alphaPermille))) / 1_000L
        }
        states[transferId] = State(transferredBytes, nowMs, nextEma)
        val remaining = (totalBytes - transferredBytes).coerceAtLeast(0L)
        val etaSec = if (nextEma > 0L) remaining / nextEma else 0L
        return TransferSpeedSample(bytesPerSecond = nextEma, etaSeconds = etaSec)
    }

    fun remove(transferId: String) {
        states.remove(transferId)
    }
}
