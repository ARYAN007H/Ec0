package ec0.core.engine.smartconnect.protocol

import ec0.core.engine.smartconnect.SmartConnectConstants
import ec0.core.engine.smartconnect.SmartConnectEnvelope
import ec0.core.engine.smartconnect.SmartConnectEnvelopeCodec
import ec0.core.engine.smartconnect.SmartConnectIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Wire models and builders for Smart Connect Service `0` (`svc 0`) 6-step handshake,
 * `EXCHANGE_KEY` (`cmd 1`), user disconnect (`cmd 6`), and `/readyfor/mdns` discovery invites.
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §3-S-A.
 */
@Serializable
data class SmartConnectPhoneHelloPayload(
    val cmd: Int = SmartConnectConstants.CMD_HELLO_PAIR,
    val data: SmartConnectPhoneHelloData
)

@Serializable
data class SmartConnectPhoneHelloData(
    val deviceId: String,
    val deviceName: String = "Motorola Phone",
    val ip: String = "",
    val ips: List<String> = emptyList(),
    val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val fp: String = "",
    val invitation: String? = null,
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
 * CRITICAL INVARIANTS (§3-S-A Step 2):
 * 1. `os = 6`, `type = 4`, `scType = 2`, `verCode = 903013000`, `verName = "09.0.3.013.000"`
 *    (`WIN_UNLOCKED` profile so the stock phone APK unlocks PC feature tiles).
 * 2. `sn` MUST be non-empty (`TextUtils.isEmpty(sn)` in `c0.o.w` aborts verification).
 * 3. `rfaServiceVersions` MUST NOT be present in the serialized JSON (`c0.o.w` crashes with a
 *    Gson cast exception if `rfaServiceVersions` is included).
 */
@Serializable
data class SmartConnectPcVerifyReplyPayload(
    val cmd: Int = SmartConnectConstants.CMD_HELLO_PAIR,
    val code: Int = 200,
    val data: SmartConnectPcVerifyData
)

@Serializable
data class SmartConnectPcVerifyData(
    val deviceId: String,
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
 * CRITICAL WIRE QUIRK (§3-S-A Step 4):
 * On PC -> Phone `EXCHANGE_KEY`, **both** `device.deviceId` AND `device.realDeviceId` MUST be set
 * to `<pcRealId>` (NOT `nonceId`). The phone keys its persistent paired-device database on the ID
 * learned during `EXCHANGE_KEY`.
 */
@Serializable
data class SmartConnectExchangeKeyPayload(
    val cmd: Int = SmartConnectConstants.CMD_EXCHANGE_KEY,
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
    val deviceName: String = ""
)

/**
 * Intentional user disconnect (`svc 0 cmd 6`, `{"cmd":6,"data":{"type":0}}`) sent before closing
 * the WebSocket with code `3123` (`WS_CLOSE_CODE_USER_DISCONNECT`).
 */
@Serializable
data class SmartConnectDisconnectPayload(
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
    val wsPort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
    val deviceId: String,
    val deviceName: String,
    val fp: String,
    val invitation: String,
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
        return SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectPhoneHelloPayload>(dataObj)
    }

    fun parseExchangeKey(envelope: SmartConnectEnvelope): SmartConnectExchangeKeyPayload {
        require(envelope.serviceId == SmartConnectConstants.SVC_HANDSHAKE) {
            "Expected serviceId=0 for EXCHANGE_KEY, got ${envelope.serviceId}"
        }
        val dataObj = SmartConnectEnvelopeCodec.unwrapDataObject(envelope.data)
        return SmartConnectEnvelopeCodec.json.decodeFromJsonElement<SmartConnectExchangeKeyPayload>(dataObj)
    }

    fun buildPcVerifyReplyFrame(
        pcNonceIdBase64: String,
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
        val payload = SmartConnectPcVerifyReplyPayload(
            cmd = SmartConnectConstants.CMD_HELLO_PAIR,
            code = 200,
            data = SmartConnectPcVerifyData(
                deviceId = pcNonceIdBase64,
                deviceName = pcDeviceName.ifBlank { "EC0-PC" },
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
     * Sets BOTH `device.deviceId` and `device.realDeviceId` inside `data.device` to [pcRealIdBase64]
     * per §3-S-A Step 4.
     */
    fun buildPcExchangeKeyFrame(
        pcNonceIdBase64: String,
        pcRealIdBase64: String,
        pcDeviceName: String,
        perPairKeyBase64: String,
        msgId: Int
    ): String {
        SmartConnectIdentity.decodeId16(pcRealIdBase64)
        val payload = SmartConnectExchangeKeyPayload(
            cmd = SmartConnectConstants.CMD_EXCHANGE_KEY,
            data = SmartConnectExchangeKeyData(
                key = perPairKeyBase64,
                device = SmartConnectExchangeKeyDevice(
                    deviceId = pcRealIdBase64,
                    realDeviceId = pcRealIdBase64,
                    deviceName = pcDeviceName.ifBlank { "EC0-PC" }
                )
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
