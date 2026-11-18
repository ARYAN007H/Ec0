/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Walled-garden notification-cap tests (audit M1) and file-transfer incoming
 * cap tests (audit M4).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.protocol.NotificationPostInfo
import ec0.core.engine.protocol.NotificationProtocol
import ec0.core.engine.protocol.FileOfferCommand
import ec0.core.engine.protocol.FileTransferState
import ec0.core.engine.protocol.FileEntryManifest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationCapsTest {

    private fun postEnvelope(title: String, body: String) = Ec0Envelope(
        msgType = "info",
        msgBody = buildJsonObject {
            put("info_type", NotificationProtocol.INFO_NOTIF_POST)
            put(
                "info_body",
                buildJsonObject {
                    put("notif_id", "n1")
                    put("pkg", "com.audited.app")
                    put("app_name", "Audited")
                    put("title", title)
                    put("body", body)
                    put("ts_ms", 1L)
                },
            )
        },
        srcRole = "phone",
        category = NotificationProtocol.CATEGORY_NOTIF,
        timestamp = 0L,
        seq = 1L,
        nonce = 0L,
        sig = "test",
    )

    @Test
    fun incomingOversizedPostIsSanitizedBeforeTheFeed() {
        val service = NotificationRelayService()
        service.handleControlEnvelope(
            postEnvelope(
                title = "T".repeat(10_000),
                body = "B".repeat(1_000_000),
            ),
        )
        val stored = service.notifications.value.single()
        assertEquals(NotificationPostInfo.MAX_TITLE_CHARS, stored.title.length)
        assertEquals(NotificationPostInfo.MAX_BODY_CHARS, stored.body.length)
    }

    @Test
    fun actionListIsCapped() {
        val service = NotificationRelayService()
        val actions = (1..20).map { "action-$it-" + "x".repeat(200) }
        val envelope = Ec0Envelope(
            msgType = "info",
            msgBody = buildJsonObject {
                put("info_type", NotificationProtocol.INFO_NOTIF_POST)
                put(
                    "info_body",
                    buildJsonObject {
                        put("notif_id", "n2")
                        put("pkg", "com.audited.app")
                        put("app_name", "Audited")
                        put("title", "t")
                        put("body", "b")
                        put("ts_ms", 1L)
                        put(
                            "actions",
                            kotlinx.serialization.json.buildJsonArray {
                                for (a in actions) add(kotlinx.serialization.json.JsonPrimitive(a))
                            },
                        )
                    },
                )
            },
            srcRole = "phone",
            category = NotificationProtocol.CATEGORY_NOTIF,
            timestamp = 0L,
            seq = 1L,
            nonce = 0L,
            sig = "test",
        )
        service.handleControlEnvelope(envelope)
        val stored = service.notifications.value.single()
        assertTrue(stored.actions.size <= NotificationPostInfo.MAX_ACTION_COUNT)
        assertTrue(stored.actions.all { it.length <= NotificationPostInfo.MAX_ACTION_CHARS })
    }

    @Test
    fun desktopMonitorTruncatesOversizedContent() {
        val relayed = mutableListOf<NotificationPostInfo>()
        val hugeTitle = "x".repeat(20_000)
        val block = buildString {
            append("method call time=123 sender=:1.0 -> dest=:1.5 serial=1 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify\n")
            append("   string \":1.0\"\n")
            append("   string \"audited-app\"\n")
            append("   string \"\"\n")
            append("   string \"$hugeTitle\"\n")
            append("   string \"body\"\n")
            append("\n")
        }
        val monitor = DesktopNotificationMonitor(
            sender = { relayed.add(it) },
            monitorRunner = { _, onLine -> block.lines().forEach(onLine) },
        )
        monitor.start()
        assertEquals(1, relayed.size, "the (capped) block is relayed once")
        assertEquals(NotificationPostInfo.MAX_TITLE_CHARS, relayed[0].title.length)
        assertEquals("body", relayed[0].body)
    }

    @Test
    fun oversizeNotifyBlockIsRejected() {
        val hugeBlock = "method call member=Notify " + "y".repeat(DesktopNotificationParser.MAX_BLOCK_CHARS + 1)
        assertTrue(DesktopNotificationParser.parseNotifyBlock(hugeBlock, 1L) == null)
    }

    @Test
    fun parseNotifyBlockIsLenientOnNormalSizes() {
        val block = buildString {
            append("method call member=Notify\n")
            append("   string \":1.0\"\n")
            append("   string \"app\"\n")
            append("   string \"icon\"\n")
            append("   string \"Title\"\n")
            append("   string \"Body\"\n")
            append("\n")
        }
        val parsed = DesktopNotificationParser.parseNotifyBlock(block, 7L)
        assertTrue(parsed != null)
        assertEquals("Title", parsed!!.title)
        assertEquals("Body", parsed.body)
    }
}

class FileTransferIncomingCapsTest {

    private fun offerWithFiles(count: Int, sizeBytes: Long): FileOfferCommand {
        val manifests = (0 until count).map { i ->
            FileEntryManifest(
                fileId = "f-$i",
                relativePath = "file-$i.bin",
                sizeBytes = sizeBytes,
                sha256 = "a".repeat(64),
                lastModifiedMs = 0L,
                streamId = 1_000 + i,
            )
        }
        return FileOfferCommand(
            transferId = "t-caps",
            chunkSize = 256 * 1024,
            files = manifests,
        )
    }

    private fun transport() = object : FileTransferService.OutboundTransport {
        val sent = mutableListOf<Pair<String, kotlinx.serialization.json.JsonObject>>()
        override fun sendCmd(cmdType: String, cmdBody: kotlinx.serialization.json.JsonObject) {
            sent.add(cmdType to cmdBody)
        }

        override fun sendInfo(infoType: String, infoBody: kotlinx.serialization.json.JsonObject) {
            sent.add(infoType to infoBody)
        }

        override fun sendChunk(frame: ec0.core.engine.transport.BulkStreamFrame) = Unit
    }

    @Test
    fun oversizeFileCountOfferIsRejectedWithoutAccept() {
        val service = FileTransferService(downloadDir = Files.createTempDirectory("ec0-ft-caps").toFile())
        val transport = transport()
        service.handleControlEnvelope(
            Ec0Envelope(
                msgType = "cmd",
                msgBody = buildJsonObject {
                    put("cmd_type", "file_offer")
                    put("cmd_body", offerWithFiles(FileTransferService.MAX_FILES_PER_INCOMING_OFFER + 1, 1L).toJsonObject())
                },
                srcRole = "phone",
                category = "file",
                timestamp = 0L,
                seq = 1L,
                nonce = 0L,
                sig = "test",
            ),
            transport,
        )
        assertEquals(FileTransferState.FAILED, service.transfers.value["t-caps"]?.state)
        assertTrue(
            transport.sent.none { it.first == "file_accept" },
            "no accept sent for a rejected offer",
        )
    }

    @Test
    fun oversizeTotalBytesOfferIsRejected() {
        val service = FileTransferService(downloadDir = Files.createTempDirectory("ec0-ft-caps2").toFile())
        val transport = transport()
        service.handleControlEnvelope(
            Ec0Envelope(
                msgType = "cmd",
                msgBody = buildJsonObject {
                    put("cmd_type", "file_offer")
                    put(
                        "cmd_body",
                        offerWithFiles(1, FileTransferService.MAX_TOTAL_BYTES_PER_INCOMING_OFFER + 1).toJsonObject(),
                    )
                },
                srcRole = "phone",
                category = "file",
                timestamp = 0L,
                seq = 1L,
                nonce = 0L,
                sig = "test",
            ),
            transport,
        )
        assertEquals(FileTransferState.FAILED, service.transfers.value["t-caps"]?.state)
        assertTrue(transport.sent.none { it.first == "file_accept" })
    }

    @Test
    fun normalOfferStillAutoAccepts() {
        val service = FileTransferService(downloadDir = Files.createTempDirectory("ec0-ft-caps3").toFile())
        val transport = transport()
        service.handleControlEnvelope(
            Ec0Envelope(
                msgType = "cmd",
                msgBody = buildJsonObject {
                    put("cmd_type", "file_offer")
                    put("cmd_body", offerWithFiles(2, 512L).toJsonObject())
                },
                srcRole = "phone",
                category = "file",
                timestamp = 0L,
                seq = 1L,
                nonce = 0L,
                sig = "test",
            ),
            transport,
        )
        assertEquals(FileTransferState.TRANSFERRING, service.transfers.value["t-caps"]?.state)
        assertTrue(transport.sent.any { it.first == "file_accept" }, "legit folder sync unaffected")
    }
}
