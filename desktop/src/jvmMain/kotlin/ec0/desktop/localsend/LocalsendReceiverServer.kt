/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Desktop LocalSend v2 RECEIVER — the 7-route HTTP contract on the stdlib
 * HTTP server (com.sun.net.httpserver; ktor-server lives behind core's
 * `implementation` visibility and desktop stays stdlib-only on purpose).
 *
 * Wire truths replicated from the reference implementation
 * (localsend-upstream packages/core/src/http/server/v2.rs, verifier-approved):
 * - prepare-upload: empty files → 400; another session → 409 "Blocked by
 *   another session"; nothing accepted → **204 no-content**; decline → **403**;
 *   wrong/missing PIN → **401**; PIN rate-limit → **429**; success → 200
 *   PrepareUploadResponseDtoV2 (sessionId + file-id → UUID token).
 * - upload: missing query → 400; session/token/IP/pending mismatch → **403**
 *   (invalid_token_error, v2.rs:544); success → **empty 200**; save failure →
 *   500; sha256 mismatch → 422.
 * - cancel: always ends with an empty 200 (pending or active, even unknown).
 * - /api/localsend/v2/show: internal route (mod.rs:676) → invokes the show
 *   callback, empty 200.
 *
 * PIN model: `LocalsendSecurity.evaluatePin` + per-IP failure counter
 * (LruCache cap 200 like upstream), success clears the counter.
 * Fingerprint: HTTP mode → random string (this server); HTTPS mode → cert
 * SHA-256 (wiring deferred, documented in LocalsendReceiverConfig).
 */
package ec0.desktop.localsend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.api.LocalsendV2Route
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.InfoResponseDtoV2
import ec0.core.localsend.dto.PrepareDownloadResponseDtoV2
import ec0.core.localsend.dto.PrepareUploadRequestDtoV2
import ec0.core.localsend.dto.PrepareUploadResponseDtoV2
import ec0.core.localsend.dto.RegisterResponseDtoV2
import ec0.core.localsend.security.LocalsendSecurity
import ec0.core.localsend.security.PinDecision
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.logging.Logger

class LocalsendReceiverServer(
    val config: LocalsendReceiverConfig,
    private val store: IncomingSessionStore = IncomingSessionStore(),
    /**
     * Decide which offered files to accept when [LocalsendReceiverConfig.AcceptPolicy.ASK]
     * is configured. Upstream distinguishes three outcomes (server/v2.rs):
     * DECLINE → 403, ACCEPT(empty) → 204 no-content, ACCEPT(non-empty) → 200.
     */
    sealed interface Acceptance {
        /** Explicit decline by the recipient → 403. */
        data object DECLINE : Acceptance

        /** Acceptance decision — an empty set means "accept nothing" → 204. */
        data class ACCEPT(val fileIds: Set<String>) : Acceptance
    }

    private val decide: suspend (request: PrepareUploadRequestDtoV2, senderIp: String) -> Acceptance =
        { _, _ -> Acceptance.DECLINE },
    private val onShow: () -> Unit = {},
    private val logger: Logger = Logger.getLogger("LocalsendReceiver"),
) {
    /** Transfer queue for the UI (agent-1's TransferItem model). */
    val transferQueue: StateFlow<List<ec0.desktop.state.TransferItem>> get() = store.transferQueue

    private var server: HttpServer? = null

    /** Actual bound port (resolves 0 → ephemeral) once started. */
    var boundPort: Int? = null
        private set

    private val json = LocalsendV2.json

    val deviceInfo: ec0.core.localsend.dto.RegisterDtoV2
        get() = ec0.core.localsend.dto.RegisterDtoV2(
            alias = config.alias,
            version = LocalsendV2.PROTOCOL_VERSION,
            deviceModel = config.deviceModel,
            deviceType = config.deviceType,
            fingerprint = config.fingerprint,
            port = config.port,
            protocol = ec0.core.localsend.ProtocolType.HTTP,
            download = true, // download API offered as soon as the server is up
        )

    fun start(port: Int = config.port) {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/") { exchange -> dispatch(exchange) }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        boundPort = server.address.port
        this.server = server
        logger.info("LocalSend receiver listening on :$port (fingerprint=${config.fingerprint.take(8)}…)")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // ---------------------------------------------------------------- dispatch

    private fun dispatch(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val method = exchange.requestMethod.uppercase()
        try {
            when (path) {
                LocalsendV2Route.REGISTER.wirePath -> handleRegister(exchange)
                LocalsendV2Route.PREPARE_UPLOAD.wirePath -> handlePrepareUpload(exchange)
                LocalsendV2Route.UPLOAD.wirePath -> handleUpload(exchange)
                LocalsendV2Route.CANCEL.wirePath -> handleCancel(exchange)
                LocalsendV2Route.PREPARE_DOWNLOAD.wirePath -> handlePrepareDownload(exchange)
                LocalsendV2Route.DOWNLOAD.wirePath -> handleDownload(exchange)
                LocalsendV2Route.INFO.wirePath -> handleInfo(exchange)
                "${LocalsendV2.API_PATH_V2}/show" -> handleShow(exchange)
                else -> respond(exchange, 404, "Not found".toByteArray())
            }
        } catch (e: Exception) {
            logger.warning("Receiver handler error on $method $path: $e")
            runCatching { respond(exchange, 500, "Internal error".toByteArray()) }
        } finally {
            exchange.close()
        }
    }

    // ---------------------------------------------------------------- routes

    private fun handleRegister(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.REGISTER)) return
        respondJson(
            exchange,
            200,
            RegisterResponseDtoV2.serializer(),
            RegisterResponseDtoV2(
                alias = config.alias,
                version = LocalsendV2.PROTOCOL_VERSION,
                deviceModel = config.deviceModel,
                deviceType = config.deviceType,
                fingerprint = config.fingerprint,
                download = true,
            ),
        )
    }

    private fun handlePrepareUpload(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.PREPARE_UPLOAD)) return
        val senderIp = exchange.remoteAddress.address.hostAddress

        // PIN gate — before anything else (reference common/pin.rs + v2.rs:237)
        val decision = LocalsendSecurity.evaluatePin(config.requiredPin, query(exchange)["pin"], store.pinFailureCount(senderIp))
        when (decision) {
            PinDecision.BLOCKED_TOO_MANY_ATTEMPTS -> return respond(exchange, 429, "Too many PIN attempts".toByteArray())
            PinDecision.DENIED_WRONG_PIN -> {
                store.recordPinFailure(senderIp)
                return respond(exchange, 401, "Wrong PIN".toByteArray())
            }
            PinDecision.ALLOWED -> store.clearPinFailures(senderIp)
        }

        val request = parseJsonBody(exchange, PrepareUploadRequestDtoV2.serializer())
            ?: return respond(exchange, 400, "Malformed body".toByteArray())
        if (request.files.isEmpty()) return respond(exchange, 400, "No files provided".toByteArray())

        if (store.hasBlockingSession()) {
            return respond(exchange, 409, "Blocked by another session".toByteArray())
        }

        // The decision callback may block on a user prompt (executor thread).
        val acceptance = kotlinx.coroutines.runBlocking { decideAccepted(request, senderIp) }
        val accepted: Set<String> = when (acceptance) {
            Acceptance.DECLINE ->
                return respond(exchange, 403, "Declined".toByteArray()) // distinct from wrong-PIN 401
            is Acceptance.ACCEPT -> acceptance.fileIds
        }
        if (accepted.isEmpty()) {
            // Accepted nothing → no transfer needed → 204 no-content (v2.rs:138)
            return respond(exchange, 204, ByteArray(0))
        }

        val sessionId = UUID.randomUUID().toString()
        val acceptedFiles: Map<String, FileDto> = request.files.filterKeys { it in accepted }
        val session = store.createPendingSession(sessionId, senderIp, request.info, acceptedFiles, config.destination)

        val tokens = acceptedFiles.mapValues { (fileId, _) -> session.files.getValue(fileId).token }
        respondJson(exchange, 200, PrepareUploadResponseDtoV2.serializer(), PrepareUploadResponseDtoV2(sessionId = sessionId, files = tokens))
    }

    private suspend fun decideAccepted(request: PrepareUploadRequestDtoV2, senderIp: String): Acceptance =
        when (config.acceptPolicy) {
            LocalsendReceiverConfig.AcceptPolicy.AUTO_ACCEPT_ALL -> Acceptance.ACCEPT(request.files.keys)
            LocalsendReceiverConfig.AcceptPolicy.AUTO_DECLINE_ALL -> Acceptance.DECLINE
            LocalsendReceiverConfig.AcceptPolicy.ASK -> decide(request, senderIp)
        }

    private fun handleUpload(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.UPLOAD)) return
        val query = query(exchange)
        val sessionId = query["sessionId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val fileId = query["fileId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val token = query["token"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val senderIp = exchange.remoteAddress.address.hostAddress

        val file = store.validateUpload(sessionId, senderIp, fileId, token)
            ?: return respond(exchange, 403, "Invalid token or IP address".toByteArray())

        store.markInProgress(sessionId, fileId)
        try {
            file.target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            var written = 0L
            exchange.requestBody.use { input ->
                Files.newOutputStream(
                    file.target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        store.markProgress(sessionId, fileId, written)
                    }
                }
            }

            // Optional checksum verification (reference: verify_checksums)
            val expectedSha256 = if (config.verifyChecksums) file.dto.sha256 else null
            if (expectedSha256 != null) {
                val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.target))
                    .joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    Files.deleteIfExists(file.target)
                    store.markFailed(sessionId, fileId)
                    return respond(exchange, 422, "Checksum mismatch".toByteArray())
                }
            }

            store.markDone(sessionId, fileId)
            if (store.findSession(sessionId)?.files?.values?.all { it.status == IncomingFileStatus.DONE } == true) {
                store.markSessionFinished(sessionId)
            }
            respond(exchange, 200, ByteArray(0)) // empty 200 ack (v2.rs:453)
        } catch (e: IOException) {
            store.markFailed(sessionId, fileId)
            respond(exchange, 500, "Save failed".toByteArray())
        }
    }

    private fun handleCancel(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.CANCEL)) return
        val sessionId = query(exchange)["sessionId"]
        if (sessionId != null) store.removeSession(sessionId)
        respond(exchange, 200, ByteArray(0)) // empty 200 regardless (v2.rs:534)
    }

    private fun handlePrepareDownload(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.PREPARE_DOWNLOAD)) return
        val query = query(exchange)
        val senderIp = exchange.remoteAddress.address.hostAddress
        val decision = LocalsendSecurity.evaluatePin(config.requiredPin, query["pin"], store.pinFailureCount(senderIp))
        when (decision) {
            PinDecision.BLOCKED_TOO_MANY_ATTEMPTS -> return respond(exchange, 429, "Too many PIN attempts".toByteArray())
            PinDecision.DENIED_WRONG_PIN -> {
                store.recordPinFailure(senderIp)
                return respond(exchange, 401, "Wrong PIN".toByteArray())
            }
            PinDecision.ALLOWED -> store.clearPinFailures(senderIp)
        }

        val sessionId = query["sessionId"] ?: UUID.randomUUID().toString()
        val files = config.offeredFiles.entries.associate { (fileId, path) ->
            fileId to FileDto(
                id = fileId,
                fileName = path.fileName.toString(),
                size = Files.size(path),
                fileType = guessMimeType(path),
            )
        }
        respondJson(
            exchange,
            200,
            PrepareDownloadResponseDtoV2.serializer(),
            PrepareDownloadResponseDtoV2(
                info = InfoResponseDtoV2(
                    alias = config.alias,
                    version = LocalsendV2.PROTOCOL_VERSION,
                    deviceModel = config.deviceModel,
                    deviceType = config.deviceType,
                    fingerprint = config.fingerprint,
                    download = true,
                ),
                sessionId = sessionId,
                files = files,
            ),
        )
    }

    private fun handleDownload(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.DOWNLOAD)) return
        val fileId = query(exchange)["fileId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val path = config.offeredFiles[fileId] ?: return respond(exchange, 404, "Unknown file".toByteArray())
        val bytes = Files.readAllBytes(path)
        exchange.responseHeaders.set("Content-Type", guessMimeType(path))
        respond(exchange, 200, bytes)
    }

    private fun handleInfo(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.INFO)) return
        respondJson(
            exchange,
            200,
            InfoResponseDtoV2.serializer(),
            InfoResponseDtoV2(
                alias = config.alias,
                version = LocalsendV2.PROTOCOL_VERSION,
                deviceModel = config.deviceModel,
                deviceType = config.deviceType,
                fingerprint = config.fingerprint,
                download = true,
            ),
        )
    }

    private fun handleShow(exchange: HttpExchange) {
        // Internal route (mod.rs:676): bring the pickup UI forward
        if (exchange.requestMethod.uppercase() != "POST") return respond(exchange, 405, "Method not allowed".toByteArray())
        runCatching { onShow() }
        respond(exchange, 200, ByteArray(0))
    }

    // ---------------------------------------------------------------- helpers

    /** Responds 405 and returns false when the method does not match the route contract. */
    private fun requireMethod(exchange: HttpExchange, route: LocalsendV2Route): Boolean {
        if (exchange.requestMethod.uppercase() != route.method.name) {
            respond(exchange, 405, "Method not allowed".toByteArray())
            return false
        }
        return true
    }

    private fun query(exchange: HttpExchange): Map<String, String> =
        exchange.requestURI.rawQuery?.split('&')?.filter { it.isNotBlank() }?.associate {
            val idx = it.indexOf('=')
            if (idx == -1) {
                java.net.URLDecoder.decode(it, "UTF-8") to ""
            } else {
                java.net.URLDecoder.decode(it.substring(0, idx), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(idx + 1), "UTF-8")
            }
        } ?: emptyMap()

    private fun <T : Any> parseJsonBody(exchange: HttpExchange, serializer: kotlinx.serialization.KSerializer<T>): T? =
        exchange.requestBody.use { input ->
            runCatching { json.decodeFromString(serializer, input.readBytes().decodeToString()) }.getOrNull()
        }

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }

    private fun <T : Any> respondJson(exchange: HttpExchange, status: Int, serializer: kotlinx.serialization.KSerializer<T>, payload: T) {
        val body = json.encodeToString(serializer, payload).toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun guessMimeType(path: Path): String = when (path.fileName.toString().substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    /** Discovery payload for the announcer — download=true while the server runs. */
    fun multicastMessage(): ec0.core.localsend.dto.MulticastMessageV2 = ec0.core.localsend.dto.MulticastMessageV2(
        alias = config.alias,
        version = LocalsendV2.PROTOCOL_VERSION,
        deviceModel = config.deviceModel,
        deviceType = config.deviceType,
        fingerprint = config.fingerprint,
        port = config.port,
        protocol = ec0.core.localsend.ProtocolType.HTTP,
        download = true,
    )
}
