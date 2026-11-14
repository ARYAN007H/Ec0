/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * LocalSend v2 receiver on Ktor CIO — the Android phone's receiver. The
 * desktop uses com.sun.net.httpserver (not available on Android); this class
 * implements the SAME 7-route contract over Ktor, sharing
 * [IncomingSessionStore] (sanitization, containment, size enforcement,
 * atomic token claims, checksum retry window) with the desktop receiver.
 *
 * Wire truths: identical to LocalsendReceiverServer.kt (reference
 * localsend-upstream packages/core/src/http/server/v2.rs, verifier-approved).
 */
package ec0.core.localsend.server

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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.origin
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class LocalsendReceiverKtor(
    val config: LocalsendReceiverConfig,
    private val store: IncomingSessionStore = IncomingSessionStore(),
    private val decide: suspend (PrepareUploadRequestDtoV2, String) -> ReceiverAcceptance =
        { _, _ -> ReceiverAcceptance.DECLINE },
    private val logger: Logger = Logger.getLogger("LocalsendReceiverKtor"),
) {
    val transferQueue: StateFlow<List<ec0.ui.models.TransferItem>> get() = store.transferQueue

    private var server: EmbeddedServer<*, *>? = null

    var boundPort: Int? = null
        private set

    fun start(port: Int = config.port) {
        val server = embeddedServer(CIO, port = port, host = "0.0.0.0") { receiverModule() }
        server.start(wait = false)
        this.server = server
        boundPort = port
        logger.info("LocalSend receiver (ktor) listening on :$port (fingerprint=${config.fingerprint.take(8)}…)")
    }

    fun stop() {
        server?.stop(500, 2000)
        server = null
    }

    private fun Application.receiverModule() {
        routing {
            post(LocalsendV2Route.REGISTER.wirePath) { handleRegister(call) }
            post(LocalsendV2Route.PREPARE_UPLOAD.wirePath) { handlePrepareUpload(call) }
            post(LocalsendV2Route.UPLOAD.wirePath) { handleUpload(call) }
            post(LocalsendV2Route.CANCEL.wirePath) { handleCancel(call) }
            post(LocalsendV2Route.PREPARE_DOWNLOAD.wirePath) { handlePrepareDownload(call) }
            get(LocalsendV2Route.DOWNLOAD.wirePath) { handleDownload(call) }
            get(LocalsendV2Route.INFO.wirePath) { handleInfo(call) }
        }
    }

    private suspend fun handleRegister(call: ApplicationCall) {
        val request = parseJsonBody(call, RegisterDtoV2.serializer())
            ?: return call.respondText("Malformed body", status = HttpStatusCode.BadRequest)
        call.respondText(
            json.encodeToString(RegisterResponseDtoV2.serializer(), RegisterResponseDtoV2(
                alias = request.alias,
                version = request.version,
                deviceModel = request.deviceModel,
                deviceType = request.deviceType,
                fingerprint = request.fingerprint,
                download = true,
            )),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    private suspend fun handlePrepareUpload(call: ApplicationCall) {
        val senderIp = call.clientIp()
        val decision = LocalsendSecurity.evaluatePin(config.requiredPin, call.query()["pin"], store.pinFailureCount(senderIp))
        when (decision) {
            PinDecision.BLOCKED_TOO_MANY_ATTEMPTS -> return call.respondText("Too many PIN attempts", status = HttpStatusCode.TooManyRequests)
            PinDecision.DENIED_WRONG_PIN -> {
                store.recordPinFailure(senderIp)
                return call.respondText("Wrong PIN", status = HttpStatusCode.Unauthorized)
            }
            PinDecision.ALLOWED -> store.clearPinFailures(senderIp)
        }

        val request = parseJsonBody(call, PrepareUploadRequestDtoV2.serializer())
            ?: return call.respondText("Malformed body", status = HttpStatusCode.BadRequest)
        if (request.files.isEmpty()) return call.respondText("No files provided", status = HttpStatusCode.BadRequest)

        if (store.hasBlockingSession()) return call.respondText("Blocked by another session", status = HttpStatusCode.Conflict)

        val sessionId = UUID.randomUUID().toString()
        store.createSession(sessionId, senderIp, request.info, request.files, config.destination, IncomingSessionStatus.DECIDING)

        when (val acceptance = decide(request, senderIp)) {
            ReceiverAcceptance.DECLINE -> {
                store.removeSession(sessionId)
                return call.respondText("Declined", status = HttpStatusCode.Forbidden)
            }
            is ReceiverAcceptance.ACCEPT -> {
                if (acceptance.fileIds.isEmpty()) {
                    store.removeSession(sessionId)
                    return call.respondText("", status = HttpStatusCode.NoContent)
                }
                store.filterToAccepted(sessionId, acceptance.fileIds)
                val files = store.findSession(sessionId)?.files ?: emptyMap()
                call.respondText(
                    json.encodeToString(
                        PrepareUploadResponseDtoV2.serializer(),
                        PrepareUploadResponseDtoV2(sessionId = sessionId, files = files.mapValues { it.value.token }),
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }
        }
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        val query = call.query()
        val sessionId = query["sessionId"] ?: return call.respondText("Missing parameters", status = HttpStatusCode.BadRequest)
        val fileId = query["fileId"] ?: return call.respondText("Missing parameters", status = HttpStatusCode.BadRequest)
        val token = query["token"] ?: return call.respondText("Missing parameters", status = HttpStatusCode.BadRequest)
        val senderIp = call.clientIp()

        val file = store.validateAndClaim(sessionId, senderIp, fileId, token)
            ?: return call.respondText("Invalid token or IP address", status = HttpStatusCode.Forbidden)

        val destination = config.destination.toAbsolutePath().normalize()
        val target = file.target.toAbsolutePath().normalize()
        if (!target.startsWith(destination)) {
            store.markFailed(sessionId, fileId)
            store.maybeFinishSession(sessionId)
            return call.respondText("Invalid file name", status = HttpStatusCode.BadRequest)
        }

        try {
            target.parent?.let { Files_createDirectories(it) }
            val declaredSize = file.dto.size
            val digest = if (config.verifyChecksums && file.dto.sha256 != null) MessageDigest.getInstance("SHA-256") else null

            var written = 0L
            var overflowed = false
            val raw = File(target.toString()).outputStream()
            val output = if (digest != null) java.security.DigestOutputStream(raw, digest) else raw
            output.use {
                val buffer = ByteArray(64 * 1024)
                val input = call.request.receiveChannel().toInputStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    written += read
                    if (declaredSize in 1..Long.MAX_VALUE && written > declaredSize) {
                        overflowed = true
                        break
                    }
                    output.write(buffer, 0, read)
                    store.markProgress(sessionId, fileId, written)
                }
            }

            if (overflowed) {
                Files_deleteIfExists(target)
                store.markFailed(sessionId, fileId)
                store.maybeFinishSession(sessionId)
                return call.respondText("Body exceeds declared size", status = HttpStatusCode.BadRequest)
            }
            if (declaredSize in 1..Long.MAX_VALUE && written != declaredSize) {
                Files_deleteIfExists(target)
                store.markFailed(sessionId, fileId)
                store.maybeFinishSession(sessionId)
                return call.respondText("Truncated body", status = HttpStatusCode.BadRequest)
            }

            val expectedSha256 = file.dto.sha256?.takeIf { config.verifyChecksums }
            if (digest != null && expectedSha256 != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    Files_deleteIfExists(target)
                    val nextStatus = store.markChecksumMismatch(sessionId, fileId)
                    if (nextStatus == IncomingFileStatus.FAILED) store.maybeFinishSession(sessionId)
                    return call.respondText("Checksum mismatch", status = HttpStatusCode.UnprocessableEntity)
                }
            }

            store.markDone(sessionId, fileId)
            store.maybeFinishSession(sessionId)
            call.respondText("", status = HttpStatusCode.OK)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "upload save failed", e)
            runCatching { Files_deleteIfExists(target) }
            store.markFailed(sessionId, fileId)
            store.maybeFinishSession(sessionId)
            call.respondText("Save failed", status = HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun handleCancel(call: ApplicationCall) {
        val sessionId = call.query()["sessionId"]
        if (sessionId != null) store.removeSessionForIp(sessionId, call.clientIp())
        call.respondText("", status = HttpStatusCode.OK)
    }

    private suspend fun handlePrepareDownload(call: ApplicationCall) {
        val query = call.query()
        val senderIp = call.clientIp()
        val decision = LocalsendSecurity.evaluatePin(config.requiredPin, query["pin"], store.pinFailureCount(senderIp))
        when (decision) {
            PinDecision.BLOCKED_TOO_MANY_ATTEMPTS -> return call.respondText("Too many PIN attempts", status = HttpStatusCode.TooManyRequests)
            PinDecision.DENIED_WRONG_PIN -> {
                store.recordPinFailure(senderIp)
                return call.respondText("Wrong PIN", status = HttpStatusCode.Unauthorized)
            }
            PinDecision.ALLOWED -> store.clearPinFailures(senderIp)
        }

        val sessionId = query["sessionId"] ?: UUID.randomUUID().toString()
        val files = config.offeredFiles.entries.associate { (fileId, path) ->
            fileId to FileDto(
                id = fileId,
                fileName = File(path.toString()).name,
                size = Files_size(path),
                fileType = guessMimeType(path.toString()),
            )
        }
        store.createDownloadSession(sessionId, senderIp, files.keys)
        call.respondText(
            json.encodeToString(
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
            ),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    private suspend fun handleDownload(call: ApplicationCall) {
        val query = call.query()
        val sessionId = query["sessionId"] ?: return call.respondText("Missing parameters", status = HttpStatusCode.BadRequest)
        val fileId = query["fileId"] ?: return call.respondText("Missing parameters", status = HttpStatusCode.BadRequest)
        val clientIp = call.clientIp()
        if (!store.validateDownloadSession(sessionId, clientIp, fileId)) {
            return call.respondText("Invalid session", status = HttpStatusCode.Forbidden)
        }
        val path = config.offeredFiles[fileId] ?: return call.respondText("Unknown file", status = HttpStatusCode.NotFound)
        val file = File(path.toString())
        call.respondBytes(
            file.readBytes(),
            ContentType.parse(guessMimeType(path.toString())),
            HttpStatusCode.OK,
        )
    }

    private suspend fun handleInfo(call: ApplicationCall) {
        call.respondText(
            json.encodeToString(
                InfoResponseDtoV2.serializer(),
                InfoResponseDtoV2(
                    alias = config.alias,
                    version = LocalsendV2.PROTOCOL_VERSION,
                    deviceModel = config.deviceModel,
                    deviceType = config.deviceType,
                    fingerprint = config.fingerprint,
                    download = true,
                ),
            ),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun ApplicationCall.clientIp(): String =
        request.origin.remoteHost ?: "unknown"

    private fun ApplicationCall.query(): Map<String, String> =
        request.uri.substringAfter('?', "").split('&').filter { it.isNotBlank() }.associate {
            val idx = it.indexOf('=')
            val decode = { raw: String ->
                runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            }
            if (idx == -1) decode(it) to "" else decode(it.substring(0, idx)) to decode(it.substring(idx + 1))
        }

    private suspend fun <T : Any> parseJsonBody(call: ApplicationCall, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching {
            val bytes = call.request.receiveChannel().toInputStream().readNBytes(MAX_BODY_BYTES.toInt() + 1)
            if (bytes.size > MAX_BODY_BYTES) return null
            json.decodeFromString(serializer, bytes.decodeToString())
        }.getOrNull()

    private fun guessMimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
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

    private val json = LocalsendV2.json

    companion object {
        /** Notifications/wallpapers can be large (≥16 MB per the transport note). */
        const val MAX_BODY_BYTES = 16L * 1024 * 1024

        // java.nio.file helpers kept as top-level fns so the file stays JVM-only
        // without importing Paths at every call site.
        fun Files_createDirectories(path: java.nio.file.Path) {
            java.nio.file.Files.createDirectories(path)
        }

        fun Files_deleteIfExists(path: java.nio.file.Path): Boolean = java.nio.file.Files.deleteIfExists(path)

        fun Files_size(path: java.nio.file.Path): Long = java.nio.file.Files.size(path)
    }
}
