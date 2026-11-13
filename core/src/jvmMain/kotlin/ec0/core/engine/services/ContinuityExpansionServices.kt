/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Continuity Expansion Services (Slice 9 Improvements #4, #8, #9):
 *   1. UniversalControlService — screen-edge cursor barrier state machine that
 *      detects when the local desktop pointer hits a configured monitor edge
 *      (`LEFT`, `RIGHT`, `TOP`, `BOTTOM`), transitions input routing to the paired
 *      peer (`CursorHandoffCommand`), and handles return handoff.
 *   2. HotspotAndTelephonyService — Instant Hotspot activation + Linux `nmcli`
 *      Wi-Fi auto-join command builder, plus Android call state relay (`RINGING`,
 *      `ACTIVE`, `ENDED`) that automatically pauses Linux desktop media playback
 *      (`playerctl pause`) when a phone call arrives.
 *   3. MediaSessionBridgeService — bidirectional Android `MediaSession` <-> Linux
 *      MPRIS2 (`playerctl`) media transport & track metadata sync.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.HotspotAndTelephonyProtocol
import ec0.core.engine.protocol.HotspotControlCommand
import ec0.core.engine.protocol.HotspotStatusInfo
import ec0.core.engine.protocol.MediaControlCommand
import ec0.core.engine.protocol.MediaControlProtocol
import ec0.core.engine.protocol.MediaSessionInfo
import ec0.core.engine.protocol.PhoneCallState
import ec0.core.engine.protocol.ScreenEdge
import ec0.core.engine.protocol.UniversalControlProtocol
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages Universal Control screen-edge cursor handoff between Linux desktop and
 * paired Android / Linux devices.
 */
class UniversalControlService(
    initialTargetEdge: ScreenEdge = ScreenEdge.RIGHT,
    private val handoffCooldownMs: Long = 250L,
    private val onRemoteCursorHandoff: (CursorHandoffCommand) -> Unit = {},
) {
    private val lock = ReentrantLock()
    private var lastTransitionMs: Long = 0L

    private val _configuredEdge = MutableStateFlow(initialTargetEdge)
    val configuredEdge: StateFlow<ScreenEdge> = _configuredEdge.asStateFlow()

    private val _activeRemotePeerId = MutableStateFlow<String?>(null)
    val activeRemotePeerId: StateFlow<String?> = _activeRemotePeerId.asStateFlow()

    private val _lastHandoffEvent = MutableStateFlow<CursorHandoffCommand?>(null)
    val lastHandoffEvent: StateFlow<CursorHandoffCommand?> = _lastHandoffEvent.asStateFlow()

    fun setConfiguredEdge(edge: ScreenEdge) {
        _configuredEdge.value = edge
    }

    /**
     * Evaluates local pointer coordinates `(x, y)` against the monitor bounds
     * `(screenWidth, screenHeight)`. When the pointer reaches the configured edge,
     * sends a `cursor_handoff` (`enter`) command to [peerDeviceId] over [session].
     */
    fun evaluateLocalPointerAtEdge(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        peerDeviceId: String,
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): CursorHandoffCommand? = lock.withLock {
        if (screenWidth <= 1 || screenHeight <= 1) return null
        if (nowMs - lastTransitionMs < handoffCooldownMs) return null

        val edge = _configuredEdge.value
        val hitEdge = when (edge) {
            ScreenEdge.LEFT -> x <= 0
            ScreenEdge.RIGHT -> x >= screenWidth - 1
            ScreenEdge.TOP -> y <= 0
            ScreenEdge.BOTTOM -> y >= screenHeight - 1
        }
        if (!hitEdge || _activeRemotePeerId.value != null) return null

        val permille = when (edge) {
            ScreenEdge.LEFT, ScreenEdge.RIGHT ->
                ((y.coerceIn(0, screenHeight).toLong() * 1000L) / screenHeight.toLong()).toInt()
            ScreenEdge.TOP, ScreenEdge.BOTTOM ->
                ((x.coerceIn(0, screenWidth).toLong() * 1000L) / screenWidth.toLong()).toInt()
        }.coerceIn(0, 1000)

        val cmd = CursorHandoffCommand(
            action = CursorHandoffCommand.ACTION_ENTER,
            edge = edge,
            positionPermille = permille,
        )
        lastTransitionMs = nowMs
        _activeRemotePeerId.value = peerDeviceId
        _lastHandoffEvent.value = cmd

        session?.sendCmd(
            cmdType = UniversalControlProtocol.CMD_CURSOR_HANDOFF,
            cmdBody = cmd.toJsonObject(),
            category = UniversalControlProtocol.CATEGORY_INPUT,
            timestampMs = nowMs,
        )
        cmd
    }

    /**
     * Releases remote cursor capture and returns pointer focus to the local desktop.
     */
    fun returnCursorToLocal(
        session: Ec0Session? = null,
        positionPermille: Int = 500,
        nowMs: Long = System.currentTimeMillis(),
    ): CursorHandoffCommand = lock.withLock {
        val edge = _configuredEdge.value
        val cmd = CursorHandoffCommand(
            action = CursorHandoffCommand.ACTION_LEAVE,
            edge = edge.opposite(),
            positionPermille = positionPermille.coerceIn(0, 1000),
        )
        lastTransitionMs = nowMs
        _activeRemotePeerId.value = null
        _lastHandoffEvent.value = cmd

        session?.sendCmd(
            cmdType = UniversalControlProtocol.CMD_CURSOR_HANDOFF,
            cmdBody = cmd.toJsonObject(),
            category = UniversalControlProtocol.CATEGORY_INPUT,
            timestampMs = nowMs,
        )
        cmd
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        val bodyType = envelope.bodyType ?: envelope.msgType
        if (bodyType != UniversalControlProtocol.CMD_CURSOR_HANDOFF) return false

        val cmd = CursorHandoffCommand.fromJsonObject(body)
        lock.withLock {
            _lastHandoffEvent.value = cmd
            if (cmd.action == CursorHandoffCommand.ACTION_LEAVE) {
                _activeRemotePeerId.value = null
            }
        }
        onRemoteCursorHandoff(cmd)
        return true
    }
}

/**
 * Coordinates Instant Hotspot (`hotspot_control` / `hotspot_status` + Linux
 * `nmcli` auto-connect) and Telephony Call Relay (`call_state` / `call_action` +
 * automatic Linux media pause on incoming/active phone call).
 */
class HotspotAndTelephonyService(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val autoPauseLinuxMediaOnCall: Boolean = true,
) {
    private val lock = ReentrantLock()
    private var mediaPausedForCallId: String? = null

    private val _hotspotStatus = MutableStateFlow(HotspotStatusInfo(enabled = false))
    val hotspotStatus: StateFlow<HotspotStatusInfo> = _hotspotStatus.asStateFlow()

    private val _activeCalls = MutableStateFlow<List<CallStateInfo>>(emptyList())
    val activeCalls: StateFlow<List<CallStateInfo>> = _activeCalls.asStateFlow()

    /**
     * Builds the exact Linux NetworkManager (`nmcli`) argv list to join the
     * phone's Wi-Fi hotspot without shell interpolation.
     */
    fun buildNmcliConnectCommand(ssid: String, passphrase: String): List<String> {
        require(ssid.isNotBlank()) { "Hotspot SSID must not be blank" }
        return if (passphrase.isBlank()) {
            listOf("nmcli", "device", "wifi", "connect", ssid)
        } else {
            listOf("nmcli", "device", "wifi", "connect", ssid, "password", passphrase)
        }
    }

    /**
     * Requests the Android peer to toggle its Wi-Fi Hotspot (`hotspot_control`).
     */
    fun requestHotspotToggle(
        enable: Boolean,
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): HotspotControlCommand {
        val action = if (enable) HotspotControlCommand.ACTION_ENABLE else HotspotControlCommand.ACTION_DISABLE
        val cmd = HotspotControlCommand(action = action)
        session?.sendCmd(
            cmdType = HotspotAndTelephonyProtocol.CMD_HOTSPOT_CONTROL,
            cmdBody = cmd.toJsonObject(),
            category = HotspotAndTelephonyProtocol.CATEGORY_NET,
            timestampMs = nowMs,
        )
        return cmd
    }

    /**
     * Joins the currently advertised phone hotspot on Linux using `nmcli`.
     */
    fun joinHotspotOnLinux(status: HotspotStatusInfo = _hotspotStatus.value): Boolean {
        val ssid = status.ssid
        if (!status.enabled || ssid.isNullOrBlank()) return false
        val argv = buildNmcliConnectCommand(ssid, status.passphrase.orEmpty())
        return try {
            val res = launcher.runBlockingCommand(argv, timeoutMs = 10_000L)
            res.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Sends a telephony call action (`answer_speaker`, `reject`, `end_call`, `reject_sms`)
     * to the phone over [session].
     */
    fun sendCallAction(
        command: CallActionCommand,
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? {
        if (command.action == CallActionCommand.ACTION_REJECT ||
            command.action == CallActionCommand.ACTION_END_CALL ||
            command.action == CallActionCommand.ACTION_REJECT_WITH_SMS
        ) {
            lock.withLock {
                _activeCalls.value = _activeCalls.value.filterNot { it.callId == command.callId }
            }
        }
        return session?.sendCmd(
            cmdType = HotspotAndTelephonyProtocol.CMD_CALL_ACTION,
            cmdBody = command.toJsonObject(),
            category = HotspotAndTelephonyProtocol.CATEGORY_TELEPHONY,
            timestampMs = nowMs,
        )
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType ?: envelope.msgType) {
            HotspotAndTelephonyProtocol.INFO_HOTSPOT_STATUS -> {
                val status = HotspotStatusInfo.fromJsonObject(body)
                _hotspotStatus.value = status
                true
            }
            HotspotAndTelephonyProtocol.CMD_HOTSPOT_CONTROL -> {
                val cmd = HotspotControlCommand.fromJsonObject(body)
                val enabled = when (cmd.action) {
                    HotspotControlCommand.ACTION_ENABLE -> true
                    HotspotControlCommand.ACTION_DISABLE -> false
                    else -> _hotspotStatus.value.enabled
                }
                _hotspotStatus.value = _hotspotStatus.value.copy(enabled = enabled)
                true
            }
            HotspotAndTelephonyProtocol.INFO_CALL_STATE -> {
                val info = CallStateInfo.fromJsonObject(body)
                onCallStateReceived(info)
                true
            }
            HotspotAndTelephonyProtocol.CMD_CALL_ACTION -> {
                val cmd = CallActionCommand.fromJsonObject(body)
                if (cmd.action == CallActionCommand.ACTION_REJECT ||
                    cmd.action == CallActionCommand.ACTION_END_CALL ||
                    cmd.action == CallActionCommand.ACTION_REJECT_WITH_SMS
                ) {
                    lock.withLock {
                        _activeCalls.value = _activeCalls.value.filterNot { it.callId == cmd.callId }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun onCallStateReceived(info: CallStateInfo) = lock.withLock {
        val existing = _activeCalls.value.filterNot { it.callId == info.callId }
        if (info.state == PhoneCallState.ENDED || info.state == PhoneCallState.MISSED) {
            _activeCalls.value = existing
            if (mediaPausedForCallId == info.callId) {
                mediaPausedForCallId = null
            }
        } else {
            _activeCalls.value = existing + info
            if (autoPauseLinuxMediaOnCall &&
                (info.state == PhoneCallState.RINGING || info.state == PhoneCallState.ACTIVE) &&
                mediaPausedForCallId != info.callId
            ) {
                mediaPausedForCallId = info.callId
                runCatching {
                    launcher.runBlockingCommand(listOf("playerctl", "pause"), timeoutMs = 1_500L)
                }
            }
        }
    }
}

/**
 * Bridges Android `MediaSession` and Linux MPRIS2 (`playerctl`) media playback
 * state and transport controls across devices.
 */
class MediaSessionBridgeService(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val executeLocalPlayerctlOnIncomingCommand: Boolean = true,
) {
    private val _remoteMediaSession = MutableStateFlow<MediaSessionInfo?>(null)
    val remoteMediaSession: StateFlow<MediaSessionInfo?> = _remoteMediaSession.asStateFlow()

    /**
     * Builds the `playerctl` CLI argv for a given [MediaControlCommand].
     */
    fun buildPlayerctlCommand(command: MediaControlCommand): List<String>? =
        when (command.action) {
            MediaControlCommand.ACTION_PLAY -> listOf("playerctl", "play")
            MediaControlCommand.ACTION_PAUSE -> listOf("playerctl", "pause")
            MediaControlCommand.ACTION_PLAY_PAUSE -> listOf("playerctl", "play-pause")
            MediaControlCommand.ACTION_NEXT -> listOf("playerctl", "next")
            MediaControlCommand.ACTION_PREVIOUS -> listOf("playerctl", "previous")
            MediaControlCommand.ACTION_SET_VOLUME -> {
                val vol = command.volumePct ?: 100
                listOf("playerctl", "volume", "%.2f".format(java.util.Locale.US, vol / 100.0))
            }
            else -> null
        }

    fun sendMediaControl(
        command: MediaControlCommand,
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? = session?.sendCmd(
        cmdType = MediaControlProtocol.CMD_MEDIA_CONTROL,
        cmdBody = command.toJsonObject(),
        category = MediaControlProtocol.CATEGORY_MEDIA,
        timestampMs = nowMs,
    )

    fun broadcastMediaSession(
        info: MediaSessionInfo,
        session: Ec0Session? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Ec0Envelope? {
        _remoteMediaSession.value = info
        return session?.sendInfo(
            infoType = MediaControlProtocol.INFO_MEDIA_SESSION,
            infoBody = info.toJsonObject(),
            category = MediaControlProtocol.CATEGORY_MEDIA,
            timestampMs = nowMs,
        )
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType ?: envelope.msgType) {
            MediaControlProtocol.INFO_MEDIA_SESSION -> {
                _remoteMediaSession.value = MediaSessionInfo.fromJsonObject(body)
                true
            }
            MediaControlProtocol.CMD_MEDIA_CONTROL -> {
                val cmd = MediaControlCommand.fromJsonObject(body)
                if (executeLocalPlayerctlOnIncomingCommand) {
                    val argv = buildPlayerctlCommand(cmd)
                    if (argv != null) {
                        runCatching { launcher.runBlockingCommand(argv, timeoutMs = 1_500L) }
                    }
                }
                true
            }
            else -> false
        }
    }
}
