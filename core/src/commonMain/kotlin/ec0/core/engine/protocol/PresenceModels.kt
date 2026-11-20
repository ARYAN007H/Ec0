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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object PresenceProtocol {
    const val CATEGORY_PRESENCE: String = "presence"
    const val INFO_PRESENCE_BEACON: String = "presence_beacon"
    const val INFO_APP_LIST: String = "app_list"

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

/**
 * Pure B3 tile-contract mapping: beacon capabilities → canonical wireName set
 * ("mirror", "cam", "clip", …). Both dashboard tile bridges (desktop
 * `DesktopBridge.tiles`, phone `AndroidAppState.tiles`) feed this into
 * [ec0.ui.models.computeTileRegistry] — :ui stays core-free because the
 * mapping happens here, one hop from the beacon model it consumes.
 */
fun beaconCapabilityWireNames(capabilities: Iterable<EngineCapability>): Set<String> =
    capabilities.map { it.wireName }.toSet()

/**
 * Launchable Android application descriptor synced from phone → desktop for
 * Individual App Streaming (`scrcpy --new-display --start-app=<pkg>`).
 */
data class AndroidAppInfo(
    val packageName: String,
    val label: String,
) {
    init {
        require(ScrcpyMirrorStartCommand.isValidPackageName(packageName)) {
            "Invalid Android packageName: '$packageName'"
        }
    }

    fun sanitized(): AndroidAppInfo = copy(
        label = label.filter { it.code >= 32 && it.code != 127 }.trim().take(64).ifEmpty { packageName },
    )

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("label", label)
        put("pkg", packageName)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): AndroidAppInfo? {
            val pkg = (obj["pkg"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!ScrcpyMirrorStartCommand.isValidPackageName(pkg)) return null
            val label = (obj["label"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: pkg
            return AndroidAppInfo(packageName = pkg, label = label).sanitized()
        }
    }
}

data class AppListInfo(
    val apps: List<AndroidAppInfo>,
) {
    fun sanitized(): AppListInfo = copy(
        apps = apps.take(MAX_APPS).map { it.sanitized() },
    )

    fun toJsonObject(): JsonObject = buildJsonObject {
        put(
            "apps",
            buildJsonArray {
                for (app in apps.take(MAX_APPS)) add(app.toJsonObject())
            },
        )
    }

    companion object {
        const val MAX_APPS: Int = 120

        fun fromJsonObject(obj: JsonObject): AppListInfo {
            val arr = obj["apps"] as? JsonArray ?: return AppListInfo(emptyList())
            val list = arr.take(MAX_APPS).mapNotNull { el ->
                (el as? JsonObject)?.let { AndroidAppInfo.fromJsonObject(it) }
            }
            return AppListInfo(list)
        }
    }
}

data class ScrcpyMirrorStartCommand(
    val host: String,
    val adbPort: Int = 5555,
    val maxFps: Int = 60,
    val bitrateKbps: Int = 8000,
    val windowTitle: String = "ec0 Mirror",
    val audioEnabled: Boolean = true,
    val turnScreenOff: Boolean = false,
    val stayAwake: Boolean = false,
    val maxSize: Int = 0,
    /** Optional Android package name for virtual-display App Streaming (`--start-app=`). */
    val startApp: String? = null,
    /** Optional virtual display geometry (`WxH` or `WxH/DPI`, e.g. `1080x1920/420`). */
    val newDisplay: String? = null,
) {
    init {
        require(host.isNotBlank()) { "Mirror host must not be blank" }
        require(adbPort in 1..65535) { "adbPort out of range: $adbPort" }
        require(maxFps in 1..240) { "maxFps out of range: $maxFps" }
        require(bitrateKbps in 500..100_000) { "bitrateKbps out of range: $bitrateKbps" }
        require(maxSize == 0 || maxSize in 320..4096) { "maxSize out of range: $maxSize" }
        if (startApp != null) {
            require(isValidPackageName(startApp)) { "Invalid startApp package name: '$startApp'" }
        }
        if (newDisplay != null) {
            require(isValidNewDisplaySpec(newDisplay)) { "Invalid newDisplay geometry: '$newDisplay'" }
        }
    }

    val serial: String
        get() = "$host:$adbPort"

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("adb_port", adbPort)
        put("audio_enabled", audioEnabled)
        put("bitrate_kbps", bitrateKbps)
        put("host", host)
        put("max_fps", maxFps)
        if (maxSize > 0) put("max_size", maxSize)
        if (newDisplay != null) put("new_display", newDisplay)
        if (startApp != null) put("start_app", startApp)
        if (stayAwake) put("stay_awake", true)
        if (turnScreenOff) put("turn_screen_off", true)
        put("window_title", windowTitle)
    }

    companion object {
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        private val NEW_DISPLAY_REGEX = Regex("^[1-9]\\d{2,3}x[1-9]\\d{2,3}(/[1-9]\\d{1,2})?$")

        fun isValidPackageName(pkg: String): Boolean =
            pkg.length in 3..128 && PACKAGE_NAME_REGEX.matches(pkg)

        fun isValidNewDisplaySpec(spec: String): Boolean =
            spec.length in 7..16 && NEW_DISPLAY_REGEX.matches(spec)

        fun fromJsonObject(obj: JsonObject): ScrcpyMirrorStartCommand {
            val host = (obj["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'host' in mirror_start")
            val adbPort = (obj["adb_port"] as? JsonPrimitive)?.intOrNull ?: 5555
            val maxFps = (obj["max_fps"] as? JsonPrimitive)?.intOrNull ?: 60
            val bitrateKbps = (obj["bitrate_kbps"] as? JsonPrimitive)?.intOrNull ?: 8000
            val windowTitle = (obj["window_title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "ec0 Mirror"
            val audioEnabled = (obj["audio_enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            val turnScreenOff = (obj["turn_screen_off"] as? JsonPrimitive)?.booleanOrNull ?: false
            val stayAwake = (obj["stay_awake"] as? JsonPrimitive)?.booleanOrNull ?: false
            val maxSize = (obj["max_size"] as? JsonPrimitive)?.intOrNull ?: 0
            val startApp = (obj["start_app"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            val newDisplay = (obj["new_display"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            return ScrcpyMirrorStartCommand(
                host = host,
                adbPort = adbPort,
                maxFps = maxFps,
                bitrateKbps = bitrateKbps,
                windowTitle = windowTitle,
                audioEnabled = audioEnabled,
                turnScreenOff = turnScreenOff,
                stayAwake = stayAwake,
                maxSize = maxSize,
                startApp = startApp,
                newDisplay = newDisplay,
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
