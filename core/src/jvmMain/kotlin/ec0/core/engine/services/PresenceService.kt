/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Presence & Battery Beacon Service (`docs/engine/design.md` §6.5):
 *   - Broadcasts signed `presence_beacon` info envelopes (`battery_pct`,
 *     `charging`, `screen_on`, `caps`, `wifi_ssid_hash`).
 *   - Tracks live presence state per connected `peerDeviceId`.
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.protocol.AndroidAppInfo
import ec0.core.engine.protocol.AppListInfo
import ec0.core.engine.protocol.PresenceBeaconInfo
import ec0.core.engine.protocol.PresenceProtocol
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class PresenceService {
    private val lock = ReentrantLock()
    private val _peerPresence = MutableStateFlow<Map<String, PresenceBeaconInfo>>(emptyMap())
    val peerPresence: StateFlow<Map<String, PresenceBeaconInfo>> = _peerPresence.asStateFlow()

    private val _peerApps = MutableStateFlow<Map<String, List<AndroidAppInfo>>>(emptyMap())
    val peerApps: StateFlow<Map<String, List<AndroidAppInfo>>> = _peerApps.asStateFlow()

    fun broadcastBeacon(
        beacon: PresenceBeaconInfo,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ) = broadcastBeacon(beacon) { body ->
        session.sendInfo(
            infoType = PresenceProtocol.INFO_PRESENCE_BEACON,
            infoBody = body,
            category = PresenceProtocol.CATEGORY_PRESENCE,
            timestampMs = timestampMs,
        )
    }

    fun broadcastBeacon(
        beacon: PresenceBeaconInfo,
        sendInfo: (JsonObject) -> Unit,
    ) {
        sendInfo(beacon.toJsonObject())
    }

    fun broadcastAppList(
        apps: List<AndroidAppInfo>,
        session: Ec0Session,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val sanitized = AppListInfo(apps).sanitized()
        session.sendInfo(
            infoType = PresenceProtocol.INFO_APP_LIST,
            infoBody = sanitized.toJsonObject(),
            category = PresenceProtocol.CATEGORY_PRESENCE,
            timestampMs = timestampMs,
        )
    }

    /**
     * Handles an incoming verified `presence_beacon` or `app_list` [Ec0Envelope] from [peerDeviceId].
     */
    fun handleControlEnvelope(peerDeviceId: String, envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: return false
        return when (envelope.bodyType) {
            PresenceProtocol.INFO_PRESENCE_BEACON -> {
                val beacon = PresenceBeaconInfo.fromJsonObject(body)
                lock.withLock {
                    val next = LinkedHashMap(_peerPresence.value)
                    next[peerDeviceId] = beacon
                    _peerPresence.value = next
                }
                true
            }
            PresenceProtocol.INFO_APP_LIST -> {
                val appList = AppListInfo.fromJsonObject(body).sanitized()
                lock.withLock {
                    val next = LinkedHashMap(_peerApps.value)
                    next[peerDeviceId] = appList.apps
                    _peerApps.value = next
                }
                true
            }
            else -> false
        }
    }
}
