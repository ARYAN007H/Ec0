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
 *   500; sha256 mismatch → 422 (with upstream's attempts-based retry window).
 * - cancel: always ends with an empty 200 (pending or active, even unknown) —
 *   honored only for the session's own sender IP (v2.rs:494-503).
 * - /api/localsend/v2/show: internal route (mod.rs:676) — token-gated like
 *   upstream internal::show.
 * - /download: only through PIN-gated prepare-download sessions bound to the
 *   client IP (web.rs:395-427), streamed, never read whole into memory.
 *
 * Security posture (review round 2):
 * - peer-supplied `fileName` is sanitized (FileNameSanitizer, reference
 *   filename.rs) AND the resolved target is containment-checked against the
 *   destination before any byte is written;
 * - declared size is enforced while streaming (upstream save.rs:267-306);
 * - tokens are single-use claims (validate + transition in one lock);
 * - checksums are computed incrementally during receive;
 * - the HTTP executor is shut down on stop() so the JVM can exit.
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
import ec0.core.localsend.dto.RegisterDtoV2
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

class LocalsendReceiverServer(
    val config: LocalsendReceiverConfig,
    private val store: IncomingSessionStore = IncomingSessionStore(),
    /**
     * Decide which offered files to accept when [AcceptPolicy.ASK]
     * is configured. Upstream distinguishes three outcomes (server/v2.rs):
     * DECLINE → 403, ACCEPT(empty) → 204 no-content, ACCEPT(non-empty) → 200.
     */
    private val decide: suspend (request: PrepareUploadRequestDtoV2, senderIp: String) -> Acceptance =
        { _, _ -> Acceptance.DECLINE },
    private val onShow: () -> Unit = {},
    private val logger: Logger = Logger.getLogger("LocalsendReceiver"),
) {
    /**
     * Upstream distinguishes three prepare-upload outcomes (server/v2.rs):
     * DECLINE → 403, ACCEPT(empty) → 204 no-content, ACCEPT(non-empty) → 200.
     */
    sealed interface Acceptance {
        /** Explicit decline by the recipient → 403. */
        data object DECLINE : Acceptance

        /** Acceptance decision — an empty set means "accept nothing" → 204. */
        data class ACCEPT(val fileIds: Set<String>) : Acceptance
    }

    /** Transfer queue for the UI (shared TransferItem model). */
    val transferQueue: StateFlow<List<ec0.ui.models.TransferItem>> get() = store.transferQueue

    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    /** Actual bound port (resolves 0 → ephemeral) once started. */
    var boundPort: Int? = null
        private set

    /**
     * Token for the internal /show route — upstream internal::show requires
     * `?token=` and 403s mismatches; the token is shared only with the local
     * launcher process (single-instance handoff), never over the wire.
     */
    val showToken: String = UUID.randomUUID().toString()

    private val json = LocalsendV2.json

    val deviceInfo: RegisterDtoV2
        get() = RegisterDtoV2(
            alias = config.alias,
            version = LocalsendV2.PROTOCOL_VERSION,
            deviceModel = config.deviceModel,
            deviceType = config.deviceType,
            fingerprint = config.fingerprint,
            port = boundPort ?: config.port,
            protocol = ec0.core.localsend.ProtocolType.HTTP,
            download = true, // download API offered as soon as the server is up
        )

    fun start(port: Int = config.port) {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("/") { exchange -> dispatch(exchange) }
        val executor = Executors.newFixedThreadPool(4)
        server.executor = executor
        server.start()
        boundPort = server.address.port
        this.server = server
        this.executor = executor
        logger.info("LocalSend receiver listening on :${server.address.port} (fingerprint=${config.fingerprint.take(8)}…)")
    }

    fun stop() {
        server?.stop(0)
        server = null
        // JDK HttpServer.stop() does NOT own a caller-supplied executor: the
        // workers would block the queue forever and keep the JVM alive.
        executor?.shutdownNow()
        executor = null
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
        } catch (e: Throwable) {
            // Throwable: an OOM mid-handler is an Error and must still 500 +
            // be logged with its stack instead of silently dropping the conn.
            logger.log(Level.WARNING, "Receiver handler error on $method $path", e)
            runCatching { respond(exchange, 500, "Internal error".toByteArray()) }
        } finally {
            exchange.close()
        }
    }

    // ---------------------------------------------------------------- routes

    private fun handleRegister(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.REGISTER)) return
        // Upstream echoes the REQUEST's info (v2.rs:196-209) — the sender uses
        // the fingerprint echo to confirm the receiver saw ITS identity.
        val request = parseJsonBody(exchange, RegisterDtoV2.serializer())
            ?: return respond(exchange, 400, "Malformed body".toByteArray())
        respondJson(
            exchange,
            200,
            RegisterResponseDtoV2.serializer(),
            RegisterResponseDtoV2(
                alias = request.alias,
                version = request.version,
                deviceModel = request.deviceModel,
                deviceType = request.deviceType,
                fingerprint = request.fingerprint,
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

        // Claim the single slot BEFORE the (possibly blocking) user decision —
        // upstream v2.rs:262-269: concurrent prepares 409 while deciding.
        val sessionId = UUID.randomUUID().toString()
        store.createSession(sessionId, senderIp, request.info, request.files, config.destination, IncomingSessionStatus.DECIDING)

        val acceptance = kotlinx.coroutines.runBlocking { decideAccepted(request, senderIp) }
        val accepted: Set<String> = when (acceptance) {
            Acceptance.DECLINE -> {
                store.removeSession(sessionId)
                return respond(exchange, 403, "Declined".toByteArray()) // distinct from wrong-PIN 401
            }
            is Acceptance.ACCEPT -> acceptance.fileIds
        }
        if (accepted.isEmpty()) {
            // Accepted nothing → no transfer needed → 204 no-content (v2.rs:138)
            store.removeSession(sessionId)
            return respond(exchange, 204, ByteArray(0))
        }

        store.filterToAccepted(sessionId, accepted)
        val files = store.findSession(sessionId)?.files ?: emptyMap()
        respondJson(exchange, 200, PrepareUploadResponseDtoV2.serializer(), PrepareUploadResponseDtoV2(sessionId = sessionId, files = files.mapValues { it.value.token }))
    }

    private suspend fun decideAccepted(request: PrepareUploadRequestDtoV2, senderIp: String): Acceptance =
        when (config.acceptPolicy) {
            AcceptPolicy.AUTO_ACCEPT_ALL -> Acceptance.ACCEPT(request.files.keys)
            AcceptPolicy.AUTO_DECLINE_ALL -> Acceptance.DECLINE
            AcceptPolicy.ASK -> decide(request, senderIp)
        }

    private fun handleUpload(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.UPLOAD)) return
        val query = query(exchange)
        val sessionId = query["sessionId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val fileId = query["fileId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val token = query["token"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val senderIp = exchange.remoteAddress.address.hostAddress

        // Atomic single-use claim: validate + IN_PROGRESS in one store lock
        // (two separate locks would let concurrent uploads interleave writes).
        val file = store.validateAndClaim(sessionId, senderIp, fileId, token)
            ?: return respond(exchange, 403, "Invalid token or IP address".toByteArray())

        // Containment check (defense in depth on top of FileNameSanitizer):
        // the resolved target must stay inside the destination directory.
        val destination = config.destination.toAbsolutePath().normalize()
        val target = file.target.toAbsolutePath().normalize()
        if (!target.startsWith(destination)) {
            store.markFailed(sessionId, fileId)
            store.maybeFinishSession(sessionId)
            return respond(exchange, 400, "Invalid file name".toByteArray())
        }

        try {
            target.parent?.let { Files.createDirectories(it) }
            val declaredSize = file.dto.size
            val digest = if (config.verifyChecksums && file.dto.sha256 != null) MessageDigest.getInstance("SHA-256") else null

            var written = 0L
            var overflowed = false
            exchange.requestBody.use { input ->
                java.security.DigestOutputStream(
                    Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                    digest,
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        written += read
                        if (declaredSize in 1..Long.MAX_VALUE && written > declaredSize) {
                            // Upstream save.rs: fail the moment written exceeds
                            // the declared size — disk-exhaustion guard.
                            overflowed = true
                            break
                        }
                        output.write(buffer, 0, read)
                        store.markProgress(sessionId, fileId, written)
                    }
                }
            }

            if (overflowed) {
                Files.deleteIfExists(target)
                store.markFailed(sessionId, fileId)
                store.maybeFinishSession(sessionId)
                return respond(exchange, 400, "Body exceeds declared size".toByteArray())
            }
            if (declaredSize in 1..Long.MAX_VALUE && written != declaredSize) {
                // EOF before declared size — upstream requires written == expected.
                Files.deleteIfExists(target)
                store.markFailed(sessionId, fileId)
                store.maybeFinishSession(sessionId)
                return respond(exchange, 400, "Truncated body".toByteArray())
            }

            // Optional checksum verification (reference: verify_checksums)
            val expectedSha256 = file.dto.sha256?.takeIf { config.verifyChecksums }
            if (digest != null && expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    Files.deleteIfExists(target)
                    // Retry window: reset to PENDING while attempts remain so
                    // the sender can retry with the same token (upstream
                    // MAX_UPLOAD_ATTEMPTS = 3), else the file FAILED.
                    val nextStatus = store.markChecksumMismatch(sessionId, fileId)
                    if (nextStatus == IncomingFileStatus.FAILED) store.maybeFinishSession(sessionId)
                    return respond(exchange, 422, "Checksum mismatch".toByteArray())
                }
            }

            store.markDone(sessionId, fileId)
            store.maybeFinishSession(sessionId)
            respond(exchange, 200, ByteArray(0)) // empty 200 ack (v2.rs:453)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(target) }
            store.markFailed(sessionId, fileId)
            store.maybeFinishSession(sessionId)
            respond(exchange, 500, "Save failed".toByteArray())
        }
    }

    private fun handleCancel(exchange: HttpExchange) {
        if (!requireMethod(exchange, LocalsendV2Route.CANCEL)) return
        val sessionId = query(exchange)["sessionId"]
        if (sessionId != null) {
            // Only the session's own sender may cancel it (v2.rs:494-503).
            val senderIp = exchange.remoteAddress.address.hostAddress
            store.removeSessionForIp(sessionId, senderIp)
        }
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
        store.createDownloadSession(sessionId, senderIp, files.keys)
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
        val query = query(exchange)
        val sessionId = query["sessionId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val fileId = query["fileId"] ?: return respond(exchange, 400, "Missing parameters".toByteArray())
        val clientIp = exchange.remoteAddress.address.hostAddress
        // Only through a PIN-gated prepare-download session bound to this IP
        // (upstream web.rs:395-427) — /download never bypasses the handshake.
        if (!store.validateDownloadSession(sessionId, clientIp, fileId)) {
            return respond(exchange, 403, "Invalid session".toByteArray())
        }
        val path = config.offeredFiles[fileId] ?: return respond(exchange, 404, "Unknown file".toByteArray())
        exchange.responseHeaders.set("Content-Type", guessMimeType(path))
        val size = Files.size(path)
        exchange.sendResponseHeaders(200, size)
        Files.newInputStream(path).use { input ->
            exchange.responseBody.use { output -> input.copyTo(output, 64 * 1024) }
        }
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
        // Internal route (mod.rs:676): bring the pickup UI forward — token
        // gated like upstream internal::show, 403 on mismatch.
        if (exchange.requestMethod.uppercase() != "POST") return respond(exchange, 405, "Method not allowed".toByteArray())
        if (query(exchange)["token"] != showToken) return respond(exchange, 403, "Invalid token".toByteArray())
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
            val decode = { raw: String ->
                runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            }
            if (idx == -1) {
                decode(it) to ""
            } else {
                decode(it.substring(0, idx)) to decode(it.substring(idx + 1))
            }
        } ?: emptyMap()

    /** Hard body cap for JSON endpoints (16 MB — notifications/wallpapers can be large, v2 transport note). */
    private fun <T : Any> parseJsonBody(exchange: HttpExchange, serializer: kotlinx.serialization.KSerializer<T>): T? =
        exchange.requestBody.use { input ->
            runCatching {
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n == -1) break
                    total += n
                    if (total > 16L * 1024 * 1024) return null
                    buffer.write(chunk, 0, n)
                }
                json.decodeFromString(serializer, buffer.toByteArray().decodeToString())
            }.getOrNull()
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
        port = boundPort ?: config.port,
        protocol = ec0.core.localsend.ProtocolType.HTTP,
        download = true,
    )
}
