/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Wire models for ec0-engine Presence Beacons (`category = "presence"`,
 * `info_type = "presence_beacon"`) and Scrcpy Screen Mirroring coordination
 * (`category = "mirror"`, `cmd_type = "mirror_start" | "mirror_stop"`,
 * docs/engine/design.md §6.5, §6.7, Amendment 5).
 */
package ec0.core.engine.protocol

import ec0.core.engine.EngineCapability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object PresenceProtocol {
    const val CATEGORY_PRESENCE: String = "presence"
    const val INFO_PRESENCE_BEACON: String = "presence_beacon"

    const val CATEGORY_MIRROR: String = "mirror"
    const val CMD_MIRROR_START: String = "mirror_start"
    const val CMD_MIRROR_STOP: String = "mirror_stop"
}

data class PresenceBeaconInfo(
    val batteryPct: Int,
    val isCharging: Boolean,
    val screenOn: Boolean,
    val capabilities: Set<EngineCapability> = EngineCapability.entries.toSet(),
    val wifiSsidHash: String? = null,
) {
    init {
        require(batteryPct in 0..100) { "batteryPct must be in 0..100 (got $batteryPct)" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("battery_pct", batteryPct)
        put("caps", EngineCapability.encodeCsv(capabilities))
        put("charging", isCharging)
        put("screen_on", screenOn)
        if (wifiSsidHash != null) {
            put("wifi_ssid_hash", wifiSsidHash)
        }
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): PresenceBeaconInfo {
            val batteryPct = (obj["battery_pct"] as? JsonPrimitive)?.intOrNull
                ?: throw IllegalArgumentException("Missing 'battery_pct' in presence_beacon")
            val charging = (obj["charging"] as? JsonPrimitive)?.booleanOrNull ?: false
            val screenOn = (obj["screen_on"] as? JsonPrimitive)?.booleanOrNull ?: true
            val capsCsv = (obj["caps"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
            val ssidHash = (obj["wifi_ssid_hash"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return PresenceBeaconInfo(
                batteryPct = batteryPct,
                isCharging = charging,
                screenOn = screenOn,
                capabilities = EngineCapability.parseCsv(capsCsv).ifEmpty { EngineCapability.entries.toSet() },
                wifiSsidHash = ssidHash,
            )
        }
    }
}

data class ScrcpyMirrorStartCommand(
    val host: String,
    val adbPort: Int = 5555,
    val maxFps: Int = 60,
    val bitrateKbps: Int = 8000,
    val windowTitle: String = "ec0 Mirror",
) {
    init {
        require(host.isNotBlank()) { "Mirror host must not be blank" }
        require(adbPort in 1..65535) { "adbPort out of range: $adbPort" }
        require(maxFps in 1..240) { "maxFps out of range: $maxFps" }
        require(bitrateKbps in 500..100_000) { "bitrateKbps out of range: $bitrateKbps" }
    }

    val serial: String
        get() = "$host:$adbPort"

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("adb_port", adbPort)
        put("bitrate_kbps", bitrateKbps)
        put("host", host)
        put("max_fps", maxFps)
        put("window_title", windowTitle)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): ScrcpyMirrorStartCommand {
            val host = (obj["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'host' in mirror_start")
            val adbPort = (obj["adb_port"] as? JsonPrimitive)?.intOrNull ?: 5555
            val maxFps = (obj["max_fps"] as? JsonPrimitive)?.intOrNull ?: 60
            val bitrateKbps = (obj["bitrate_kbps"] as? JsonPrimitive)?.intOrNull ?: 8000
            val windowTitle = (obj["window_title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "ec0 Mirror"
            return ScrcpyMirrorStartCommand(
                host = host,
                adbPort = adbPort,
                maxFps = maxFps,
                bitrateKbps = bitrateKbps,
                windowTitle = windowTitle,
            )
        }
    }
}

data class ScrcpyMirrorStopCommand(
    val reason: String = "stopped_by_user",
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("reason", reason)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): ScrcpyMirrorStopCommand {
            val reason = (obj["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "stopped_by_user"
            return ScrcpyMirrorStopCommand(reason)
        }
    }
}
