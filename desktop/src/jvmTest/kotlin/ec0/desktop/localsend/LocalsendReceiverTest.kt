/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Per-route tests for the LocalSend receiver — real HTTP over the stdlib
 * server on an ephemeral port, exercising the exact wire contract
 * (status codes, bodies, PIN model, fingerprint semantics, /show internal).
 *
 * NOTE: desktop's jvmTest source set needs kotlin("test") + kotlinx-
 * coroutines-core on its classpath — gradle edit requested in the hub log
 * (agent-0 applies; desktop tests have no local deps of their own).
 */
package ec0.desktop.localsend

import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.api.LocalsendV2Route
import ec0.core.localsend.dto.MulticastMessageV2
import ec0.core.localsend.dto.PrepareUploadRequestDtoV2
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.core.localsend.server.AcceptPolicy
import ec0.core.localsend.server.IncomingSessionStore
import ec0.core.localsend.server.LocalsendReceiverConfig
import ec0.core.localsend.server.ReceiverAcceptance
import ec0.core.localsend.server.ReceiverAcceptance
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.ProtocolType
import ec0.core.localsend.DeviceType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalsendReceiverTest {

    private fun receiver(
        acceptPolicy: AcceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL,
        requiredPin: String? = null,
        decide: (suspend (PrepareUploadRequestDtoV2, String) -> ReceiverAcceptance)? = null,
        offeredFiles: Map<String, java.nio.file.Path> = emptyMap(),
        onShow: () -> Unit = {},
    ): LocalsendReceiverServer {
        val config = LocalsendReceiverConfig(
            port = 0,
            alias = "ec0-desktop",
            deviceModel = "Linux",
            deviceType = DeviceType.DESKTOP,
            requiredPin = requiredPin,
            destination = Files.createTempDirectory("ec0-incoming"),
            offeredFiles = offeredFiles,
            acceptPolicy = acceptPolicy,
            fingerprint = "testfingerprint1234567890abcdef",
        )
        val store = IncomingSessionStore()
        return LocalsendReceiverServer(
            config = config,
            store = store,
            decide = decide ?: { _, _ -> ReceiverAcceptance.DECLINE },
            onShow = onShow,
        )
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun start(receiver: LocalsendReceiverServer): Int {
        receiver.start(0)
        return receiver.boundPort!!
    }

    private fun post(port: Int, path: String, body: String, query: String = ""): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port$path?$query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun get(port: Int, path: String, query: String = ""): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:$port$path?$query")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun senderInfo() = RegisterDtoV2(
        alias = "A5x",
        version = LocalsendV2.PROTOCOL_VERSION,
        deviceModel = "OPPO A5x 5G",
        deviceType = DeviceType.MOBILE,
        fingerprint = "sender-fingerprint",
        port = 53317,
        protocol = ProtocolType.HTTP,
        download = true,
    )

    private fun prepareBody(files: List<FileDto>): String = LocalsendV2.json.encodeToString(
        PrepareUploadRequestDtoV2.serializer(),
        PrepareUploadRequestDtoV2(info = senderInfo(), files = files.associateBy { it.id }),
    )

    private fun sampleFiles() = listOf(
        FileDto(id = "f1", fileName = "notes.txt", size = 5, fileType = "text/plain", sha256 = null),
        FileDto(id = "f2", fileName = "img.png", size = 4, fileType = "image/png", sha256 = null),
    )

    @Test
    fun registerReturnsDeviceWithoutPortOrProtocol() {
        val receiver = receiver()
        val port = start(receiver)
        val res = post(port, LocalsendV2Route.REGISTER.wirePath, LocalsendV2.json.encodeToString(RegisterDtoV2.serializer(), senderInfo()))
        assertEquals(200, res.statusCode())
        val json = LocalsendV2.json.parseToJsonElement(res.body()).jsonObject
        assertEquals("A5x", json["alias"]!!.jsonPrimitive.content) // register echoes the REQUEST info
        assertEquals("sender-fingerprint", json["fingerprint"]!!.jsonPrimitive.content)
        assertTrue(json.containsKey("download"))
        assertFalse(json.containsKey("port")) // RegisterResponseDtoV2 echoes no port/protocol
        assertFalse(json.containsKey("protocol"))
        receiver.stop()
    }

    @Test
    fun prepareUploadAutoAcceptIssuesTokens() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        val res = post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()))
        assertEquals(200, res.statusCode())
        val json = LocalsendV2.json.parseToJsonElement(res.body()).jsonObject
        val sessionId = json["sessionId"]!!.jsonPrimitive.content
        assertTrue(sessionId.isNotBlank())
        val tokens = json["files"]!!.jsonObject
        assertEquals(setOf("f1", "f2"), tokens.keys)
        assertTrue(tokens["f1"]!!.jsonPrimitive.content != tokens["f2"]!!.jsonPrimitive.content) // distinct UUID tokens
        receiver.stop()
    }

    @Test
    fun prepareUploadDeclineIs403AndEmptyAcceptIs204() {
        val decline = receiver(acceptPolicy = AcceptPolicy.AUTO_DECLINE_ALL)
        val port = start(decline)
        assertEquals(403, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).statusCode())
        decline.stop()

        val acceptNothing = receiver(
            acceptPolicy = AcceptPolicy.ASK,
            decide = { _, _ -> ReceiverAcceptance.ACCEPT(emptySet()) },
        )
        val port2 = start(acceptNothing)
        assertEquals(204, post(port2, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).statusCode())
        acceptNothing.stop()
    }

    @Test
    fun prepareUploadEmptyFilesIs400() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        assertEquals(400, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(emptyList())).statusCode())
        receiver.stop()
    }

    @Test
    fun prepareUploadConflictWhileSessionActive() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        assertEquals(200, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).statusCode())
        assertEquals(409, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).statusCode())
        receiver.stop()
    }

    @Test
    fun pinModelWrong401RateLimit429CorrectClears() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL, requiredPin = "4321")
        val port = start(receiver)
        val wrong = post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=0000")
        assertEquals(401, wrong.statusCode())
        assertEquals(401, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=0000").statusCode())
        assertEquals(401, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=1111").statusCode())
        // 3 failures recorded → next blocked with 429 even with the CORRECT pin? Reference:
        // 429 fires before evaluation once the limit is reached.
        assertEquals(429, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=4321").statusCode())
        receiver.stop()
    }

    @Test
    fun pinCorrectAttemptSucceedsAndClearsCounter() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL, requiredPin = "4321")
        val port = start(receiver)
        assertEquals(401, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=0000").statusCode())
        assertEquals(200, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=4321").statusCode())
        // counter cleared — another wrong attempt is 401 again, not 429
        assertEquals(401, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles()), query = "pin=9999").statusCode())
        receiver.stop()
    }

    @Test
    fun uploadFlowEndToEndAndSecondAttemptRejected() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        val prepared = LocalsendV2.json.parseToJsonElement(
            post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).body(),
        ).jsonObject
        val sessionId = prepared["sessionId"]!!.jsonPrimitive.content
        val token = prepared["files"]!!.jsonObject["f1"]!!.jsonPrimitive.content

        val upload = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=$sessionId&fileId=f1&token=$token"))
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, upload.statusCode())
        assertEquals("", upload.body()) // empty 200 ack (v2.rs:453)

        val written = receiver.config.destination.resolve("notes.txt")
        assertEquals("hello", Files.readString(written))

        // token/file no longer PENDING → 403
        assertEquals(403, http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=$sessionId&fileId=f1&token=$token"))
                .POST(HttpRequest.BodyPublishers.ofString("again"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode())
        receiver.stop()
    }

    @Test
    fun uploadChecksumMismatchIs422() {
        val files = listOf(
            FileDto(id = "f1", fileName = "notes.txt", size = 5, fileType = "text/plain", sha256 = "deadbeef"),
        )
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        val prepared = LocalsendV2.json.parseToJsonElement(
            post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(files)).body(),
        ).jsonObject
        val token = prepared["files"]!!.jsonObject["f1"]!!.jsonPrimitive.content
        val res = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=${prepared["sessionId"]!!.jsonPrimitive.content}&fileId=f1&token=$token"))
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(422, res.statusCode())
        receiver.stop()
    }

    @Test
    fun uploadMissingParamsIs400AndUnknownTokenIs403() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        assertEquals(400, post(port, LocalsendV2Route.UPLOAD.wirePath, "x", query = "sessionId=a").statusCode())
        assertEquals(403, post(port, LocalsendV2Route.UPLOAD.wirePath, "x", query = "sessionId=a&fileId=f&token=t").statusCode())
        receiver.stop()
    }

    @Test
    fun cancelRemovesSessionThenUploadFails() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        val prepared = LocalsendV2.json.parseToJsonElement(
            post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).body(),
        ).jsonObject
        val sessionId = prepared["sessionId"]!!.jsonPrimitive.content
        val token = prepared["files"]!!.jsonObject["f1"]!!.jsonPrimitive.content

        val cancel = post(port, LocalsendV2Route.CANCEL.wirePath, "", query = "sessionId=$sessionId")
        assertEquals(200, cancel.statusCode())
        assertEquals("", cancel.body())

        assertEquals(403, http.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=$sessionId&fileId=f1&token=$token"))
                .POST(HttpRequest.BodyPublishers.ofString("late"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).statusCode())
        receiver.stop()
    }

    @Test
    fun prepareDownloadAndDownloadServeOfferedFiles() {
        val offered = Files.createTempDirectory("ec0-offered").resolve("readme.txt")
        Files.writeString(offered, "ec0 offered file")
        val receiver = receiver(offeredFiles = mapOf("d1" to offered), requiredPin = null)
        val port = start(receiver)
        val prepared = post(port, LocalsendV2Route.PREPARE_DOWNLOAD.wirePath, "{}")
        assertEquals(200, prepared.statusCode())
        val json = LocalsendV2.json.parseToJsonElement(prepared.body()).jsonObject
        val sessionId = json["sessionId"]!!.jsonPrimitive.content
        assertTrue(json["files"]!!.jsonObject.containsKey("d1"))
        assertEquals("ec0-desktop", json["info"]!!.jsonObject["alias"]!!.jsonPrimitive.content)

        val download = get(port, LocalsendV2Route.DOWNLOAD.wirePath, query = "sessionId=$sessionId&fileId=d1")
        assertEquals(200, download.statusCode())
        assertEquals("ec0 offered file", download.body())
        receiver.stop()
    }

    @Test
    fun prepareDownloadWrongPinIs401() {
        val receiver = receiver(requiredPin = "9999")
        val port = start(receiver)
        assertEquals(401, post(port, LocalsendV2Route.PREPARE_DOWNLOAD.wirePath, "{}", query = "pin=0000").statusCode())
        receiver.stop()
    }

    @Test
    fun infoRouteExposesFingerprintAndDownload() {
        val receiver = receiver()
        val port = start(receiver)
        val res = get(port, LocalsendV2Route.INFO.wirePath)
        assertEquals(200, res.statusCode())
        val json = LocalsendV2.json.parseToJsonElement(res.body()).jsonObject
        assertEquals("testfingerprint1234567890abcdef", json["fingerprint"]!!.jsonPrimitive.content)
        assertTrue(json["download"]!!.jsonPrimitive.content == "true")
        receiver.stop()
    }

    @Test
    fun showRouteInvokesCallbackAndWrongMethodIs405() {
        var shown = false
        val receiver = receiver(onShow = { shown = true })
        val port = start(receiver)
        assertEquals(403, post(port, "${LocalsendV2.API_PATH_V2}/show", "").statusCode()) // missing token
        assertEquals(403, post(port, "${LocalsendV2.API_PATH_V2}/show", "", query = "token=wrong").statusCode())
        assertEquals(200, post(port, "${LocalsendV2.API_PATH_V2}/show", "", query = "token=${receiver.showToken}").statusCode())
        assertTrue(shown)
        assertEquals(405, get(port, LocalsendV2Route.REGISTER.wirePath).statusCode()) // GET on POST route
        receiver.stop()
    }

    @Test
    fun announcerPayloadCarriesDownloadTrueAndFingerprint() {
        val receiver = receiver()
        val message: MulticastMessageV2 = receiver.multicastMessage()
        assertTrue(message.download) // download=true once the server is up
        assertEquals("testfingerprint1234567890abcdef", message.fingerprint)
        assertEquals(ProtocolType.HTTP, message.protocol)
    }

    @Test
    fun uploadFileNameTraversalIsSanitizedIntoDestination() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        try {
            val evil = listOf(
                FileDto(id = "e1", fileName = "../pwned.txt", size = 4, fileType = "text/plain"),
                FileDto(id = "e2", fileName = "/tmp/pwned-abs.txt", size = 4, fileType = "text/plain"),
            )
            val prepared = LocalsendV2.json.parseToJsonElement(
                post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(evil)).body(),
            ).jsonObject
            val sessionId = prepared["sessionId"]!!.jsonPrimitive.content
            for ((fileId, name) in listOf("e1" to "pwned.txt", "e2" to "pwned-abs.txt")) {
                val token = prepared["files"]!!.jsonObject[fileId]!!.jsonPrimitive.content
                val res = http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=$sessionId&fileId=$fileId&token=$token"))
                        .POST(HttpRequest.BodyPublishers.ofString("pwn!"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, res.statusCode())
                val target = receiver.config.destination.resolve(name)
                assertEquals("pwn!", Files.readString(target), "$name must land INSIDE the destination, sanitized")
            }
            assertFalse(Files.exists(receiver.config.destination.resolve("..").resolve("pwned.txt")))
            assertFalse(Files.exists(java.nio.file.Path.of("/tmp/pwned-abs.txt")))
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun uploadOverflowingDeclaredSizeIsRejected() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        try {
            val files = listOf(FileDto(id = "big", fileName = "big.bin", size = 4, fileType = "application/octet-stream"))
            val prepared = LocalsendV2.json.parseToJsonElement(
                post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(files)).body(),
            ).jsonObject
            val sessionId = prepared["sessionId"]!!.jsonPrimitive.content
            val token = prepared["files"]!!.jsonObject["big"]!!.jsonPrimitive.content
            val res = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=$sessionId&fileId=big&token=$token"))
                    .POST(HttpRequest.BodyPublishers.ofString("0123456789")) // 10 bytes > declared 4
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, res.statusCode())
            assertFalse(Files.exists(receiver.config.destination.resolve("big.bin")))
        } finally {
            receiver.stop()
        }
    }

    @Test
    fun sessionSlotFreesAfterFailedFileSoOthersCanSend() {
        val receiver = receiver(acceptPolicy = AcceptPolicy.AUTO_ACCEPT_ALL)
        val port = start(receiver)
        try {
            val files = listOf(FileDto(id = "f1", fileName = "notes.txt", size = 5, fileType = "text/plain", sha256 = "deadbeef"))
            val prepared = LocalsendV2.json.parseToJsonElement(
                post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(files)).body(),
            ).jsonObject
            val sessionId = prepared["sessionId"]!!.jsonPrimitive.content
            val token = prepared["files"]!!.jsonObject["f1"]!!.jsonPrimitive.content
            // 3 checksum failures exhaust the retry window (upstream MAX_UPLOAD_ATTEMPTS = 3)
            repeat(3) {
                assertEquals(422, http.send(
                    HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:$port${LocalsendV2Route.UPLOAD.wirePath}?sessionId=$sessionId&fileId=f1&token=$token"))
                        .POST(HttpRequest.BodyPublishers.ofString("hello"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).statusCode())
            }
            // slot freed despite the FAILED file — a new sender is NOT 409'd
            assertEquals(200, post(port, LocalsendV2Route.PREPARE_UPLOAD.wirePath, prepareBody(sampleFiles())).statusCode())
        } finally {
            receiver.stop()
        }
    }
}
