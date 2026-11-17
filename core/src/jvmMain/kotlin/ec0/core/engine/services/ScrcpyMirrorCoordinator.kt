/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Scrcpy Screen Mirroring Coordinator (`docs/engine/design.md` §6.7, Amendment 5):
 *   - Negotiates `mirror_start` / `mirror_stop` over the authenticated control plane.
 *   - Normative Amendment 5 sequence on Desktop:
 *       1. Run `adb connect <host>:<adb_port>` and verify exit code == 0 and stdout
 *          contains `"connected to"` (or `"already connected to"`).
 *       2. Spawn external `scrcpy` binary (`--serial <host>:<adb_port> --window-title <title>
 *          --max-fps <fps> --video-bit-rate <bitrateKbps>K`).
 *   - Keeps `scrcpy` as an unmodified external process per GPL-3.0 / README.md §License.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.protocol.PresenceProtocol
import ec0.core.engine.protocol.ScrcpyMirrorStartCommand
import ec0.core.engine.protocol.ScrcpyMirrorStopCommand
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CommandExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

typealias CommandExecutionResult = CommandExecResult

interface RunningProcessHandle : Closeable {
    val isAlive: Boolean
    fun stop()
    override fun close() = stop()
}

interface ExternalProcessLauncher {
    /** Runs a short-lived command (such as `adb connect <host>:<port>`) and returns its output. */
    fun runBlockingCommand(command: List<String>, timeoutMs: Long = 5_000L): CommandExecResult

    /** Spawns a long-lived background process (such as `scrcpy --serial ...`). */
    fun spawnProcess(command: List<String>): RunningProcessHandle
}

object SystemProcessLauncher : ExternalProcessLauncher {
    override fun runBlockingCommand(command: List<String>, timeoutMs: Long): CommandExecResult {
        val proc = ProcessBuilder(command).start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw IOException("Command timed out after ${timeoutMs}ms: ${command.joinToString(" ")}")
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        return CommandExecResult(proc.exitValue(), stdout, stderr)
    }

    override fun spawnProcess(command: List<String>): RunningProcessHandle {
        val proc = ProcessBuilder(command).start()
        return object : RunningProcessHandle {
            override val isAlive: Boolean
                get() = proc.isAlive

            override fun stop() {
                if (proc.isAlive) {
                    proc.destroy()
                }
            }
        }
    }
}

class ScrcpyMirrorCoordinator(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val adbBinary: String = "adb",
    private val scrcpyBinary: String = "scrcpy",
) : Closeable {

    /**
     * Raw `adb devices -l` output — the live serial registry. Wireless-debugging
     * serials are `ip:port` and CHANGE on every re-pairing; the live list is the
     * only durable way to find the phone (a hardcoded :5555 was the original bug).
     */
    fun adbDevicesList(): String {
        val result = launcher.runBlockingCommand(listOf(adbBinary, "devices", "-l"))
        if (result.exitCode != 0) {
            throw IOException("adb devices -l failed (exit=${result.exitCode}): ${result.stderr.trim()}")
        }
        return result.stdout
    }

    private val lock = ReentrantLock()
    private var activeProcess: RunningProcessHandle? = null
    private var activeSerial: String? = null

    val isMirroring: Boolean
        get() = lock.withLock { activeProcess?.isAlive == true }

    val currentSerial: String?
        get() = lock.withLock { activeSerial }

    fun requestStartMirror(
        cmd: ScrcpyMirrorStartCommand,
        sendCmd: (JsonObject) -> Unit,
    ) {
        sendCmd(cmd.toJsonObject())
    }

    fun requestStopMirror(
        reason: String = "stopped_by_user",
        sendCmd: (JsonObject) -> Unit,
    ) {
        stopLocalMirror()
        sendCmd(ScrcpyMirrorStopCommand(reason).toJsonObject())
    }

    /**
     * Builds the exact `adb connect <host>:<adb_port>` command line (Amendment 5).
     */
    fun buildAdbConnectArgs(cmd: ScrcpyMirrorStartCommand): List<String> =
        listOf(adbBinary, "connect", cmd.serial)

    /**
     * Builds the exact `scrcpy` command line after `adb connect` succeeds.
     */
    fun buildScrcpyArgs(cmd: ScrcpyMirrorStartCommand): List<String> = listOf(
        scrcpyBinary,
        "--serial",
        cmd.serial,
        "--window-title",
        cmd.windowTitle,
        "--max-fps",
        cmd.maxFps.toString(),
        "--video-bit-rate",
        "${cmd.bitrateKbps}K",
    )

    /**
     * Executes the normative Amendment 5 sequence:
     *   1. `adb connect <host>:<adb_port>`
     *   2. Verify exitCode == 0 and stdout contains `"connected to"` (case-insensitive)
     *   3. Spawn `scrcpy --serial <host>:<adb_port> ...`
     */
    fun launchMirrorSession(cmd: ScrcpyMirrorStartCommand, directSerial: String? = null): RunningProcessHandle = lock.withLock {
        stopLocalMirrorLocked()

        val serial = directSerial ?: cmd.serial
        if (directSerial == null) {
            // Legacy path: dial host:port first (USB-tcpip / explicit adb connect).
            val adbArgs = buildAdbConnectArgs(cmd)
            val adbResult = launcher.runBlockingCommand(adbArgs)
            val outLower = adbResult.stdout.lowercase()
            if (adbResult.exitCode != 0 || !outLower.contains("connected to") || outLower.contains("unable to connect") || outLower.contains("failed to connect")) {
                throw IOException(
                    "adb connect ${cmd.serial} failed (exit=${adbResult.exitCode}): ${adbResult.stdout.trim()} ${adbResult.stderr.trim()}".trim(),
                )
            }
        }

        val scrcpyArgs = buildScrcpyArgs(cmd).let { args ->
            // Swap the synthesized host:port serial for the LIVE adb serial.
            if (directSerial != null && serial != cmd.serial) {
                val idx = args.indexOf(cmd.serial)
                if (idx >= 0) args.toMutableList().also { it[idx] = serial } else args + listOf("--serial", serial)
            } else args
        }
        val handle = launcher.spawnProcess(scrcpyArgs)
        activeProcess = handle
        activeSerial = serial
        handle
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: return false
        return when (envelope.bodyType) {
            PresenceProtocol.CMD_MIRROR_START -> {
                val startCmd = ScrcpyMirrorStartCommand.fromJsonObject(body)
                launchMirrorSession(startCmd)
                true
            }
            PresenceProtocol.CMD_MIRROR_STOP -> {
                ScrcpyMirrorStopCommand.fromJsonObject(body)
                stopLocalMirror()
                true
            }
            else -> false
        }
    }

    fun stopLocalMirror() {
        lock.withLock {
            stopLocalMirrorLocked()
        }
    }

    private fun stopLocalMirrorLocked() {
        runCatching { activeProcess?.stop() }
        activeProcess = null
        activeSerial = null
    }

    override fun close() {
        stopLocalMirror()
    }
}

/**
 * Picks the adb serial for the phone from `adb devices -l` output — the LIVE serial
 * registry. Wireless-debugging serials are `ip:port` and change on every re-pairing,
 * so a hardcoded `host:5555` can never survive real usage; the running adb daemon is
 * the source of truth.
 *
 * Matching order: adb `model:` vs the engine peer alias (normalized `-`/`_`
 * case-insensitive), then a serial whose `ip:` prefix matches the peer's LAN IP.
 * Returns null when no connected device matches.
 */
fun resolveMirrorSerial(
    adbDevicesOutput: String,
    peerAlias: String,
    peerIp: String?,
): String? {
    fun normalize(s: String): String = s.replace("-", "_").lowercase()
    val wanted = normalize(peerAlias)
    val deviceLines = adbDevicesOutput.lines().filter { "\tdevice" in it || "\tdevice\r" in it }

    for (line in deviceLines) {
        val serial = line.substringBefore('\t').trim()
        val model = Regex("model:(\\S+)").find(line)?.groupValues?.get(1) ?: ""
        if (wanted.isNotEmpty() && (normalize(model) == wanted || normalize(model).contains(wanted) || wanted.contains(normalize(model)))) {
            return serial
        }
    }
    if (!peerIp.isNullOrBlank()) {
        for (line in deviceLines) {
            val serial = line.substringBefore('\t').trim()
            if (serial.startsWith("$peerIp:")) return serial
        }
    }
    return null
}
