package ec0.core.engine.smartconnect

import ec0.core.engine.smartconnect.protocol.SmartConnectDiscoveryInvitePayload
import ec0.core.engine.smartconnect.protocol.SmartConnectHandshakeCodec
import ec0.core.engine.transport.PinnedTlsWebSocketListener
import ec0.core.engine.transport.PinnedTlsWebSocketSession
import ec0.core.engine.transport.PinnedTlsWebSocketTransport
import ec0.core.engine.transport.SealedDiscoveryFrameCodec
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Smart Connect `/readyfor/mdns` sealed discovery handler, `ECONNREFUSED` TCP RST presence probe,
 * and BLE `0x5246` (`"RF"`) 20-byte `ServiceData` wake parser + GATT `0x0722` sealed-invite builder.
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §1.3 & §3-S-A.
 */
object SmartConnectDiscoveryAndBle {

    /** Full 128-bit Bluetooth Base UUID for `0x5246` (`"RF"`). */
    const val BLE_SERVICE_UUID_5246_FULL: String = "00005246-0000-1000-8000-00805f9b34fb"

    /** Full 128-bit Bluetooth Base UUID for GATT Write Characteristic `0x0722`. */
    const val BLE_GATT_CHAR_UUID_0722_FULL: String = "00000722-0000-1000-8000-00805f9b34fb"

    data class BleWakeAdvertisement(
        val flags: Int,
        val phoneNonceIdRaw16: ByteArray,
        val deviceType: Int,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val matchedPeerRealDeviceId: String? = null
    )

    enum class TcpRstPresenceResult {
        /** Port accepted TCP connection (Ready For server actively listening). */
        ONLINE_LISTENING,
        /** Kernel replied with TCP RST (`ECONNREFUSED`) within threshold — phone is awake on LAN. */
        ONLINE_IDLE_TCP_RST,
        /** Timed out or host unreachable — phone is offline or out of Wi-Fi range. */
        UNREACHABLE
    }

    /**
     * Parses a 20-byte BLE `ServiceData[0x5246]` payload (`docs/engine/smart-connect-design.md` §3-S-A).
     *
     * Wire layout (`20` bytes):
     * - `byte[0]`: `0x01` (protocol version / marker)
     * - `bytes[1..17)`: `phoneNonceIdRaw16` (16-byte salted-hash `nonceId`)
     * - `byte[17]`: status flags
     * - `byte[18]`: `deviceType` (`1` = phone)
     * - `byte[19]`: `batteryAndCharging` (`bit 7` = charging flag, `bits 0..6` = battery `0..100`)
     *
     * Note: BlueZ scanners MUST filter on `ServiceData` key `0x5246` rather than `ServiceUUIDs`,
     * because the phone omits `0x5246` from its `ServiceUUIDs` advertisement list.
     */
    fun <T> parseBleWakeServiceData(
        serviceDataBytes: ByteArray,
        pairedPeers: Iterable<T>,
        realDeviceIdSelector: (T) -> String
    ): BleWakeAdvertisement? {
        if (serviceDataBytes.size != SmartConnectConstants.BLE_WAKE_SERVICE_DATA_BYTES) {
            return null
        }
        if (serviceDataBytes[0] != 0x01.toByte()) {
            return null
        }
        val phoneNonceIdRaw16 = serviceDataBytes.copyOfRange(1, 17)
        val flags = serviceDataBytes[17].toInt() and 0xff
        val deviceType = serviceDataBytes[18].toInt() and 0xff
        val battByte = serviceDataBytes[19].toInt() and 0xff
        val isCharging = (battByte and 0x80) != 0
        val batteryPercent = (battByte and 0x7f).coerceIn(0, 100)

        val matchedPeer = SmartConnectIdentity.matchNonceIdToPairedPeer(
            candidateNonceIdRaw16 = phoneNonceIdRaw16,
            pairedPeers = pairedPeers,
            realDeviceIdSelector = realDeviceIdSelector
        )

        return BleWakeAdvertisement(
            flags = flags,
            phoneNonceIdRaw16 = phoneNonceIdRaw16,
            deviceType = deviceType,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            matchedPeerRealDeviceId = matchedPeer?.let(realDeviceIdSelector)
        )
    }

    /**
     * Builds the binary `SealedDiscoveryFrame` (`frameType = 0x01`) to write to BLE GATT
     * characteristic `0x0722` (`WriteValue(..., {"type": "command"})` — Write-Without-Response)
     * or `/readyfor/mdns` WebSocket binary frame.
     */
    fun buildSealedInviteFrame(
        pcNonceIdRaw16: ByteArray,
        perPairKey32: ByteArray,
        invite: SmartConnectDiscoveryInvitePayload,
        iv12: ByteArray? = null
    ): ByteArray {
        val inviteJson = SmartConnectHandshakeCodec.encodeDiscoveryInviteJson(invite)
        return SealedDiscoveryFrameCodec.seal(
            senderNonceIdRaw16 = pcNonceIdRaw16,
            perPairKey32 = perPairKey32,
            plaintextUtf8 = inviteJson,
            iv12 = iv12
        )
    }

    /**
     * Sends an outbound sealed `cmd=1` discovery invite to `wss://[phoneHost]:[phonePort]/readyfor/mdns`
     * pinned to [expectedPhoneFingerprintHex].
     */
    fun sendOutboundSealedDiscoveryInvite(
        transport: PinnedTlsWebSocketTransport,
        phoneHost: String,
        phonePort: Int = SmartConnectConstants.DEFAULT_WS_PORT,
        expectedPhoneFingerprintHex: String?,
        pcNonceIdRaw16: ByteArray,
        perPairKey32: ByteArray,
        invite: SmartConnectDiscoveryInvitePayload,
        timeoutMs: Int = 5_000
    ): Boolean {
        val sealedFrame = buildSealedInviteFrame(
            pcNonceIdRaw16 = pcNonceIdRaw16,
            perPairKey32 = perPairKey32,
            invite = invite
        )
        val connectedLatch = CountDownLatch(1)
        val session = runCatching {
            transport.connectClient(
                host = phoneHost,
                port = phonePort,
                path = SmartConnectConstants.WS_DISCOVERY_PATH,
                expectedServerFingerprintHex = expectedPhoneFingerprintHex,
                allowUnpinnedCapture = expectedPhoneFingerprintHex.isNullOrBlank(),
                connectTimeoutMs = timeoutMs,
                listener = object : PinnedTlsWebSocketListener {
                    override fun onConnected(session: PinnedTlsWebSocketSession) {
                        connectedLatch.countDown()
                    }
                }
            )
        }.getOrNull() ?: return false

        return try {
            connectedLatch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            session.sendBinary(sealedFrame)
            session.sendClose()
            true
        } catch (_: Throwable) {
            false
        } finally {
            session.close()
        }
    }

    /**
     * Probes a paired phone's background LAN presence via TCP `connect()` to port `9836`
     * (`docs/engine/smart-connect-design.md` §3-S-A).
     *
     * When the phone is idle on Wi-Fi with Smart Connect's server stopped, the Linux kernel on
     * the phone replies with a TCP `RST` (`ConnectException: Connection refused`) in `~35 ms`.
     * When the phone is offline or out of range, `connect()` times out (`SocketTimeoutException`).
     */
    fun probePairedPeerPresenceViaTcpRst(
        host: String,
        port: Int = SmartConnectConstants.DEFAULT_WS_PORT,
        timeoutMs: Int = 500
    ): TcpRstPresenceResult {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            TcpRstPresenceResult.ONLINE_LISTENING
        } catch (_: ConnectException) {
            TcpRstPresenceResult.ONLINE_IDLE_TCP_RST
        } catch (_: SocketTimeoutException) {
            TcpRstPresenceResult.UNREACHABLE
        } catch (_: Throwable) {
            TcpRstPresenceResult.UNREACHABLE
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Parses the decrypted JSON payload inside a `SealedDiscoveryFrame` on `/readyfor/mdns`.
     */
    fun parseDecryptedDiscoveryJson(plaintextUtf8: String): JsonObject? {
        return runCatching {
            SmartConnectHandshakeCodec.decodeDiscoveryJson(plaintextUtf8).jsonObject
        }.getOrNull()
    }

    fun extractDiscoveryCmd(obj: JsonObject): Int? {
        return (obj["cmd"] as? JsonPrimitive)?.content?.toIntOrNull()
    }
}
