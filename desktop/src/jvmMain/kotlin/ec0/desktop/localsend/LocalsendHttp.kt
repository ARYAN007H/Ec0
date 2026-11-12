package ec0.desktop.localsend

import ec0.core.localsend.LocalsendV2
import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.PrepareUploadRequestDtoV2
import ec0.core.localsend.dto.PrepareUploadResponseDtoV2
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.desktop.state.UiDevice
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Raised when prepare-upload answers 401 (wrong/missing PIN). */
class PinRequiredException(val requiredPin: Boolean) : Exception("PIN required (401)")

/** Raised when the recipient declines (403). */
class RecipientDeclinedException : Exception("Recipient declined (403)")

/** Raised when nothing was accepted (204). */
class NothingAcceptedException : Exception("No files accepted (204)")

/**
 * Minimal LocalSend v2 HTTP client over java.net (stdlib — no gradle
 * changes). Implements the route contract from core: prepare-upload →
 * upload?sessionId&fileId&token, with the status semantics verified in T5
 * (200 w/ tokens, 401 pin, 403 decline, 204 nothing-accepted).
 *
 * HTTPS peers: the announced fingerprint is the SHA-256 of the peer
 * certificate — we pin exactly that (protocol semantics, reference
 * `server_cert_verifier.rs`).
 */
class LocalsendHttp(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** prepare-upload; returns sessionId → fileId→token map (accepted only). */
    fun prepareUpload(
        peer: UiDevice,
        info: RegisterDtoV2,
        files: Map<String, FileDto>,
        pin: String?,
    ): PrepareUploadResponseDtoV2 {
        val body = LocalsendV2.json.encodeToString(
            PrepareUploadRequestDtoV2.serializer(),
            PrepareUploadRequestDtoV2(info = info, files = files),
        )
        val path = ec0.core.localsend.api.LocalsendV2Route.PREPARE_UPLOAD.path +
            (pin?.takeIf { it.isNotEmpty() }?.let { "?pin=" + urlEncode(it) } ?: "")
        val conn = open(peer, path, method = "POST")
        try {
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
            val code = conn.responseCode
            when (code) {
                200 -> {
                    val text = conn.inputStream.readBytes().decodeToString()
                    return LocalsendV2.json.decodeFromString(PrepareUploadResponseDtoV2.serializer(), text)
                }
                204 -> throw NothingAcceptedException()
                401 -> throw PinRequiredException(true)
                403 -> throw RecipientDeclinedException()
                else -> throw IllegalStateException("prepare-upload failed: HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Stream [bytes] to /upload with progress callbacks (percent 0..100). */
    fun uploadFile(
        peer: UiDevice,
        sessionId: String,
        fileId: String,
        token: String,
        fileName: String,
        bytes: InputStream,
        size: Long,
        onProgress: (Int) -> Unit,
    ) {
        val route = ec0.core.localsend.api.LocalsendV2Route.UPLOAD
        val query = "?sessionId=" + urlEncode(sessionId) +
            "&fileId=" + urlEncode(fileId) +
            "&token=" + urlEncode(token)
        val conn = open(peer, route.path + query, method = "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(size)
            conn.outputStream.use { out ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                var lastPct = -1
                while (true) {
                    val n = bytes.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    val pct = ((written * 100) / size.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress(pct)
                    }
                }
            }
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException("upload failed: HTTP $code")
            }
            // success = empty 200 (verified in T5); body intentionally ignored
        } finally {
            conn.disconnect()
        }
    }

    private fun open(peer: UiDevice, pathWithQuery: String, method: String): HttpURLConnection {
        val url = URL("${peer.wireProtocol}://${peer.hostAddress}:${peer.port}$pathWithQuery")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        if (peer.wireProtocol == "https") {
            (conn as HttpsURLConnection).apply {
                sslSocketFactory = pinnedFactory(peer.fingerprint)
                // identity is the pinned certificate, not the name
                hostnameVerifier = LENIENT_HOSTNAME
            }
        }
        return conn
    }

    private fun pinnedFactory(expectedSha256Hex: String): javax.net.ssl.SSLSocketFactory {
        val expected = expectedSha256Hex.replace(":", "").lowercase()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw java.security.cert.CertificateException("empty chain")
                val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                val hex = digest.joinToString("") { "%02x".format(it) }
                if (expected.isNotEmpty() && hex != expected) {
                    throw java.security.cert.CertificateException(
                        "fingerprint mismatch: got $hex want $expected",
                    )
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return ctx.socketFactory
    }

    companion object {
        private val LENIENT_HOSTNAME = HostnameVerifier { _, _ -> true }

        internal fun urlEncode(value: String): String =
            java.net.URLEncoder.encode(value, "UTF-8")
    }
}
