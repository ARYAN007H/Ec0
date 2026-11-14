package ec0.core.engine.smartconnect

import ec0.core.engine.smartconnect.protocol.SmartConnectExchangeKeyData
import ec0.core.engine.smartconnect.protocol.SmartConnectExchangeKeyDevice
import ec0.core.engine.smartconnect.protocol.SmartConnectExchangeKeyPayload
import ec0.core.engine.smartconnect.protocol.SmartConnectHandshakeCodec
import ec0.core.engine.smartconnect.protocol.SmartConnectPhoneHelloData
import ec0.core.engine.smartconnect.protocol.SmartConnectPhoneHelloPayload
import ec0.core.engine.smartconnect.protocol.SmartConnectStatusAndCapData
import ec0.core.engine.smartconnect.protocol.SmartConnectStatusAndCapPayload
import ec0.core.engine.smartconnect.protocol.SmartConnectStatusCodec
import ec0.core.engine.smartconnect.protocol.SmartConnectWallpaperData
import ec0.core.engine.smartconnect.protocol.SmartConnectWallpaperPayload
import ec0.core.engine.transport.LeafDerPinningTrustManager
import ec0.core.engine.transport.PinnedTlsWebSocketListener
import ec0.core.engine.transport.PinnedTlsWebSocketSession
import ec0.core.engine.transport.PinnedTlsWebSocketTransport
import ec0.core.engine.transport.SealedDiscoveryFrameCodec
import ec0.core.engine.transport.SmartConnectTlsIdentity
import java.nio.file.Files
import java.security.cert.CertificateException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SmartConnectSliceATest {

    @Test
    fun sealedDiscoveryFrameMatchesDesignDocGoldenVectorAndBlocksUnpairHazards() {
        val perPairKey = ByteArray(32) { it.toByte() } // 0001..1f
        val senderNonceId = ByteArray(16) { (0xa0 + it).toByte() } // a0a1..af
        val iv12 = ByteArray(12) { it.toByte() } // 0001..0b
        val plaintext = """{"cmd":1}"""

        val frame = SealedDiscoveryFrameCodec.seal(
            senderNonceIdRaw16 = senderNonceId,
            perPairKey32 = perPairKey,
            plaintextUtf8 = plaintext,
            iv12 = iv12
        )
        assertEquals(54, frame.size)
        assertEquals(SmartConnectConstants.SEALED_FRAME_TYPE_ENCRYPTED, frame[16])

        val ctAndTagHex = frame.copyOfRange(29, 54).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        assertEquals(
            "3c20b576a1c7f82af0f7245b9e966e997f676a43dd38cacf53",
            ctAndTagHex
        )

        val opened = SealedDiscoveryFrameCodec.openWithKey(frame, perPairKey)
        assertEquals(plaintext, opened.plaintextUtf8)
        assertEquals(0x01.toByte(), opened.frameType)

        // Verify unpair hazard guard blocks frameType 0x02 (PLAINTEXT) and 0x03 (CANNOT_DECRYPT)
        val hazard02 = frame.copyOf().also { it[16] = SmartConnectConstants.SEALED_FRAME_TYPE_PLAINTEXT_HAZARD }
        assertFailsWith<IllegalArgumentException> {
            SealedDiscoveryFrameCodec.assertOutgoingFrameSafe(hazard02)
        }
        val hazard03 = frame.copyOf().also { it[16] = SmartConnectConstants.SEALED_FRAME_TYPE_CANNOT_DECRYPT_HAZARD }
        assertFailsWith<IllegalArgumentException> {
            SealedDiscoveryFrameCodec.assertOutgoingFrameSafe(hazard03)
        }
    }

    @Test
    fun tlsLeafDerPinningTrustManagerAcceptsPinnedCertAndRejectsOtherCert() {
        val idA = SmartConnectTlsIdentity.generateSelfSignedRsa2048("PC-A")
        val idB = SmartConnectTlsIdentity.generateSelfSignedRsa2048("PC-B")

        val tm = LeafDerPinningTrustManager(idA.fingerprintHex)
        tm.checkServerTrusted(arrayOf(idA.certificate), "RSA")
        assertEquals(idA.fingerprintHex, tm.lastObservedPeerFingerprintHex)

        assertFailsWith<CertificateException> {
            tm.checkServerTrusted(arrayOf(idB.certificate), "RSA")
        }
    }

    @Test
    fun bleWake20ByteServiceDataParserAndTcpRstPresenceProbe() {
        val phoneRealIdRaw16 = ByteArray(16) { (0x20 + it).toByte() }
        val phoneRealIdB64 = Base64.getEncoder().encodeToString(phoneRealIdRaw16)
        val phoneNonceRaw16 = SmartConnectIdentity.generateNonceIdRaw(phoneRealIdRaw16)

        val serviceData20 = ByteArray(20)
        serviceData20[0] = 0x01
        phoneNonceRaw16.copyInto(serviceData20, destinationOffset = 1)
        serviceData20[17] = 0x03 // flags
        serviceData20[18] = 0x01 // deviceType = phone
        serviceData20[19] = (0x80 or 84).toByte() // charging = true, battery = 84%

        val parsed = SmartConnectDiscoveryAndBle.parseBleWakeServiceData(
            serviceDataBytes = serviceData20,
            pairedPeers = listOf(phoneRealIdB64),
            realDeviceIdSelector = { it }
        )
        assertNotNull(parsed)
        assertEquals(phoneRealIdB64, parsed.matchedPeerRealDeviceId)
        assertTrue(parsed.isCharging)
        assertEquals(84, parsed.batteryPercent)

        // Probe a closed loopback port -> Linux kernel immediately sends TCP RST (ECONNREFUSED)
        val probe = SmartConnectDiscoveryAndBle.probePairedPeerPresenceViaTcpRst(
            host = "127.0.0.1",
            port = 49199,
            timeoutMs = 500
        )
        assertEquals(SmartConnectDiscoveryAndBle.TcpRstPresenceResult.ONLINE_IDLE_TCP_RST, probe)
    }

    @Test
    fun fullSixStepHandshakeAndCapMapAndDiscoveryReconnectOverLoopbackTls() {
        val tempDir = Files.createTempDirectory("ec0-sc-slice-a-test").toFile()
        val refreshLatch = CountDownLatch(1)
        val coordinator = SmartConnectNodeCoordinator(
            storageDir = tempDir,
            initialDeviceName = "EC0-Test-PC",
            wsPort = 0, // Ephemeral port
            localAddressesProvider = { listOf("127.0.0.1") },
            onMdnsRefreshRequested = { refreshLatch.countDown() },
            unpromptedExchangeKeyDelayMs = 1_500L,
            postPairRefreshDelayMs = 200L
        )
        try {
            val port = coordinator.start()
            assertTrue(port > 0)

            val qrPayload = coordinator.buildPairingQrPayload()
            val parsedQr = SmartConnectIdentity.parseQrPayloadString(qrPayload)
            assertEquals(coordinator.certFingerprintHex, parsedQr.wsData.fp)

            val phoneTls = SmartConnectTlsIdentity.generateSelfSignedRsa2048("Moto-A5x")
            val phoneTransport = PinnedTlsWebSocketTransport(phoneTls)
            val phoneRealIdB64 = SmartConnectIdentity.generateRealDeviceIdBase64()
            val phoneNonceIdB64 = SmartConnectIdentity.generateNonceIdBase64(phoneRealIdB64)
            val phoneKeyB64 = SmartConnectIdentity.generatePerPairKeyBase64()

            val receivedByPhone = CopyOnWriteArrayList<SmartConnectEnvelope>()
            val verifyAndCapLatch = CountDownLatch(2)
            val exchangeKeyLatch = CountDownLatch(1)

            val controlSession = phoneTransport.connectClient(
                host = "127.0.0.1",
                port = port,
                path = SmartConnectConstants.WS_CONTROL_PATH,
                expectedServerFingerprintHex = parsedQr.wsData.fp,
                listener = object : PinnedTlsWebSocketListener {
                    override fun onTextMessage(session: PinnedTlsWebSocketSession, text: String) {
                        val env = SmartConnectEnvelopeCodec.decodeEnvelope(text)
                        receivedByPhone.add(env)
                        if (env.serviceId == SmartConnectConstants.SVC_HANDSHAKE) {
                            val cmd = SmartConnectEnvelopeCodec.extractCmd(env)
                            if (cmd == SmartConnectConstants.CMD_HELLO_PAIR) {
                                verifyAndCapLatch.countDown()
                            } else if (cmd == SmartConnectConstants.CMD_EXCHANGE_KEY) {
                                exchangeKeyLatch.countDown()
                            }
                        } else if (env.serviceId == SmartConnectConstants.SVC_DEVICE_STATUS) {
                            verifyAndCapLatch.countDown()
                        }
                    }
                }
            )

            // Step 1: Phone sends svc 0 cmd 0 Hello with QR invitation token
            val helloFrame = SmartConnectEnvelopeCodec.encodeTypedFrame(
                deviceId = phoneNonceIdB64,
                srcServiceId = SmartConnectConstants.SVC_HANDSHAKE,
                serviceId = SmartConnectConstants.SVC_HANDSHAKE,
                msgId = 1,
                innerData = SmartConnectPhoneHelloPayload(
                    cmd = SmartConnectConstants.CMD_HELLO_PAIR,
                    data = SmartConnectPhoneHelloData(
                        deviceId = phoneNonceIdB64,
                        deviceName = "aryan-moto-a5x",
                        ip = "127.0.0.1",
                        wsPort = 9836,
                        fp = phoneTls.fingerprintHex,
                        invitation = parsedQr.wsData.invitation,
                        verCode = 904005000,
                        verName = "09.0.4.005.0"
                    )
                )
            )
            controlSession.sendText(helloFrame)

            assertTrue(
                verifyAndCapLatch.await(5, TimeUnit.SECONDS),
                "Timed out waiting for Step 2 Verify Reply + Step 3 svc 25 cmd 1 cap map"
            )

            val verifyReplyEnv = receivedByPhone.first {
                it.serviceId == SmartConnectConstants.SVC_HANDSHAKE &&
                    SmartConnectEnvelopeCodec.extractCmd(it) == SmartConnectConstants.CMD_HELLO_PAIR
            }
            val capReplyEnv = receivedByPhone.first {
                it.serviceId == SmartConnectConstants.SVC_DEVICE_STATUS &&
                    SmartConnectEnvelopeCodec.extractCmd(it) == SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY
            }
            val parsedCap = SmartConnectStatusCodec.parseStatusAndCap(capReplyEnv)
            // Slice S-A success criterion: empty cap map so phone device page renders with 0 feature tiles
            assertTrue(parsedCap.data.cap.isEmpty())

            // Step 4: Phone sends svc 0 cmd 1 EXCHANGE_KEY with its persistent realDeviceId
            val phoneExchangeKeyFrame = SmartConnectEnvelopeCodec.encodeTypedFrame(
                deviceId = phoneNonceIdB64,
                srcServiceId = SmartConnectConstants.SVC_HANDSHAKE,
                serviceId = SmartConnectConstants.SVC_HANDSHAKE,
                msgId = 2,
                innerData = SmartConnectExchangeKeyPayload(
                    cmd = SmartConnectConstants.CMD_EXCHANGE_KEY,
                    data = SmartConnectExchangeKeyData(
                        key = phoneKeyB64,
                        device = SmartConnectExchangeKeyDevice(
                            deviceId = phoneNonceIdB64,
                            realDeviceId = phoneRealIdB64,
                            deviceName = "aryan-moto-a5x"
                        )
                    )
                )
            )
            controlSession.sendText(phoneExchangeKeyFrame)

            // Also send phone status (svc 25 cmd 1), wallpaper (svc 25 cmd 2), and connect-burst (svc 1 cmd 31)
            val phoneStatusFrame = SmartConnectEnvelopeCodec.encodeTypedFrame(
                deviceId = phoneNonceIdB64,
                srcServiceId = SmartConnectConstants.SVC_DEVICE_STATUS,
                serviceId = SmartConnectConstants.SVC_DEVICE_STATUS,
                msgId = 3,
                innerData = SmartConnectStatusAndCapPayload(
                    cmd = SmartConnectConstants.STATUS_CMD_CAP_AND_BATTERY,
                    data = SmartConnectStatusAndCapData(
                        battery = 87,
                        charge = 1,
                        volume = 65,
                        mute = false,
                        dnd = 1,
                        camera = 1,
                        cap = mapOf("mirror" to 1, "appStream" to 5)
                    )
                )
            )
            controlSession.sendText(phoneStatusFrame)

            val fakeJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            val phoneWallpaperFrame = SmartConnectEnvelopeCodec.encodeTypedFrame(
                deviceId = phoneNonceIdB64,
                srcServiceId = SmartConnectConstants.SVC_DEVICE_STATUS,
                serviceId = SmartConnectConstants.SVC_DEVICE_STATUS,
                msgId = 4,
                innerData = SmartConnectWallpaperPayload(
                    cmd = SmartConnectConstants.STATUS_CMD_WALLPAPER,
                    data = SmartConnectWallpaperData(
                        wallpaper = Base64.getEncoder().encodeToString(fakeJpegBytes),
                        md5 = "d41d8cd98f00b204e9800998ecf8427e"
                    )
                )
            )
            controlSession.sendText(phoneWallpaperFrame)

            val burstFrame = SmartConnectEnvelopeCodec.encodeEnvelope(
                SmartConnectEnvelope(
                    deviceId = phoneNonceIdB64,
                    srcServiceId = SmartConnectConstants.SVC_MIRROR,
                    serviceId = SmartConnectConstants.SVC_MIRROR,
                    msgId = 5,
                    data = buildJsonObject {
                        put("cmd", SmartConnectConstants.MIRROR_CMD_DISPLAY_METRICS)
                        put(
                            "data",
                            buildJsonObject {
                                put("width", 1080)
                                put("height", 2400)
                                put("dpi", 420)
                            }
                        )
                    }
                )
            )
            controlSession.sendText(burstFrame)

            assertTrue(
                exchangeKeyLatch.await(5, TimeUnit.SECONDS),
                "Timed out waiting for PC Step 4 EXCHANGE_KEY reply"
            )
            val pcExchEnv = receivedByPhone.first {
                it.serviceId == SmartConnectConstants.SVC_HANDSHAKE &&
                    SmartConnectEnvelopeCodec.extractCmd(it) == SmartConnectConstants.CMD_EXCHANGE_KEY
            }
            val parsedPcExch = SmartConnectHandshakeCodec.parseExchangeKey(pcExchEnv)
            // Verify the wire quirk: BOTH device.deviceId and device.realDeviceId are pcRealId
            assertEquals(coordinator.realDeviceIdBase64, parsedPcExch.data.device.deviceId)
            assertEquals(coordinator.realDeviceIdBase64, parsedPcExch.data.device.realDeviceId)

            val pairedPeers = coordinator.getPairedPeers()
            assertEquals(1, pairedPeers.size)
            val pairedRecord = pairedPeers.single()
            assertEquals(phoneRealIdB64, pairedRecord.realDeviceId)
            assertEquals("aryan-moto-a5x", pairedRecord.deviceName)

            assertTrue(
                refreshLatch.await(5, TimeUnit.SECONDS),
                "Timed out waiting for Step 6 post-pair mDNS discovery refresh"
            )

            val statusSnap = coordinator.peerStatusFlow.value[phoneRealIdB64]
            assertNotNull(statusSnap)
            assertEquals(87, statusSnap.battery)
            assertEquals(1080, statusSnap.displayWidth)
            assertEquals(2400, statusSnap.displayHeight)
            assertNotNull(statusSnap.wallpaperPath)

            // Test /readyfor/mdns sealed discovery trigger -> sealed invite reply
            val rotatedPhoneNonceRaw16 = SmartConnectIdentity.generateNonceIdRaw(
                SmartConnectIdentity.decodeId16(phoneRealIdB64)
            )
            val perPairKey32 = Base64.getDecoder().decode(pairedRecord.perPairKey)
            val sealedTrigger = SealedDiscoveryFrameCodec.seal(
                senderNonceIdRaw16 = rotatedPhoneNonceRaw16,
                perPairKey32 = perPairKey32,
                plaintextUtf8 = """{"cmd":0}"""
            )

            val inviteReplyLatch = CountDownLatch(1)
            var receivedSealedReply: ByteArray? = null
            val mdnsSession = phoneTransport.connectClient(
                host = "127.0.0.1",
                port = port,
                path = SmartConnectConstants.WS_DISCOVERY_PATH,
                expectedServerFingerprintHex = coordinator.certFingerprintHex,
                listener = object : PinnedTlsWebSocketListener {
                    override fun onBinaryMessage(session: PinnedTlsWebSocketSession, bytes: ByteArray) {
                        receivedSealedReply = bytes
                        inviteReplyLatch.countDown()
                    }
                }
            )
            mdnsSession.sendBinary(sealedTrigger)
            assertTrue(
                inviteReplyLatch.await(5, TimeUnit.SECONDS),
                "Timed out waiting for sealed discovery invite reply on /readyfor/mdns"
            )
            val replyBytes = receivedSealedReply!!
            SealedDiscoveryFrameCodec.assertOutgoingFrameSafe(replyBytes)
            val openedInvite = SealedDiscoveryFrameCodec.openWithKey(replyBytes, perPairKey32)
            assertTrue(openedInvite.plaintextUtf8.contains("\"cmd\":1"))
            assertTrue(openedInvite.plaintextUtf8.contains("\"wsPort\":$port"))

            mdnsSession.close()
            controlSession.close()
            phoneTransport.close()
        } finally {
            coordinator.close()
            tempDir.deleteRecursively()
        }
    }
}
