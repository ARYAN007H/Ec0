package ec0.core.engine.smartconnect.protocol

import ec0.core.engine.smartconnect.SmartConnectConstants
import ec0.core.engine.smartconnect.SmartConnectEnvelope
import ec0.core.engine.smartconnect.SmartConnectEnvelopeCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Wire models and replace-whole capability registry (`SmartConnectCapRegistry`) for Smart Connect
 * Service `25` (`svc 25`) device status, capability map (`cmd 1`), phone wallpaper (`cmd 2`),
 * DND state (`cmd 5`), and connect-burst display metrics (`svc 1 cmd 31`, `svc 16 cmd 14`).
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §2.1, §3-S-A & `device-status-and-tiles.md`.
 */
@Serializable
data class SmartConnectStatusAndCapPayload(
    val ver: String = "1",
    val cmd: Int = SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY,
    val cap: Map<String, Int> = SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP,
    val battery: Int = 100,
    val deviceName: String = "EC0-PC",
    val realDeviceName: String = "EC0-PC",
    val model: String = "Linux PC",
    val data: SmartConnectStatusAndCapData = SmartConnectStatusAndCapData()
)

@Serializable
data class SmartConnectStatusAndCapData(
    val battery: Int = 100,
    val charge: Int = 1,
    val volume: Int = 50,
    val mute: Boolean = false,
    val dnd: Int = 1,
    val camera: Int = 1,
    val cap: Map<String, Int> = SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP
)

@Serializable
data class SmartConnectWallpaperPayload(
    val ver: String = "1",
    val cmd: Int = SmartConnectConstants.STATUS_CMD_WALLPAPER,
    val wallpaper: String = "",
    val md5: String = "",
    val data: SmartConnectWallpaperData = SmartConnectWallpaperData()
)

@Serializable
data class SmartConnectWallpaperData(
    val wallpaper: String = "",
    val md5: String = ""
)

@Serializable
data class SmartConnectDndPayload(
    val ver: String = "1",
    val cmd: Int = SmartConnectConstants.STATUS_CMD_DND,
    val dnd: Int = 1,
    val dndState: Int = 0,
    val data: SmartConnectDndData = SmartConnectDndData()
)

@Serializable
data class SmartConnectDndData(
    val dnd: Int = 1
)

/**
 * Connect-burst display metrics sent by the phone on `svc 1 cmd 31` or `svc 16 cmd 14` immediately
 * after pairing/reconnecting. Absorbed cleanly in Slice `S-A` without closing the session.
 */
@Serializable
data class SmartConnectDisplayMetricsBurst(
    val width: Int = 0,
    val height: Int = 0,
    val dpi: Int = 0,
    val rotation: Int = 0
)

/**
 * Snapshot of the paired phone's live status learned via `svc 25 cmd 1`, `cmd 2`, and `cmd 5`.
 */
@Serializable
data class SmartConnectPeerStatusSnapshot(
    val realDeviceId: String,
    val deviceName: String = "",
    val battery: Int = -1,
    val charge: Int = 0,
    val volume: Int = -1,
    val mute: Boolean = false,
    val dnd: Int = 1,
    val camera: Int = 0,
    val peerCap: Map<String, Int> = emptyMap(),
    val wallpaperMd5: String? = null,
    val wallpaperPath: String? = null,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val displayDpi: Int = 0,
    val updatedAtMs: Long = 0L
)

/**
 * Replace-whole capability map registry (`svc 25 cmd 1`).
 *
 * CRITICAL INVARIANT (§2.1):
 * The phone replaces its entire stored `cap` map on every `svc 25 cmd 1` message (`c0.o.v` calls
 * `device.setCap(map)`). Sending a partial `{"clipboard":1}` map would erase all previously
 * advertised capabilities. [SmartConnectCapRegistry] maintains the authoritative merged capability
 * map and always emits the complete current map.
 *
 * In Slice `S-A`, [initialCapMap] defaults to [SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP]
 * (`emptyMap()`), so the first live pairing renders the PC device page with zero feature tiles
 * until Slice `S-B`+ handlers register their capabilities.
 */
class SmartConnectCapRegistry(
    initialCapMap: Map<String, Int> = SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP
) {
    private val currentCaps = LinkedHashMap<String, Int>().apply {
        putAll(initialCapMap)
    }

    fun snapshot(): Map<String, Int> = LinkedHashMap(currentCaps)

    fun registerCapability(key: String, value: Int): Map<String, Int> {
        require(key.isNotBlank()) { "Capability key must not be blank" }
        currentCaps[key] = value
        return snapshot()
    }

    fun unregisterCapability(key: String): Map<String, Int> {
        currentCaps.remove(key)
        return snapshot()
    }

    fun replaceAll(newCapMap: Map<String, Int>): Map<String, Int> {
        currentCaps.clear()
        currentCaps.putAll(newCapMap)
        return snapshot()
    }
}

object SmartConnectStatusCodec {

    fun buildStatusAndCapFrame(
        pcNonceIdBase64: String,
        msgId: Int,
        capMap: Map<String, Int> = SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP,
        pcDeviceName: String = "EC0-PC",
        battery: Int = 100,
        charge: Int = 1,
        volume: Int = 50,
        mute: Boolean = false,
        dnd: Int = 1,
        camera: Int = 1
    ): String {
        val cleanName = pcDeviceName.ifBlank { "EC0-PC" }
        val innerData = SmartConnectStatusAndCapData(
            battery = battery,
            charge = charge,
            volume = volume,
            mute = mute,
            dnd = dnd,
            camera = camera,
            cap = capMap
        )
        val payload = SmartConnectStatusAndCapPayload(
            ver = "1",
            cmd = SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY,
            cap = capMap,
            battery = battery,
            deviceName = cleanName,
            realDeviceName = cleanName,
            model = "Linux PC",
            data = innerData
        )
        return SmartConnectEnvelopeCodec.encodeTypedFrame(
            deviceId = pcNonceIdBase64,
            srcServiceId = SmartConnectConstants.SVC_DEVICE_STATUS,
            serviceId = SmartConnectConstants.SVC_DEVICE_STATUS,
            msgId = msgId,
            innerData = payload
        )
    }

    fun parseStatusAndCap(envelope: SmartConnectEnvelope): SmartConnectStatusAndCapPayload {
        require(envelope.serviceId == SmartConnectConstants.SVC_DEVICE_STATUS) {
            "Expected serviceId=25 for status/cap, got ${envelope.serviceId}"
        }
        val dataObj = SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data)
        val nested = dataObj["data"] as? JsonObject
        if (nested != null) {
            val parsedData = SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectStatusAndCapData>(nested)
            val devName = (dataObj["deviceName"] as? JsonPrimitive)?.content ?: "Motorola Phone"
            return SmartConnectStatusAndCapPayload(
                cmd = SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY,
                cap = parsedData.cap,
                battery = parsedData.battery,
                deviceName = devName,
                realDeviceName = devName,
                data = parsedData
            )
        }
        val bat = (dataObj["battery"] as? JsonPrimitive)?.intOrNull ?: 100
        val isChargingBool = (dataObj["isCharging"] as? JsonPrimitive)?.booleanOrNull
        val chg = when {
            isChargingBool == true -> 1
            isChargingBool == false -> 0
            else -> (dataObj["charge"] as? JsonPrimitive)?.intOrNull ?: 0
        }
        val vol = (dataObj["volume"] as? JsonPrimitive)?.intOrNull ?: 50
        val mute = (dataObj["mute"] as? JsonPrimitive)?.booleanOrNull ?: false
        val dnd = (dataObj["dnd"] as? JsonPrimitive)?.intOrNull ?: 1
        val cam = (dataObj["camera"] as? JsonPrimitive)?.intOrNull ?: 1
        val devName = (dataObj["deviceName"] as? JsonPrimitive)?.content ?: "Motorola Phone"
        val model = (dataObj["model"] as? JsonPrimitive)?.content ?: "Android"
        val capMap = mutableMapOf<String, Int>()
        (dataObj["cap"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.intOrNull?.let { capMap[k] = it }
        }
        val statusData = SmartConnectStatusAndCapData(
            battery = bat,
            charge = chg,
            volume = vol,
            mute = mute,
            dnd = dnd,
            camera = cam,
            cap = capMap
        )
        return SmartConnectStatusAndCapPayload(
            cmd = SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY,
            cap = capMap,
            battery = bat,
            deviceName = devName,
            realDeviceName = devName,
            model = model,
            data = statusData
        )
    }

    fun parseWallpaper(envelope: SmartConnectEnvelope): SmartConnectWallpaperPayload {
        require(envelope.serviceId == SmartConnectConstants.SVC_DEVICE_STATUS) {
            "Expected serviceId=25 for wallpaper, got ${envelope.serviceId}"
        }
        val dataObj = SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data)
        val nested = dataObj["data"] as? JsonObject
        if (nested != null && nested.containsKey("wallpaper")) {
            val d = SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectWallpaperData>(nested)
            return SmartConnectWallpaperPayload(wallpaper = d.wallpaper, md5 = d.md5, data = d)
        }
        val wp = (dataObj["wallpaper"] as? JsonPrimitive)?.content.orEmpty()
        val md5 = (dataObj["md5"] as? JsonPrimitive)?.content.orEmpty()
        val d = SmartConnectWallpaperData(wallpaper = wp, md5 = md5)
        return SmartConnectWallpaperPayload(wallpaper = wp, md5 = md5, data = d)
    }

    fun parseDnd(envelope: SmartConnectEnvelope): SmartConnectDndPayload {
        require(envelope.serviceId == SmartConnectConstants.SVC_DEVICE_STATUS) {
            "Expected serviceId=25 for DND, got ${envelope.serviceId}"
        }
        val dataObj = SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data)
        val nested = dataObj["data"] as? JsonObject
        if (nested != null && nested.containsKey("dnd")) {
            val d = SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectDndData>(nested)
            return SmartConnectDndPayload(dnd = d.dnd, data = d)
        }
        val dndVal = (dataObj["dnd"] as? JsonPrimitive)?.intOrNull
            ?: (dataObj["dndState"] as? JsonPrimitive)?.intOrNull
            ?: 1
        val d = SmartConnectDndData(dnd = dndVal)
        return SmartConnectDndPayload(dnd = dndVal, dndState = dndVal, data = d)
    }
}
