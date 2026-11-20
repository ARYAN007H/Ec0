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
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.VfsPathGuard
import ec0.core.engine.protocol.VfsProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class VfsPhoneService(
    /** Map of virtual root name (e.g. "Downloads") -> backing directory on disk. */
    private val rootsProvider: () -> Map<String, File>,
    /** Gate reflecting whether storage read access is currently granted. */
    private val accessGranted: () -> Boolean = { true },
    /** Gate reflecting whether the user explicitly enabled desktop-to-phone VFS uploads. */
    private val allowUploads: () -> Boolean = { false },
    /** Optional bounded thumbnail generator for image entries (returns <= 12 KiB JPEG bytes). */
    private val thumbnailProvider: (File) -> ByteArray? = { null },
    private val chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
    private val maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    private val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
    private val requestLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(
        capacityPerPeer = 64,
        refillPerSecond = 16,
    ),
) {
    companion object {
        const val DEFAULT_CHUNK_BYTES: Int = 48 * 1024
        const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 256L * 1024 * 1024
        const val DEFAULT_MAX_UPLOAD_BYTES: Long = 64L * 1024 * 1024
        const val MAX_LIST_ENTRIES: Int = 1_000
        const val MAX_THUMBNAILS_PER_LISTING: Int = 24
        const val MAX_THUMBNAIL_BYTES: Int = 12 * 1024
        const val MAX_ACTIVE_UPLOADS: Int = 4

        private val HANDLED_REQUEST_TYPES = setOf(
            VfsProtocol.CMD_VFS_LIST,
            VfsProtocol.CMD_VFS_DOWNLOAD,
            VfsProtocol.CMD_VFS_UPLOAD,
        )

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")

        fun inferMimeHint(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4", "mkv", "mov", "webm" -> "video/mp4"
            "mp3", "m4a", "flac", "ogg", "wav" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "txt", "md", "log", "json", "csv" -> "text/plain"
            "zip", "tar", "gz", "7z" -> "application/zip"
            else -> null
        }

        fun isImageFileName(fileName: String): Boolean =
            fileName.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
    }

    private data class ActiveUpload(
        val canonicalVirtualPath: String,
        val targetFile: File,
        val tempFile: File,
        var bytesWritten: Long = 0L,
    )

    private val activeUploads = ConcurrentHashMap<String, ActiveUpload>()
    private val uploadLock = Any()

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
            VfsProtocol.CMD_VFS_UPLOAD -> {
                handleUpload(peerDeviceId, rawPath, body, sendInfo)
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

        val rootName = canonicalPath.removePrefix("/").substringBefore('/')
        val canonicalRoot = roots[rootName]?.let { runCatching { it.canonicalFile }.getOrNull() }
        val children = (file.listFiles() ?: emptyArray())
            .filter { child ->
                if (child.name.startsWith(".")) return@filter false
                // Exclude any symlink whose canonical target escapes the granted root.
                if (canonicalRoot != null) {
                    val childCanon = runCatching { child.canonicalFile }.getOrNull() ?: return@filter false
                    if (!VfsPathGuard.isInsideRoot(canonicalRoot.path, childCanon.path)) return@filter false
                }
                true
            }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { if (it.isDirectory) 0L else it.lastModified() }.thenBy { it.name.lowercase() })
            .take(MAX_LIST_ENTRIES)

        val encoder = Base64.getEncoder()
        var thumbnailsEmitted = 0

        val reply = buildJsonObject {
            put("path", canonicalPath)
            put(
                "entries",
                buildJsonArray {
                    for (child in children) {
                        val mimeHint = if (child.isDirectory) null else inferMimeHint(child.name)
                        var thumbB64: String? = null
                        if (!child.isDirectory &&
                            thumbnailsEmitted < MAX_THUMBNAILS_PER_LISTING &&
                            isImageFileName(child.name)
                        ) {
                            val rawThumb = runCatching { thumbnailProvider(child) }.getOrNull()
                            if (rawThumb != null && rawThumb.isNotEmpty() && rawThumb.size <= MAX_THUMBNAIL_BYTES) {
                                val encoded = encoder.encodeToString(rawThumb)
                                if (encoded.length <= VfsProtocol.MAX_THUMB_B64_CHARS) {
                                    thumbB64 = encoded
                                    thumbnailsEmitted++
                                }
                            }
                        }
                        add(
                            buildJsonObject {
                                put("name", child.name)
                                put("is_dir", child.isDirectory)
                                put("size", if (child.isDirectory) 0L else child.length())
                                if (mimeHint != null) put("mime_hint", mimeHint)
                                if (thumbB64 != null) put("thumb_b64", thumbB64)
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
                    if (nextOffset > maxDownloadBytes) {
                        sendError(
                            sendInfo,
                            rawPath,
                            "File exceeds ${maxDownloadBytes / (1024 * 1024)} MB VFS transfer limit",
                        )
                        return@use
                    }
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
        }.onFailure {
            // Never leak raw OS filesystem paths from exception messages over the wire.
            sendError(sendInfo, rawPath, "File read failed")
        }
    }

    private fun handleUpload(
        peerDeviceId: String,
        rawPath: String,
        body: JsonObject?,
        sendInfo: (String, JsonObject) -> Unit,
    ) {
        if (!allowUploads()) {
            sendError(sendInfo, rawPath, "File uploads to phone are disabled")
            return
        }
        val roots = rootsProvider().filterValues { it.exists() && it.isDirectory }
        val resolved = resolveContainedFileForWrite(rawPath, roots)
        if (resolved == null) {
            sendError(sendInfo, rawPath, "Upload path outside granted roots or invalid")
            return
        }
        val (canonicalVirtualPath, targetFile) = resolved
        val offset = (body?.get("offset") as? JsonPrimitive)?.longOrNull ?: -1L
        val eof = (body?.get("eof") as? JsonPrimitive)?.booleanOrNull ?: false
        val bufferB64 = (body?.get("buffer_b64") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""

        if (offset < 0L || offset > maxUploadBytes) {
            sendError(sendInfo, rawPath, "Invalid upload offset")
            return
        }
        val decoded = if (bufferB64.isEmpty()) {
            ByteArray(0)
        } else {
            runCatching { Base64.getDecoder().decode(bufferB64) }.getOrNull() ?: run {
                sendError(sendInfo, rawPath, "Malformed base64 chunk")
                return
            }
        }
        if (decoded.size > chunkSizeBytes * 2) {
            sendError(sendInfo, rawPath, "Upload chunk exceeds maximum frame size")
            return
        }

        val uploadKey = "${peerDeviceId.lowercase()}|$canonicalVirtualPath"
        synchronized(uploadLock) {
            val state = if (offset == 0L) {
                activeUploads.remove(uploadKey)?.let { runCatching { it.tempFile.delete() } }
                if (activeUploads.size >= MAX_ACTIVE_UPLOADS) {
                    sendError(sendInfo, rawPath, "Too many concurrent uploads")
                    return
                }
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(uploadKey.encodeToByteArray())
                    .toHex()
                    .take(16)
                val temp = File(targetFile.parentFile, ".ec0up-$hash.tmp")
                runCatching { temp.delete() }
                ActiveUpload(
                    canonicalVirtualPath = canonicalVirtualPath,
                    targetFile = targetFile,
                    tempFile = temp,
                    bytesWritten = 0L,
                ).also { activeUploads[uploadKey] = it }
            } else {
                activeUploads[uploadKey] ?: run {
                    sendError(sendInfo, rawPath, "Unexpected upload offset without initial chunk")
                    return
                }
            }

            if (offset != state.bytesWritten) {
                activeUploads.remove(uploadKey)
                runCatching { state.tempFile.delete() }
                sendError(sendInfo, rawPath, "Out-of-order upload chunk")
                return
            }
            if (state.bytesWritten + decoded.size.toLong() > maxUploadBytes) {
                activeUploads.remove(uploadKey)
                runCatching { state.tempFile.delete() }
                sendError(sendInfo, rawPath, "Upload exceeds ${maxUploadBytes / (1024 * 1024)} MB limit")
                return
            }

            try {
                FileOutputStream(state.tempFile, offset > 0L).use { out ->
                    if (decoded.isNotEmpty()) {
                        out.write(decoded)
                    }
                    out.flush()
                }
                state.bytesWritten += decoded.size.toLong()
                if (eof) {
                    activeUploads.remove(uploadKey)
                    // Re-verify target is not a symlink right before rename.
                    if (Files.isSymbolicLink(state.targetFile.toPath()) || state.targetFile.isDirectory) {
                        runCatching { state.tempFile.delete() }
                        sendError(sendInfo, rawPath, "Upload target cannot be a directory or symlink")
                        return
                    }
                    try {
                        Files.move(
                            state.tempFile.toPath(),
                            state.targetFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (_: Exception) {
                        Files.move(
                            state.tempFile.toPath(),
                            state.targetFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    sendInfo(
                        VfsProtocol.INFO_VFS_UPLOAD_ACK,
                        buildJsonObject {
                            put("path", canonicalVirtualPath)
                            put("bytes", state.bytesWritten)
                        },
                    )
                }
            } catch (_: Exception) {
                activeUploads.remove(uploadKey)
                runCatching { state.tempFile.delete() }
                sendError(sendInfo, rawPath, "Upload write failed")
            }
        }
    }

    /**
     * Strict path containment for write/upload targets:
     *   - Requires at least 2 segments (`/<root>/<file>`).
     *   - Rejects dotfiles, `..`, backslashes, NUL/control chars.
     *   - Parent directory MUST already exist inside the canonical granted root.
     *   - Existing target MUST NOT be a directory or symbolic link, and its canonical
     *     path MUST remain inside the granted root.
     */
    internal fun resolveContainedFileForWrite(rawPath: String, roots: Map<String, File>): Pair<String, File>? {
        if (rawPath.isEmpty() || rawPath.length > VfsPathGuard.MAX_PATH_CHARS) return null
        if (rawPath.any { it.code < 32 || it.code == 127 || it == '\u0000' || it == '\\' }) return null

        val virtualRootPaths = roots.keys.map { "/$it" }
        val prefixed = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        if (!VfsPathGuard.isRequestInsideGrantedRoots(prefixed, virtualRootPaths)) return null

        val parts = prefixed.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.size < 2 || parts.any { it == ".." }) return null

        val rootName = parts.first()
        val rootDir = roots[rootName] ?: return null
        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrNull() ?: return null

        val relativeParts = parts.drop(1)
        if (relativeParts.any { it.startsWith(".") || it.isBlank() }) return null

        val parentDir = relativeParts.dropLast(1).fold(canonicalRoot) { acc, seg -> File(acc, seg) }
        if (!parentDir.exists() || !parentDir.isDirectory || Files.isSymbolicLink(parentDir.toPath())) return null
        val canonicalParent = runCatching { parentDir.canonicalFile }.getOrNull() ?: return null
        if (!VfsPathGuard.isInsideRoot(canonicalRoot.path, canonicalParent.path)) return null

        val fileName = relativeParts.last()
        val candidate = File(canonicalParent, fileName)
        if (Files.isSymbolicLink(candidate.toPath()) || candidate.isDirectory) return null
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (!VfsPathGuard.isInsideRoot(canonicalRoot.path, canonicalCandidate.path)) return null

        val normalizedVirtual = "/" + parts.joinToString("/")
        return normalizedVirtual to canonicalCandidate
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
        // Reject hidden/dotfile path segments (e.g. .nomedia, .thumbnails, .secrets)
        // consistently with handleList() so direct downloads cannot bypass directory hiding.
        if (relativeParts.any { it.startsWith(".") }) return null
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
