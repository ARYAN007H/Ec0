/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #1: Full-Duplex Call Audio Routing, AirPlay-Style Phone Audio Sink,
 * and Smart MPRIS Media Auto-Ducking/Pause-Resume on Incoming Calls.
 *
 * Security invariants:
 *   - All audio FIFO pipes live exclusively inside 0700 `$XDG_RUNTIME_DIR/ec0/`
 *     (never `/tmp`).
 *   - All `pactl` / `playerctl` invocations use argv lists (zero shell parsing).
 *   - `playerId` validated against strict allowlist before targeting `playerctl`.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.protocol.AudioRouteCommand
import ec0.core.engine.protocol.AudioRouteStatusInfo
import ec0.core.engine.protocol.AudioRoutingProtocol
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pure state machine: automatically pauses an active desktop MPRIS media player
 * when a paired phone rings or answers a call, remembers ONLY that player, and
 * resumes it cleanly once all calls end (never resuming media that was already paused).
 */
class CallMediaDuckingCoordinator(
    private val isMediaPlayingProvider: () -> String?,
    private val pausePlayer: (playerId: String) -> Boolean,
    private val resumePlayer: (playerId: String) -> Boolean,
) {
    private val lock = ReentrantLock()
    private val activeCallIds = LinkedHashSet<String>()
    private var pausedPlayerId: String? = null

    val duckedPlayerId: String?
        get() = lock.withLock { pausedPlayerId }

    fun onCallStateUpdate(call: CallStateInfo): String? = lock.withLock {
        when (call.state) {
            PhoneCallState.RINGING, PhoneCallState.ACTIVE -> {
                activeCallIds.add(call.callId)
                if (pausedPlayerId == null) {
                    val playing = isMediaPlayingProvider()?.takeIf { SAFE_PLAYER_ID.matches(it) }
                    if (playing != null && pausePlayer(playing)) {
                        pausedPlayerId = playing
                    }
                }
            }
            PhoneCallState.ENDED, PhoneCallState.MISSED -> {
                activeCallIds.remove(call.callId)
                if (activeCallIds.isEmpty()) {
                    val toResume = pausedPlayerId
                    pausedPlayerId = null
                    if (toResume != null) {
                        resumePlayer(toResume)
                    }
                }
            }
        }
        pausedPlayerId
    }

    fun reset() = lock.withLock {
        activeCallIds.clear()
        pausedPlayerId = null
    }

    companion object {
        private val SAFE_PLAYER_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

/**
 * Coordinates full-duplex call audio routing (desktop mic + speaker ↔ phone)
 * and AirPlay-style phone-to-PC audio sink over PipeWire/PulseAudio modules
 * backed by user-private `0700` runtime FIFOs.
 */
class AudioHandoffService(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val pactlBinary: String = "pactl",
    private val runtimeDir: File = defaultAudioRuntimeDir(),
    private val onRemoteAudioCommand: (AudioRouteCommand) -> AudioRouteStatusInfo? = { null },
) : Closeable {

    private val lock = ReentrantLock()
    private var loadedSinkModuleId: Int? = null
    private var loadedSourceModuleId: Int? = null

    /** Optional post-construction handler used by AndroidCompanionRuntime to apply remote audio commands. */
    @Volatile
    var onRemoteAudioCommandHandler: ((AudioRouteCommand) -> AudioRouteStatusInfo?)? = null

    private val _status = MutableStateFlow(AudioRouteStatusInfo(active = false, mode = "idle"))
    val status: StateFlow<AudioRouteStatusInfo> = _status.asStateFlow()

    val speakerPipeFile: File get() = File(runtimeDir, "ec0-audio-speaker.pcm")
    val micPipeFile: File get() = File(runtimeDir, "ec0-audio-mic.pcm")

    fun buildLoadPipeSinkCommand(
        sinkName: String = "Ec0PhoneAudioSink",
        sampleRateHz: Int = 48_000,
        channels: Int = 2,
    ): List<String> {
        require(sampleRateHz in AudioRoutingProtocol.VALID_SAMPLE_RATES)
        require(channels in 1..2)
        EncryptedIdentityStore.ensureDirectoryPermissions(runtimeDir)
        return listOf(
            pactlBinary,
            "load-module",
            "module-pipe-sink",
            "sink_name=$sinkName",
            "file=${speakerPipeFile.absolutePath}",
            "format=s16le",
            "rate=$sampleRateHz",
            "channels=$channels",
            "sink_properties=device.description=ec0_Phone_Audio_Bridge",
        )
    }

    fun buildLoadCallMicSourceCommand(
        sourceName: String = "Ec0CallAudioMic",
        sampleRateHz: Int = 48_000,
        channels: Int = 1,
    ): List<String> {
        require(sampleRateHz in AudioRoutingProtocol.VALID_SAMPLE_RATES)
        require(channels in 1..2)
        EncryptedIdentityStore.ensureDirectoryPermissions(runtimeDir)
        return listOf(
            pactlBinary,
            "load-module",
            "module-pipe-source",
            "source_name=$sourceName",
            "file=${micPipeFile.absolutePath}",
            "format=s16le",
            "rate=$sampleRateHz",
            "channels=$channels",
            "source_properties=device.description=ec0_Call_Audio_Mic",
        )
    }

    fun startDesktopAudioRoute(
        mode: String,
        sampleRateHz: Int = 48_000,
        channels: Int = if (mode == "media_sink") 2 else 1,
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AudioRouteStatusInfo = lock.withLock {
        require(mode == "call_duplex" || mode == "media_sink") { "Unsupported mode: $mode" }
        val current = _status.value
        if (current.active && current.mode == mode && current.sampleRateHz == sampleRateHz && current.channels == channels) {
            return current
        }
        stopDesktopAudioRouteLocked(sendWireStop = false, session = null, nowMs = nowMs)

        val sinkRes = runCatching {
            launcher.runBlockingCommand(
                buildLoadPipeSinkCommand(sampleRateHz = sampleRateHz, channels = channels),
                timeoutMs = 3_000L,
            )
        }.getOrNull()
        if (sinkRes != null && sinkRes.exitCode == 0) {
            loadedSinkModuleId = sinkRes.stdout.trim().toIntOrNull()
        }
        if (mode == "call_duplex") {
            val srcRes = runCatching {
                launcher.runBlockingCommand(
                    buildLoadCallMicSourceCommand(sampleRateHz = sampleRateHz, channels = 1),
                    timeoutMs = 3_000L,
                )
            }.getOrNull()
            if (srcRes != null && srcRes.exitCode == 0) {
                loadedSourceModuleId = srcRes.stdout.trim().toIntOrNull()
            }
        }

        val cmdAction = if (mode == "call_duplex") {
            AudioRoutingProtocol.ACTION_START_CALL_AUDIO
        } else {
            AudioRoutingProtocol.ACTION_START_MEDIA_SINK
        }
        val cmd = AudioRouteCommand(
            action = cmdAction,
            codec = "pcm_s16le",
            sampleRateHz = sampleRateHz,
            channels = channels,
            duplex = mode == "call_duplex",
        )
        var wireSent = false
        if (session != null && !session.isClosed) {
            wireSent = runCatching {
                session.sendCmd(
                    cmdType = AudioRoutingProtocol.CMD_AUDIO_ROUTE,
                    cmdBody = cmd.toJsonObject(),
                    category = AudioRoutingProtocol.CATEGORY_AUDIO,
                    timestampMs = nowMs,
                )
                true
            }.getOrDefault(false)
        }

        val routeStarted = loadedSinkModuleId != null || wireSent
        val next = if (routeStarted) {
            AudioRouteStatusInfo(
                active = true,
                mode = mode,
                codec = cmd.codec,
                sampleRateHz = sampleRateHz,
                channels = channels,
            )
        } else {
            AudioRouteStatusInfo(active = false, mode = "idle")
        }
        _status.value = next
        next
    }

    fun stopDesktopAudioRoute(
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): AudioRouteStatusInfo = lock.withLock {
        stopDesktopAudioRouteLocked(sendWireStop = true, session = session, nowMs = nowMs)
    }

    private fun stopDesktopAudioRouteLocked(
        sendWireStop: Boolean,
        session: Ec0Session?,
        nowMs: Long,
    ): AudioRouteStatusInfo {
        val prevMode = _status.value.mode
        loadedSinkModuleId?.let { id ->
            runCatching { launcher.runBlockingCommand(listOf(pactlBinary, "unload-module", id.toString()), 2_000L) }
        }
        loadedSourceModuleId?.let { id ->
            runCatching { launcher.runBlockingCommand(listOf(pactlBinary, "unload-module", id.toString()), 2_000L) }
        }
        loadedSinkModuleId = null
        loadedSourceModuleId = null

        if (sendWireStop && session != null && prevMode != "idle") {
            val stopAction = if (prevMode == "call_duplex") {
                AudioRoutingProtocol.ACTION_STOP_CALL_AUDIO
            } else {
                AudioRoutingProtocol.ACTION_STOP_MEDIA_SINK
            }
            val cmd = AudioRouteCommand(action = stopAction)
            session.sendCmd(
                cmdType = AudioRoutingProtocol.CMD_AUDIO_ROUTE,
                cmdBody = cmd.toJsonObject(),
                category = AudioRoutingProtocol.CATEGORY_AUDIO,
                timestampMs = nowMs,
            )
        }
        val idle = AudioRouteStatusInfo(active = false, mode = "idle")
        _status.value = idle
        return idle
    }

    fun handleControlEnvelope(envelope: Ec0Envelope, session: Ec0Session? = null): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            AudioRoutingProtocol.CMD_AUDIO_ROUTE -> {
                val cmd = AudioRouteCommand.fromJsonObject(body)
                val handler = onRemoteAudioCommandHandler ?: onRemoteAudioCommand
                val updated = runCatching { handler(cmd) }.getOrNull()
                if (updated != null) {
                    _status.value = updated
                    session?.let { s ->
                        runCatching {
                            s.sendInfo(
                                infoType = AudioRoutingProtocol.INFO_AUDIO_STATUS,
                                infoBody = updated.toJsonObject(),
                                category = AudioRoutingProtocol.CATEGORY_AUDIO,
                                timestampMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
                true
            }
            AudioRoutingProtocol.INFO_AUDIO_STATUS -> {
                _status.value = AudioRouteStatusInfo.fromJsonObject(body)
                true
            }
            else -> false
        }
    }

    override fun close() {
        stopDesktopAudioRoute(session = null)
    }

    companion object {
        fun defaultAudioRuntimeDir(): File {
            val xdgRuntime = System.getenv("XDG_RUNTIME_DIR")?.trim().orEmpty()
            val dir = if (xdgRuntime.isNotEmpty() && File(xdgRuntime).isDirectory) {
                File(xdgRuntime, "ec0")
            } else {
                File(EncryptedIdentityStore.defaultConfigDir(), "run")
            }
            EncryptedIdentityStore.ensureDirectoryPermissions(dir)
            return dir
        }
    }
}
