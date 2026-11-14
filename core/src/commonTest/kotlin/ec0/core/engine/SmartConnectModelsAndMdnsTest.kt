package ec0.core.engine

import ec0.core.engine.smartconnect.SmartConnectConstants
import ec0.core.engine.smartconnect.SmartConnectEnvelopeCodec
import ec0.core.engine.smartconnect.SmartConnectIdentity
import ec0.core.engine.smartconnect.SmartConnectInviteTokenStore
import ec0.core.engine.smartconnect.SmartConnectRedactor
import ec0.core.engine.smartconnect.protocol.SmartConnectCapRegistry
import ec0.core.engine.smartconnect.protocol.SmartConnectHandshakeCodec
import ec0.core.engine.smartconnect.protocol.SmartConnectStatusCodec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalEncodingApi::class)
class SmartConnectModelsAndMdnsTest {

    @Test
    fun saltedHashNonceIdMatchesDesignDocGoldenVector() {
        val realDeviceIdRaw16 = ByteArray(16) { it.toByte() } // 000102030405060708090a0b0c0d0e0f
        val salt8 = ByteArray(8) { (0x10 + it).toByte() } // 1011121314151617

        val nonceRaw16 = SmartConnectIdentity.generateNonceIdRaw(realDeviceIdRaw16, salt8)
        val nonceB64 = Base64.Default.encode(nonceRaw16)
        assertEquals("EBESExQVFhcdZK3SpjiDZw==", nonceB64)

        val realIdB64 = Base64.Default.encode(realDeviceIdRaw16)
        val peers = listOf("peer-other" to SmartConnectIdentity.generateRealDeviceIdBase64(), "peer-a5x" to realIdB64)
        val matched = SmartConnectIdentity.matchNonceIdToPairedPeer(
            candidateNonceIdRaw16 = nonceRaw16,
            pairedPeers = peers,
            realDeviceIdSelector = { it.second }
        )
        assertNotNull(matched)
        assertEquals("peer-a5x", matched.first)

        val tampered = nonceRaw16.copyOf().also { it[15] = (it[15].toInt() xor 0x01).toByte() }
        val unmatched = SmartConnectIdentity.matchNonceIdToPairedPeer(
            candidateNonceIdRaw16 = tampered,
            pairedPeers = peers,
            realDeviceIdSelector = { it.second }
        )
        assertNull(unmatched)
    }

    @Test
    fun serialNumberNonEmptyGuardAndMultiUseInviteTokenTtl() {
        val generatedSn = SmartConnectIdentity.ensureNonEmptySerialNumber("")
        assertTrue(generatedSn.isNotBlank())
        assertEquals(36, generatedSn.length)
        assertEquals("custom-sn-123", SmartConnectIdentity.ensureNonEmptySerialNumber("  custom-sn-123  "))

        val store = SmartConnectInviteTokenStore(ttlMs = 180_000L)
        val token = store.issueToken(nowMs = 1_000L)
        assertEquals(44, token.length)
        // Must NOT be single-use within the 180s TTL window (phone connects twice on first pair)
        assertTrue(store.isValid(token, nowMs = 1_500L))
        assertTrue(store.isValid(token, nowMs = 50_000L))
        assertTrue(store.isValid(token, nowMs = 181_000L))
        assertFalse(store.isValid(token, nowMs = 181_001L))
    }

    @Test
    fun qrPayloadAndMatrixRoundtripMatchesMotorolaRdpConnectionScheme() {
        val realId = SmartConnectIdentity.generateRealDeviceIdBase64()
        val nonceId = SmartConnectIdentity.generateNonceIdBase64(realId)
        val fp = "a".repeat(64)
        val token = SmartConnectIdentity.generateInviteToken()
        val qrString = SmartConnectIdentity.buildQrPayloadString(
            ips = listOf("192.168.43.100"),
            wsPort = 9836,
            nonceIdBase64 = nonceId,
            deviceName = "Aryan-Arch",
            certFingerprintHex = fp,
            invitation = token,
            timestampMs = 1710000000000L
        )
        assertTrue(qrString.startsWith("motorolardpconnection{"))
        val parsed = SmartConnectIdentity.parseQrPayloadString(qrString)
        assertEquals("3.0.0", parsed.version)
        assertEquals(listOf("192.168.43.100"), parsed.wsData.ips)
        assertEquals(9836, parsed.wsData.wsPort)
        assertEquals(nonceId, parsed.wsData.deviceId)
        assertEquals(6, parsed.wsData.os)
        assertEquals(4, parsed.wsData.type)
        assertEquals(2, parsed.wsData.scType)
        assertEquals(fp, parsed.wsData.fp)
        assertEquals(token, parsed.wsData.invitation)

        val matrix = SmartConnectIdentity.buildQrMatrix(
            ips = listOf("192.168.43.100"),
            wsPort = 9836,
            nonceIdBase64 = nonceId,
            deviceName = "Aryan-Arch",
            certFingerprintHex = fp,
            invitation = token,
            timestampMs = 1710000000000L
        )
        assertTrue(matrix.size >= 21)
    }

    @Test
    fun handshakeVerifyReplyAndExchangeKeyBothIdsQuirk() {
        val pcRealId = SmartConnectIdentity.generateRealDeviceIdBase64()
        val pcNonceId = SmartConnectIdentity.generateNonceIdBase64(pcRealId)
        val perPairKey = SmartConnectIdentity.generatePerPairKeyBase64()

        val verifyFrame = SmartConnectHandshakeCodec.buildPcVerifyReplyFrame(
            pcNonceIdBase64 = pcNonceId,
            pcDeviceName = "EC0-Desktop",
            primaryIp = "192.168.1.20",
            allIps = listOf("192.168.1.20"),
            wsPort = 9836,
            serialNumber = "",
            perPairKeyBase64 = perPairKey,
            msgId = 7
        )
        // Must NEVER include rfaServiceVersions
        assertFalse(verifyFrame.contains("rfaServiceVersions"))
        val verifyEnv = SmartConnectEnvelopeCodec.decodeEnvelope(verifyFrame)
        assertEquals(0, verifyEnv.serviceId)
        val verifyDataObj = SmartConnectEnvelopeCodec.unwrapDataObject(verifyEnv.data)
            .getValue("data").jsonObject
        assertEquals("6", verifyDataObj.getValue("os").jsonPrimitive.content)
        assertEquals("4", verifyDataObj.getValue("type").jsonPrimitive.content)
        assertEquals("2", verifyDataObj.getValue("scType").jsonPrimitive.content)
        assertTrue(verifyDataObj.getValue("sn").jsonPrimitive.content.isNotBlank())

        // Step 4 EXCHANGE_KEY quirk: BOTH device.deviceId and device.realDeviceId == pcRealId
        val exchFrame = SmartConnectHandshakeCodec.buildPcExchangeKeyFrame(
            pcNonceIdBase64 = pcNonceId,
            pcRealIdBase64 = pcRealId,
            pcDeviceName = "EC0-Desktop",
            perPairKeyBase64 = perPairKey,
            msgId = 8
        )
        val exchEnv = SmartConnectEnvelopeCodec.decodeEnvelope(exchFrame)
        val parsedExch = SmartConnectHandshakeCodec.parseExchangeKey(exchEnv)
        assertEquals(pcRealId, parsedExch.data.device.deviceId)
        assertEquals(pcRealId, parsedExch.data.device.realDeviceId)
    }

    @Test
    fun capRegistryReplaceWholeAndSliceSaEmptyDefault() {
        assertTrue(SmartConnectConstants.DEFAULT_SA_TILE_CAP_MAP.isEmpty())
        val registry = SmartConnectCapRegistry()
        assertTrue(registry.snapshot().isEmpty())

        val capAfterClipboard = registry.registerCapability("clipboard", 1)
        assertEquals(mapOf("clipboard" to 1), capAfterClipboard)

        val capAfterLock = registry.registerCapability("Lock", 1)
        assertEquals(mapOf("clipboard" to 1, "Lock" to 1), capAfterLock)

        val statusFrame = SmartConnectStatusCodec.buildStatusAndCapFrame(
            pcNonceIdBase64 = SmartConnectIdentity.generateRealDeviceIdBase64(),
            msgId = 12,
            capMap = registry.snapshot()
        )
        val parsed = SmartConnectStatusCodec.parseStatusAndCap(
            SmartConnectEnvelopeCodec.decodeEnvelope(statusFrame)
        )
        assertEquals(mapOf("clipboard" to 1, "Lock" to 1), parsed.data.cap)
    }

    @Test
    fun readyForMdnsAnnounceAndExtractRoundtrip() {
        val realId = SmartConnectIdentity.generateRealDeviceIdBase64()
        val nonceId = SmartConnectIdentity.generateNonceIdBase64(realId)
        val fp = "0123456789abcdef".repeat(4)

        val packetBytes = MdnsCodec.encodeReadyForAnnouncement(
            nonceIdBase64 = nonceId,
            deviceName = "Moto-A5x",
            port = 9836,
            addresses = listOf("192.168.43.1"),
            certFingerprintHex = fp,
            timestampMs = 1710000005000L,
            type = 1,
            os = 1,
            appType = 1,
            cap = 0,
            ttlSeconds = 120L
        )

        val decoded = MdnsCodec.decodePacket(packetBytes)
        val peers = MdnsCodec.extractReadyForPeers(decoded, nowMs = 1710000005000L)
        assertEquals(1, peers.size)
        val peer = peers.single()
        assertEquals(nonceId, peer.deviceId)
        assertEquals("Moto-A5x", peer.deviceName)
        assertEquals(9836, peer.port)
        assertEquals(listOf("192.168.43.1"), peer.addresses)
        assertEquals(fp, peer.fp)

        // Redactor test
        val sensitiveJson = """{"cmd":0,"data":{"key":"secretKey123","invitation":"inv456","wallpaper":"AAAA"}}"""
        val redacted = SmartConnectRedactor.redactJsonString(sensitiveJson)
        assertFalse(redacted.contains("secretKey123"))
        assertFalse(redacted.contains("inv456"))
        assertTrue(redacted.contains("[REDACTED]"))
    }
}
