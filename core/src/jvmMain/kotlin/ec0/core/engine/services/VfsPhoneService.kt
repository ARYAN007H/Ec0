/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone-side VFS responder: serves read-only directory listings (`vfs_list` ->
 * `vfs_dir`) and chunked file downloads (`vfs_download` -> `vfs_chunk`) scoped
 * strictly to explicitly granted root directories (e.g. Downloads, DCIM,
 * Pictures, Documents).
 *
 * Security invariants:
 *   1. Virtual root `/` enumerates ONLY the configured named roots (never the
 *      host filesystem root).
 *   2. Every requested path is validated lexically via [VfsPathGuard] AND
 *      canonically via [File.canonicalFile] against its resolved root so
 *      symlinks, `..`, NUL bytes, and control characters can never escape.
 *   3. Requests are rate-limited per peer via [TokenBucketRateLimiter],
 *      directory listings are capped at [MAX_LIST_ENTRIES], and downloads are
 *      capped at [maxDownloadBytes] and streamed in constant-memory 48 KiB
 *      chunks (no whole-file RAM buffering).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.VfsPathGuard
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.util.Base64

class VfsPhoneService(
    /** Map of virtual root name (e.g. "Downloads") -> backing directory on disk. */
    private val rootsProvider: () -> Map<String, File>,
    /** Gate reflecting whether storage read access is currently granted. */
    private val accessGranted: () -> Boolean = { true },
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    private val requestLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(
        capacityPerPeer = 64,
        refillPerSecond = 16,
    ),
) {
    companion object {
        const val DEFAULT_CHUNK_BYTES: Int = 48 * 1024
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 256L * 1024 * 1024
        const val MAX_LIST_ENTRIES: Int = 1_000

        private val HANDLED_REQUEST_TYPES = setOf(
            VfsProtocol.CMD_VFS_LIST,
            VfsProtocol.CMD_VFS_DOWNLOAD,
        )
    }

    /**
     * Handles incoming VFS commands from a verified [peerDeviceId].
     * Returns `true` if the envelope was a VFS request (consumed), `false` otherwise.
     */
    fun handleControlEnvelope(
        peerDeviceId: String,
        envelope: Ec0Envelope,
        sendInfo: (infoType: String, body: JsonObject) -> Unit,
    ): Boolean {
        val bodyType = envelope.bodyType ?: envelope.msgType
        if (bodyType !in HANDLED_REQUEST_TYPES) return false

        if (!requestLimiter.tryAcquire(peerDeviceId, units = 1)) {
            sendError(sendInfo, null, "VFS rate limit exceeded")
            return true
        }
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject)
        val rawPath = (body?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "/"

        if (!accessGranted()) {
            sendError(sendInfo, rawPath, "Storage access not granted on phone")
            return true
        }

        return when (bodyType) {
            VfsProtocol.CMD_VFS_LIST -> {
                handleList(rawPath, sendInfo)
                true
            }
            VfsProtocol.CMD_VFS_DOWNLOAD -> {
                handleDownload(rawPath, sendInfo)
                true
            }
            else -> false
        }
    }

    private fun handleList(rawPath: String, sendInfo: (String, JsonObject) -> Unit) {
        val roots = rootsProvider().filterValues { it.exists() && it.isDirectory }
        val normalizedVirtual = rawPath.trim().ifEmpty { "/" }

        if (normalizedVirtual == "/") {
            val reply = buildJsonObject {
                put("path", "/")
                put(
                    "entries",
                    buildJsonArray {
                        for ((name, dir) in roots.entries.sortedBy { it.key.lowercase() }) {
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    put("is_dir", true)
                                    put("size", dir.listFiles()?.size?.toLong() ?: 0L)
                                },
                            )
                        }
                    },
                )
            }
            sendInfo(VfsProtocol.INFO_VFS_DIR, reply)
            return
        }

        val resolved = resolveContainedFile(normalizedVirtual, roots)
        if (resolved == null) {
            sendError(sendInfo, rawPath, "Path outside granted roots")
            return
        }
        val (canonicalPath, file) = resolved
        if (!file.exists() || !file.isDirectory) {
            sendError(sendInfo, rawPath, "Directory not found")
            return
        }

        val children = (file.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .take(MAX_LIST_ENTRIES)

        val reply = buildJsonObject {
            put("path", canonicalPath)
            put(
                "entries",
                buildJsonArray {
                    for (child in children) {
                        add(
                            buildJsonObject {
                                put("name", child.name)
                                put("is_dir", child.isDirectory)
                                put("size", if (child.isDirectory) 0L else child.length())
                            },
                        )
                    }
                },
            )
        }
        sendInfo(VfsProtocol.INFO_VFS_DIR, reply)
    }

    private fun handleDownload(rawPath: String, sendInfo: (String, JsonObject) -> Unit) {
        val roots = rootsProvider().filterValues { it.exists() && it.isDirectory }
        val resolved = resolveContainedFile(rawPath, roots)
        if (resolved == null) {
            sendError(sendInfo, rawPath, "Path outside granted roots")
            return
        }
        val (canonicalVirtualPath, file) = resolved
        if (!file.exists() || !file.isFile) {
            sendError(sendInfo, rawPath, "File not found")
            return
        }
        val fileLen = file.length()
        if (fileLen > maxDownloadBytes) {
            sendError(sendInfo, rawPath, "File exceeds ${maxDownloadBytes / (1024 * 1024)} MB VFS transfer limit")
            return
        }

        val encoder = Base64.getEncoder()
        val buf = ByteArray(chunkSizeBytes.coerceIn(1024, 128 * 1024))
        runCatching {
            FileInputStream(file).use { input ->
                var offset = 0L
                if (fileLen == 0L) {
                    sendInfo(
                        VfsProtocol.INFO_VFS_CHUNK,
                        buildJsonObject {
                            put("path", canonicalVirtualPath)
                            put("offset", 0L)
                            put("eof", true)
                            put("buffer_b64", "")
                        },
                    )
                    return@use
                }
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    val nextOffset = offset + read
                    val isEof = nextOffset >= fileLen
                    val slice = if (read == buf.size) buf else buf.copyOf(read)
                    sendInfo(
                        VfsProtocol.INFO_VFS_CHUNK,
                        buildJsonObject {
                            put("path", canonicalVirtualPath)
                            put("offset", offset)
                            put("eof", isEof)
                            put("buffer_b64", encoder.encodeToString(slice))
                        },
                    )
                    offset = nextOffset
                    if (isEof) break
                }
            }
        }.onFailure { err ->
            sendError(sendInfo, rawPath, "Read failed: ${err.message ?: err.javaClass.simpleName}")
        }
    }

    /**
     * Maps a virtual path like `/Downloads/sub/doc.pdf` to `(normalizedVirtualPath, canonicalFile)`,
     * enforcing lexical containment via [VfsPathGuard] and canonical symlink containment via
     * [File.canonicalFile] + [VfsPathGuard.isInsideRoot].
     */
    internal fun resolveContainedFile(rawPath: String, roots: Map<String, File>): Pair<String, File>? {
        if (rawPath.isEmpty() || rawPath.length > VfsPathGuard.MAX_PATH_CHARS) return null
        if (rawPath.any { it.code < 32 || it.code == 127 || it == '\u0000' || it == '\\' }) return null

        val virtualRootPaths = roots.keys.map { "/$it" }
        val prefixed = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        if (!VfsPathGuard.isRequestInsideGrantedRoots(prefixed, virtualRootPaths)) return null

        val parts = prefixed.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null

        val rootName = parts.first()
        val rootDir = roots[rootName] ?: return null
        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrNull() ?: return null

        val relativeParts = parts.drop(1)
        val candidate = if (relativeParts.isEmpty()) {
            canonicalRoot
        } else {
            relativeParts.fold(canonicalRoot) { acc, seg -> File(acc, seg) }
        }
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null

        if (!VfsPathGuard.isInsideRoot(canonicalRoot.path, canonicalCandidate.path)) {
            return null
        }
        val normalizedVirtual = "/" + parts.joinToString("/")
        return normalizedVirtual to canonicalCandidate
    }

    private fun sendError(sendInfo: (String, JsonObject) -> Unit, path: String?, message: String) {
        sendInfo(
            VfsProtocol.INFO_VFS_ERROR,
            buildJsonObject {
                if (path != null) put("path", path)
                put("message", message)
            },
        )
    }
}
