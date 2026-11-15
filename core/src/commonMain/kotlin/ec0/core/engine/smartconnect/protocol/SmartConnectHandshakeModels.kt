package ec0.core.engine.smartconnect.protocol

import ec0.core.engine.smartconnect.SmartConnectConstants
import ec0.core.engine.smartconnect.SmartConnectEnvelope
import ec0.core.engine.smartconnect.SmartConnectEnvelopeCodec
import ec0.core.engine.smartconnect.SmartConnectIdentity
import ec0.core.engine.smartconnect.SmartConnectWifiPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Wire models and builders for Smart Connect Service `0` (`svc 0`) 6-step handshake,
 * `EXCHANGE_KEY` (`cmd 1`), user disconnect (`cmd 6`), and `/readyfor/mdns` discovery invites.
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §3-S-A & `pairing-and-discovery.md`.
 */
@Serializable
data class SmartConnectPhoneHelloPayload(
    val cmd: Int = SmartConnectConstants.CMD_HELLO_PAIR,
    val data: SmartConnectPhoneHelloData
)

@Serializable
data class SmartConnectPhoneHelloData(
    val deviceId: String,
    val realDeviceId: String = "",
    val deviceName: String = "Motorola Phone",
    val ip: String = "",
    val ips: List<String> = emptyList(),
    val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val fp: String = "",
    val invitation: String? = null,
    val paired: Int = 0,
    val verCode: Int = 0,
    val verName: String = "",
    val type: Int = 1,
    val os: Int = 1,
    val sn: String? = null,
    val key: String? = null,
    val serviceVersions: Map<String, Int> = emptyMap()
)

/**
 * Step 2 (`svc 0 cmd 0`): PC Verify Reply sent in response to `cmd=0` (new pair) or `cmd=3`
 * (returning paired reconnect).
 *
 * CRITICAL INVARIANTS (§3-S-A Step 2 & `pairing-and-discovery.md` §Verify reply):
 * 1. `token = "OK"`, `paired = 1`, `connType = 1`, and a non-empty `device.realDeviceId`.
 * 2. `os = 6`, `type = 4`, `scType = 2`, `verCode = 903013000`, `verName = "09.0.3.013.000"`
 *    (`WIN_UNLOCKED` profile so the stock phone APK unlocks PC feature tiles).
 * 3. `sn` MUST be non-empty (`TextUtils.isEmpty(sn)` in `c0.o.w` aborts verification).
 * 4. `rfaServiceVersions` MUST NOT be present in the serialized JSON (`c0.o.w` crashes or ignores
 *    `serviceVersions` if `rfaServiceVersions` is included).
 */
@Serializable
data class SmartConnectPcVerifyReplyPayload(
    val ver: Int = 3,
    val cmd: Int = SmartConnectConstants.CMD_HELLO_PAIR,
    val code: Int = 200,
    val token: String = "OK",
    val paired: Int = 1,
    val connType: Int = 1,
    val device: SmartConnectPcVerifyDevice,
    val serviceVersions: Map<String, Int> = SmartConnectConstants.DEFAULT_SERVICE_VERSIONS,
    val data: SmartConnectPcVerifyData
)

@Serializable
data class SmartConnectPcVerifyDevice(
    val deviceId: String,
    val realDeviceId: String,
    val deviceName: String,
    val os: Int = SmartConnectConstants.PC_OS_WINDOWS,
    val type: Int = SmartConnectConstants.PC_TYPE_LAPTOP,
    val scType: Int = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
    val verCode: Long = SmartConnectConstants.PC_VER_CODE.toLong(),
    val verName: String = SmartConnectConstants.PC_VER_NAME,
    val appVer: String = SmartConnectConstants.PC_VER_NAME,
    val minVerCode: Map<String, Long> = SmartConnectConstants.MIN_VER_CODE_MAP,
    val sn: String,
    @kotlinx.serialization.SerialName("Sku")
    val skuUpper: String = "Linux PC",
    val sku: String = "Linux PC",
    val cap: Int = 0,
    val caps: Map<String, Int> = SmartConnectConstants.DEFAULT_DEVICE_CAPS,
    val serviceVersions: Map<String, Int> = SmartConnectConstants.DEFAULT_SERVICE_VERSIONS,
    val key: String,
    val isPaired: Boolean = true
)

@Serializable
data class SmartConnectPcVerifyData(
    val deviceId: String,
    val realDeviceId: String = "",
    val deviceName: String,
    val ip: String,
    val ips: List<String>,
    val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val os: Int = SmartConnectConstants.PC_OS_WINDOWS,
    val type: Int = SmartConnectConstants.PC_TYPE_LAPTOP,
    val scType: Int = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
    val verCode: Int = SmartConnectConstants.PC_VER_CODE,
    val verName: String = SmartConnectConstants.PC_VER_NAME,
    val sn: String,
    val key: String,
    val serviceVersions: Map<String, Int> = SmartConnectConstants.DEFAULT_SERVICE_VERSIONS
)

/**
 * Step 4 (`svc 0 cmd 1`): `EXCHANGE_KEY` payload.
 *
 * CRITICAL WIRE QUIRK (§3-S-A Step 4 & `pairing-and-discovery.md` §Pairing commit):
 * On PC -> Phone `EXCHANGE_KEY`, **both** `device.deviceId` AND `device.realDeviceId` MUST be set
 * to `<pcRealId>` (NOT `nonceId`). The phone keys its persistent paired-device database on the ID
 * learned during `EXCHANGE_KEY`.
 */
@Serializable
data class SmartConnectExchangeKeyPayload(
    val ver: Int = 3,
    val cmd: Int = SmartConnectConstants.CMD_EXCHANGE_KEY,
    val paired: Int = 1,
    val device: SmartConnectExchangeKeyDevice? = null,
    val data: SmartConnectExchangeKeyData
)

@Serializable
data class SmartConnectExchangeKeyData(
    val key: String,
    val device: SmartConnectExchangeKeyDevice
)

@Serializable
data class SmartConnectExchangeKeyDevice(
    val deviceId: String,
    val realDeviceId: String,
    val deviceName: String = "",
    val key: String = "",
    val sn: String = "",
    val cap: Int = 0,
    val os: Int = SmartConnectConstants.PC_OS_WINDOWS,
    val type: Int = SmartConnectConstants.PC_TYPE_LAPTOP,
    val i3: Int = 1,
    val verCode: Long = SmartConnectConstants.PC_VER_CODE.toLong(),
    val verName: String = SmartConnectConstants.PC_VER_NAME
)

/**
 * Intentional user disconnect (`svc 0 cmd 6`, `{"cmd":6,"data":{"type":0}}`) sent before closing
 * the WebSocket with code `3123` (`WS_CLOSE_CODE_USER_DISCONNECT`).
 */
@Serializable
data class SmartConnectDisconnectPayload(
    val ver: Int = 3,
    val cmd: Int = SmartConnectConstants.CMD_USER_DISCONNECT,
    val data: SmartConnectDisconnectData = SmartConnectDisconnectData()
)

@Serializable
data class SmartConnectDisconnectData(
    val type: Int = 0
)

/**
 * `/readyfor/mdns` `cmd 0` minimal discovery trigger (`{"cmd":0}`) received inside a
 * `SealedDiscoveryFrame` from a paired phone.
 */
@Serializable
data class SmartConnectDiscoveryTriggerPayload(
    val cmd: Int = SmartConnectConstants.DISCOVERY_CMD_TRIGGER
)

/**
 * `/readyfor/mdns` `cmd 1` full dial-back invite sent inside a `SealedDiscoveryFrame` (or BLE
 * GATT `0x0722` write) so the paired phone connects back to `wss://<pcIp>:<wsPort>/readyfor/`.
 */
@Serializable
data class SmartConnectDiscoveryInvitePayload(
    val cmd: Int = SmartConnectConstants.DISCOVERY_CMD_INVITE,
    val ip: String,
    val ips: List<String>,
    val policies: List<SmartConnectWifiPolicy> = emptyList(),
    val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val deviceId: String,
    val deviceName: String,
    val fp: String,
    val token: String = "",
    val invitation: String,
    val appVer: String = SmartConnectConstants.PC_VER_NAME,
    val verCode: Long = SmartConnectConstants.PC_VER_CODE.toLong(),
    val type: Int = SmartConnectConstants.PC_TYPE_LAPTOP,
    val os: Int = SmartConnectConstants.PC_OS_WINDOWS,
    val appType: Int = SmartConnectConstants.PC_APP_TYPE_DESKTOP,
    val scType: Int = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
    val cap: Int = SmartConnectConstants.PC_BASE_CAP,
    val timestamp: Long,
    val ver: String = SmartConnectConstants.ENVELOPE_VERSION
)

object SmartConnectHandshakeCodec {

    fun parsePhoneHello(envelope: SmartConnectEnvelope): SmartConnectPhoneHelloPayload {
        require(envelope.serviceId == SmartConnectConstants.SVC_HANDSHAKE) {
            "Expected serviceId=0 for Phone Hello, got ${envelope.serviceId}"
        }
        val dataObj = SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data)
        val cmd = (dataObj["cmd"] as? JsonPrimitive)?.intOrNull ?: SmartConnectConstants.CMD_HELLO_PAIR

        // Support both nested {"cmd":0,"data":{...}} (unit tests) and flat {"ver":3,"cmd":0,"token":...,"device":{...}} (stock APK)
        val nestedData = dataObj["data"] as? JsonObject
        if (nestedData != null && nestedData.containsKey("deviceId")) {
            return SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectPhoneHelloPayload>(dataObj)
        }

        val deviceObj = dataObj["device"] as? JsonObject
        val nonceId = (deviceObj?.get("deviceId") as? JsonPrimitive)?.content
            ?: (dataObj["deviceId"] as? JsonPrimitive)?.content
            ?: envelope.deviceId
        val realId = (deviceObj?.get("realDeviceId") as? JsonPrimitive)?.content.orEmpty()
        val devName = (deviceObj?.get("deviceName") as? JsonPrimitive)?.content
            ?: (dataObj["deviceName"] as? JsonPrimitive)?.content
            ?: "Motorola Phone"
        val ip = (dataObj["ip"] as? JsonPrimitive)?.content.orEmpty()
        val token = (dataObj["token"] as? JsonPrimitive)?.content
            ?: (dataObj["invitation"] as? JsonPrimitive)?.content
        val paired = (dataObj["paired"] as? JsonPrimitive)?.intOrNull ?: 0
        val phoneKey = (deviceObj?.get("key") as? JsonPrimitive)?.content
            ?: (dataObj["key"] as? JsonPrimitive)?.content
        val sn = (deviceObj?.get("sn") as? JsonPrimitive)?.content
            ?: (dataObj["sn"] as? JsonPrimitive)?.content
        val verCode = (deviceObj?.get("verCode") as? JsonPrimitive)?.intOrNull ?: 0
        val verName = (deviceObj?.get("verName") as? JsonPrimitive)?.content.orEmpty()
        val os = (deviceObj?.get("os") as? JsonPrimitive)?.intOrNull ?: 1
        val type = (deviceObj?.get("type") as? JsonPrimitive)?.intOrNull ?: 1

        val ipsList = mutableListOf<String>()
        var extractedFp = (dataObj["fp"] as? JsonPrimitive)?.content.orEmpty()
        var extractedPort = (dataObj["wsPort"] as? JsonPrimitive)?.intOrNull ?: SmartConnectConstants.DEFAULT_WS_PORT
        val ipsArr = dataObj["ips"] as? JsonArray
        if (ipsArr != null) {
            for (el in ipsArr) {
                when (el) {
                    is JsonPrimitive -> if (el.content.isNotBlank()) ipsList.add(el.content)
                    is JsonObject -> {
                        val entryIp = (el["ip"] as? JsonPrimitive)?.content.orEmpty()
                        if (entryIp.isNotBlank()) ipsList.add(entryIp)
                        val entryFp = (el["fp"] as? JsonPrimitive)?.content.orEmpty()
                        if (extractedFp.isBlank() && entryFp.isNotBlank()) extractedFp = entryFp
                        val entryPort = (el["port"] as? JsonPrimitive)?.intOrNull ?: 0
                        if (entryPort in 1..65535) extractedPort = entryPort
                    }
                    else -> Unit
                }
            }
        }
        if (ipsList.isEmpty() && ip.isNotBlank()) {
            ipsList.add(ip)
        }

        val svcVers = mutableMapOf<String, Int>()
        val svObj = (dataObj["serviceVersions"] as? JsonObject)
            ?: (deviceObj?.get("serviceVersions") as? JsonObject)
        svObj?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.intOrNull?.let { svcVers[k] = it }
        }

        return SmartConnectPhoneHelloPayload(
            cmd = cmd,
            data = SmartConnectPhoneHelloData(
                deviceId = nonceId,
                realDeviceId = realId,
                deviceName = devName,
                ip = ip.ifBlank { ipsList.firstOrNull().orEmpty() },
                ips = ipsList,
                wsPort = extractedPort,
                fp = extractedFp,
                invitation = token,
                paired = paired,
                verCode = verCode,
                verName = verName,
                type = type,
                os = os,
                sn = sn,
                key = phoneKey,
                serviceVersions = svcVers
            )
        )
    }

    fun parseExchangeKey(envelope: SmartConnectEnvelope): SmartConnectExchangeKeyPayload {
        require(envelope.serviceId == SmartConnectConstants.SVC_HANDSHAKE) {
            "Expected serviceId=0 for EXCHANGE_KEY, got ${envelope.serviceId}"
        }
        val dataObj = SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data)
        val nestedData = dataObj["data"] as? JsonObject
        if (nestedData != null && nestedData.containsKey("device")) {
            val decoded = SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectExchangeKeyData>(nestedData)
            val effectiveKey = decoded.key.ifBlank { decoded.device.key }
            val normalizedData = decoded.copy(
                key = effectiveKey,
                device = decoded.device.copy(key = effectiveKey)
            )
            return SmartConnectExchangeKeyPayload(
                cmd = SmartConnectConstants.CMD_EXCHANGE_KEY,
                device = normalizedData.device,
                data = normalizedData
            )
        }
        val deviceObj = dataObj["device"] as? JsonObject
            ?: throw IllegalArgumentException("Missing 'device' object in EXCHANGE_KEY frame")
        val dev = SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectExchangeKeyDevice>(deviceObj)
        val topKey = (dataObj["key"] as? JsonPrimitive)?.content.orEmpty()
        val effectiveKey = dev.key.ifBlank { topKey }
        val normalizedDev = dev.copy(key = effectiveKey)
        return SmartConnectExchangeKeyPayload(
            cmd = SmartConnectConstants.CMD_EXCHANGE_KEY,
            device = normalizedDev,
            data = SmartConnectExchangeKeyData(
                key = effectiveKey,
                device = normalizedDev
            )
        )
    }

    fun buildPcVerifyReplyFrame(
        pcNonceIdBase64: String,
        pcRealIdBase64: String = pcNonceIdBase64,
        pcDeviceName: String,
        primaryIp: String,
        allIps: List<String>,
        wsPort: Int,
        serialNumber: String,
        perPairKeyBase64: String,
        msgId: Int,
        serviceVersions: Map<String, Int> = SmartConnectConstants.DEFAULT_SERVICE_VERSIONS
    ): String {
        val safeSn = SmartConnectIdentity.ensureNonEmptySerialNumber(serialNumber)
        val cleanName = pcDeviceName.ifBlank { "EC0-PC" }
        val safeRealId = pcRealIdBase64.ifBlank { pcNonceIdBase64 }
        val payload = SmartConnectPcVerifyReplyPayload(
            ver = 3,
            cmd = SmartConnectConstants.CMD_HELLO_PAIR,
            code = 200,
            token = "OK",
            paired = 1,
            connType = 1,
            device = SmartConnectPcVerifyDevice(
                deviceId = pcNonceIdBase64,
                realDeviceId = safeRealId,
                deviceName = cleanName,
                os = SmartConnectConstants.PC_OS_WINDOWS,
                type = SmartConnectConstants.PC_TYPE_LAPTOP,
                scType = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
                verCode = SmartConnectConstants.PC_VER_CODE.toLong(),
                verName = SmartConnectConstants.PC_VER_NAME,
                appVer = SmartConnectConstants.PC_VER_NAME,
                minVerCode = SmartConnectConstants.MIN_VER_CODE_MAP,
                sn = safeSn,
                skuUpper = "Linux PC",
                sku = "Linux PC",
                cap = 0,
                caps = SmartConnectConstants.DEFAULT_DEVICE_CAPS,
                serviceVersions = serviceVersions,
                key = perPairKeyBase64,
                isPaired = true
            ),
            serviceVersions = serviceVersions,
            data = SmartConnectPcVerifyData(
                deviceId = pcNonceIdBase64,
                realDeviceId = safeRealId,
                deviceName = cleanName,
                ip = primaryIp,
                ips = if (allIps.isEmpty()) listOf(primaryIp) else allIps,
                wsPort = wsPort,
                os = SmartConnectConstants.PC_OS_WINDOWS,
                type = SmartConnectConstants.PC_TYPE_LAPTOP,
                scType = SmartConnectConstants.PC_SC_TYPE_WIN_UNLOCKED,
                verCode = SmartConnectConstants.PC_VER_CODE,
                verName = SmartConnectConstants.PC_VER_NAME,
                sn = safeSn,
                key = perPairKeyBase64,
                serviceVersions = serviceVersions
            )
        )
        return SmartConnectEnvelopeCodec.encodeTypedFrame(
            deviceId = pcNonceIdBase64,
            srcServiceId = SmartConnectConstants.SVC_HANDSHAKE,
            serviceId = SmartConnectConstants.SVC_HANDSHAKE,
            msgId = msgId,
            innerData = payload
        )
    }

    /**
     * Builds the PC -> Phone `svc 0 cmd 1` (`EXCHANGE_KEY`) outer frame.
     *
     * Sets BOTH `device.deviceId` and `device.realDeviceId` to [pcRealIdBase64]
     * per §3-S-A Step 4 & `pairing-and-discovery.md` §Pairing commit.
     */
    fun buildPcExchangeKeyFrame(
        pcNonceIdBase64: String,
        pcRealIdBase64: String,
        pcDeviceName: String,
        perPairKeyBase64: String,
        msgId: Int,
        serialNumber: String = ""
    ): String {
        SmartConnectIdentity.decodeId16(pcRealIdBase64)
        val safeSn = SmartConnectIdentity.ensureNonEmptySerialNumber(serialNumber)
        val cleanName = pcDeviceName.ifBlank { "EC0-PC" }
        val dev = SmartConnectExchangeKeyDevice(
            deviceId = pcRealIdBase64,
            realDeviceId = pcRealIdBase64,
            deviceName = cleanName,
            key = perPairKeyBase64,
            sn = safeSn,
            cap = 0,
            os = SmartConnectConstants.PC_OS_WINDOWS,
            type = SmartConnectConstants.PC_TYPE_LAPTOP,
            i3 = 1,
            verCode = SmartConnectConstants.PC_VER_CODE.toLong(),
            verName = SmartConnectConstants.PC_VER_NAME
        )
        val payload = SmartConnectExchangeKeyPayload(
            ver = 3,
            cmd = SmartConnectConstants.CMD_EXCHANGE_KEY,
            paired = 1,
            device = dev,
            data = SmartConnectExchangeKeyData(
                key = perPairKeyBase64,
                device = dev
            )
        )
        return SmartConnectEnvelopeCodec.encodeTypedFrame(
            deviceId = pcNonceIdBase64,
            srcServiceId = SmartConnectConstants.SVC_HANDSHAKE,
            serviceId = SmartConnectConstants.SVC_HANDSHAKE,
            msgId = msgId,
            innerData = payload
        )
    }

    fun buildUserDisconnectFrame(
        pcNonceIdBase64: String,
        msgId: Int
    ): String {
        val payload = SmartConnectDisconnectPayload(
            ver = 3,
            cmd = SmartConnectConstants.CMD_USER_DISCONNECT,
            data = SmartConnectDisconnectData(type = 0)
        )
        return SmartConnectEnvelopeCodec.encodeTypedFrame(
            deviceId = pcNonceIdBase64,
            srcServiceId = SmartConnectConstants.SVC_HANDSHAKE,
            serviceId = SmartConnectConstants.SVC_HANDSHAKE,
            msgId = msgId,
            innerData = payload
        )
    }

    fun encodeDiscoveryInviteJson(invite: SmartConnectDiscoveryInvitePayload): String {
        return SmartConnectEnvelopeCodec.json.encodeToString(invite)
    }

    fun decodeDiscoveryJson(rawPlaintextJson: String): JsonElement {
        return SmartConnectEnvelopeCodec.json.parseToJsonElement(rawPlaintextJson.trim())
    }
}
