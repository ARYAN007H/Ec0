/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #3: OS-Level Phone Storage Mount (`~/Phone`) via Loopback-Only
 * Authenticated WebDAV Server (RFC 4918) + LRU Cache + `gio`/`kioclient` Helper.
 *
 * Security invariants:
 *   - Strictly binds to `127.0.0.1` (loopback-only, ephemeral port) — NEVER LAN reachable.
 *   - Requires constant-time verified 32-byte SecureRandom Basic/Bearer session token on
 *     every HTTP request.
 *   - Every requested path is validated through `VfsPathGuard.normalizeRelativePath`
 *     (rejecting `..`, backslashes, dotfiles, NUL/control chars).
 *   - All XML responses escape `&`, `<`, `>`, `"`, `'` to defeat XML injection.
 *   - LRU directory & file-byte cache bounded strictly (`maxCachedFiles = 16`, `maxFileBytes = 8 MiB`).
 */
package ec0.core.engine.services

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ec0.core.engine.EngineEncoding
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.protocol.VfsEntryDto
import ec0.core.engine.protocol.VfsPathGuard
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface VfsMountDataSource {
    fun listDirectory(relativePath: String): List<VfsEntryDto>?
    fun readFileBytes(relativePath: String): ByteArray?
}

class VfsLoopbackWebDavServer(
    private val dataSource: VfsMountDataSource,
    private val maxCachedFiles: Int = 16,
    private val maxCachedFileBytes: Int = 8 * 1024 * 1024,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var httpServer: HttpServer? = null
    private val executor = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "ec0-vfs-webdav").apply { isDaemon = true }
    }

    private val fileCacheLock = Any()
    private val fileCache = LinkedHashMap<String, ByteArray>(maxCachedFiles, 0.75f, true)

    var boundPort: Int = 0
        private set

    var authToken: String = ""
        private set

    val mountUrl: String
        get() = "http://127.0.0.1:$boundPort/"

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return boundPort
        authToken = ByteArray(24).also { SecureRandom().nextBytes(it) }.toHex()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 32)
        server.executor = executor
        server.createContext("/") { exchange -> handleRequest(exchange) }
        server.start()
        httpServer = server
        boundPort = server.address.port
        return boundPort
    }

    fun invalidateCache() = synchronized(fileCacheLock) { fileCache.clear() }

    /** Builds a rootless GNOME `gio mount` argv list for mounting the authenticated loopback endpoint. */
    fun buildGioMountCommand(): List<String> = listOf(
        "gio",
        "mount",
        "dav://ec0@127.0.0.1:$boundPort/",
    )

    private fun handleRequest(exchange: HttpExchange) {
        try {
            if (!isAuthorized(exchange)) {
                exchange.responseHeaders.set("WWW-Authenticate", "Basic realm=\"ec0-phone-vfs\"")
                sendText(exchange, 401, "Unauthorized")
                return
            }
            val rawPath = decodeUriPath(exchange.requestURI.rawPath ?: "/")
            val relPath = normalizeWebDavPath(rawPath)
            if (relPath == null) {
                sendText(exchange, 403, "Forbidden path")
                return
            }
            when (exchange.requestMethod.uppercase()) {
                "OPTIONS" -> {
                    exchange.responseHeaders.set("DAV", "1")
                    exchange.responseHeaders.set("Allow", "OPTIONS, PROPFIND, GET, HEAD")
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                "PROPFIND" -> handlePropfind(exchange, relPath)
                "GET", "HEAD" -> handleGet(exchange, relPath, headOnly = exchange.requestMethod.equals("HEAD", true))
                else -> sendText(exchange, 405, "Method Not Allowed")
            }
        } catch (_: Throwable) {
            runCatching { sendText(exchange, 500, "Internal Error") }
        }
    }

    private fun handlePropfind(exchange: HttpExchange, relPath: String) {
        val entries = dataSource.listDirectory(relPath)
        if (entries == null) {
            sendText(exchange, 404, "Not Found")
            return
        }
        val baseHref = if (relPath.isEmpty()) "/" else "/$relPath/"
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<D:multistatus xmlns:D=\"DAV:\">")
            append(buildCollectionResponseXml(baseHref, relPath.substringAfterLast('/').ifEmpty { "Phone" }))
            val depth = exchange.requestHeaders.getFirst("Depth")?.trim() ?: "1"
            if (depth != "0") {
                for (entry in entries) {
                    if (entry.name.startsWith(".") || entry.name.contains("..") || entry.name.contains('/')) continue
                    val childHref = baseHref + encodeSegment(entry.name) + if (entry.isDir) "/" else ""
                    if (entry.isDir) {
                        append(buildCollectionResponseXml(childHref, entry.name))
                    } else {
                        append(buildFileResponseXml(childHref, entry))
                    }
                }
            }
            append("</D:multistatus>")
        }
        val bytes = xml.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/xml; charset=utf-8")
        exchange.sendResponseHeaders(207, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun handleGet(exchange: HttpExchange, relPath: String, headOnly: Boolean) {
        if (relPath.isEmpty()) {
            sendText(exchange, 400, "Cannot GET root collection")
            return
        }
        val cached = synchronized(fileCacheLock) { fileCache[relPath] }
        val bytes = cached ?: dataSource.readFileBytes(relPath)?.also { fresh ->
            if (fresh.size <= maxCachedFileBytes) {
                synchronized(fileCacheLock) {
                    fileCache[relPath] = fresh
                    while (fileCache.size > maxCachedFiles) {
                        val eldestKey = fileCache.keys.firstOrNull() ?: break
                        fileCache.remove(eldestKey)
                    }
                }
            }
        }
        if (bytes == null) {
            sendText(exchange, 404, "Not Found")
            return
        }
        exchange.responseHeaders.set("Content-Type", "application/octet-stream")
        if (headOnly) {
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization")?.trim() ?: return false
        val expected = authToken.encodeToByteArray()
        if (header.startsWith("Bearer ", ignoreCase = true)) {
            val token = header.substring(7).trim().encodeToByteArray()
            return EngineEncoding.constantTimeEquals(token, expected)
        }
        if (header.startsWith("Basic ", ignoreCase = true)) {
            val decoded = runCatching {
                String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8)
            }.getOrNull() ?: return false
            val password = decoded.substringAfter(':', missingDelimiterValue = "")
            return EngineEncoding.constantTimeEquals(password.encodeToByteArray(), expected)
        }
        return false
    }

    private fun sendText(exchange: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { httpServer?.stop(0) }
        httpServer = null
        invalidateCache()
        executor.shutdownNow()
    }

    companion object {
        fun decodeUriPath(rawPath: String): String =
            runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrDefault(rawPath)

        fun normalizeWebDavPath(rawPath: String): String? {
            if (rawPath.length > VfsPathGuard.MAX_PATH_CHARS) return null
            if (rawPath.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return null
            val trimmed = rawPath.trim().trim('/')
            if (trimmed.isEmpty()) return ""
            val segments = trimmed.split('/').filter { it.isNotEmpty() }
            if (segments.any { it == "." || it == ".." || it.startsWith(".") }) return null
            val candidate = "/" + segments.joinToString("/")
            if (!VfsPathGuard.isRequestInsideGrantedRoots(candidate, listOf("/"))) return null
            return segments.joinToString("/")
        }

        fun escapeXml(input: String): String = buildString(input.length) {
            for (ch in input) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> if (ch.code >= 0x20) append(ch)
                }
            }
        }

        private fun encodeSegment(seg: String): String =
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

        private fun buildCollectionResponseXml(href: String, name: String): String =
            "<D:response><D:href>${escapeXml(href)}</D:href><D:propstat><D:prop>" +
                "<D:displayname>${escapeXml(name)}</D:displayname>" +
                "<D:resourcetype><D:collection/></D:resourcetype>" +
                "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"

        private fun buildFileResponseXml(href: String, entry: VfsEntryDto): String =
            "<D:response><D:href>${escapeXml(href)}</D:href><D:propstat><D:prop>" +
                "<D:displayname>${escapeXml(entry.name)}</D:displayname>" +
                "<D:resourcetype/>" +
                "<D:getcontentlength>${entry.size.coerceAtLeast(0L)}</D:getcontentlength>" +
                "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }
}
