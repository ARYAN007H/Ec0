/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Session state + transfer-queue surfacing for the LocalSend receiver.
 * One active/pending session at a time — prepare-upload answers 409 while a
 * session exists (reference server/v2.rs:262-269 "Blocked by another
 * session"). File tokens are plain UUIDs verified by string equality +
 * single-use claim (v2.rs:326/:389-404 — the claim must be atomic, see
 * [validateAndClaim]). A session finishes when no file is PENDING/IN_PROGRESS
 * (upstream UploadSessionV2::is_complete: DONE or FAILED both count), so a
 * failed/interrupted file can never wedge the slot.
 *
 * Untrusted `fileName` handling mirrors upstream packages/core/src/util/
 * filename.rs: collapse to the last segment, strip control chars, NFC
 * normalize, cap 255 bytes, replace Windows-illegal chars and reserved
 * stems — an attacker-controlled name must never escape [destination].
 */
package ec0.core.localsend.server

import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.ui.models.TransferDirection
import ec0.ui.models.TransferItem
import ec0.ui.models.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path
import java.text.Normalizer
import java.util.UUID

enum class IncomingFileStatus { PENDING, IN_PROGRESS, DONE, FAILED }

data class IncomingFile(
    val fileId: String,
    val dto: FileDto,
    val token: String,
    val status: IncomingFileStatus = IncomingFileStatus.PENDING,
    val target: Path,
    val bytesWritten: Long = 0,
    /** Upload attempts consumed (checksum mismatch allows retries, upstream MAX_UPLOAD_ATTEMPTS = 3). */
    val attempts: Int = 0,
)

enum class IncomingSessionStatus { DECIDING, PENDING, FINISHED }

data class IncomingSession(
    val sessionId: String,
    val senderIp: String,
    val sender: RegisterDtoV2,
    val files: Map<String, IncomingFile>,
    val status: IncomingSessionStatus,
    val startedAt: Long = System.currentTimeMillis(),
)

object FileNameSanitizer {
    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
    private val WINDOWS_ILLEGAL = Regex("[<>:\"/\\\\|?*]")
    private val RESERVED_STEMS = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /**
     * Collapse a peer-supplied name to a safe single path segment
     * (reference filename.rs: "../../etc/passwd" → "passwd",
     * "C:\Windows\evil.exe" → "evil.exe").
     */
    fun sanitize(fileName: String): String {
        var name = fileName.substringAfterLast('/').substringAfterLast('\\')
        name = Normalizer.normalize(name, Normalizer.Form.NFC)
        name = CONTROL_CHARS.replace(name, "")
        name = WINDOWS_ILLEGAL.replace(name, "_").trim().trimEnd('.')
        if (name.isEmpty() || name == "." || name == "..") name = "untitled"
        val stem = name.substringBefore('.', missingDelimiterValue = "")
        if (stem.uppercase() in RESERVED_STEMS) name = "_$name"
        // Cap at 255 UTF-8 bytes without splitting a surrogate pair.
        if (name.encodeToByteArray().size > 255) {
            val sb = StringBuilder()
            var size = 0
            for (c in name) {
                val cSize = c.toString().encodeToByteArray().size
                if (size + cSize > 255) break
                sb.append(c)
                size += cSize
            }
            name = sb.toString()
        }
        return name.ifBlank { "untitled" }
    }
}

/**
 * Thread-safe store of the receiver's transfer state, surfaced for the UI as
 * a [StateFlow] of [TransferItem]s — one item per incoming file, INCOMING
 * direction. Also owns PIN rate-limit counters and (read-only) download
 * sessions for the /download route.
 */
class IncomingSessionStore {

    private val _sessions = MutableStateFlow<IncomingSession?>(null)
    val sessions: StateFlow<IncomingSession?> = _sessions.asStateFlow()

    private val _queue = MutableStateFlow<List<TransferItem>>(emptyList())
    val transferQueue: StateFlow<List<TransferItem>> = _queue.asStateFlow()

    /** PIN failure counter per source IP (reference: LruCache<IpAddr, u32>, cap 200). */
    private val pinFailures = object : LinkedHashMap<String, Int>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean = size > 200
    }

    /** Accepted download sessions: sessionId → (clientIp, fileIds). Created only by PIN-gated prepare-download. */
    private val downloadSessions = mutableMapOf<String, Pair<String, Set<String>>>()

    @Synchronized
    fun pinFailureCount(ip: String): Int = pinFailures[ip] ?: 0

    @Synchronized
    fun recordPinFailure(ip: String) {
        pinFailures[ip] = (pinFailures[ip] ?: 0) + 1
    }

    @Synchronized
    fun clearPinFailures(ip: String) {
        pinFailures.remove(ip)
    }

    @Synchronized
    fun hasBlockingSession(): Boolean = _sessions.value?.status.let {
        it == IncomingSessionStatus.DECIDING || it == IncomingSessionStatus.PENDING
    }

    /**
     * Creates the session (optionally in [IncomingSessionStatus.DECIDING] while
     * the user is asked) — upstream claims the slot BEFORE prompting so a
     * decision window cannot be raced by a second sender.
     */
    @Synchronized
    fun createSession(
        sessionId: String,
        senderIp: String,
        sender: RegisterDtoV2,
        files: Map<String, FileDto>,
        destination: Path,
        status: IncomingSessionStatus,
    ): IncomingSession {
        val session = IncomingSession(
            sessionId = sessionId,
            senderIp = senderIp,
            sender = sender,
            status = status,
            files = files.entries.associate { (fileId, dto) ->
                fileId to IncomingFile(
                    fileId = fileId,
                    dto = dto,
                    token = UUID.randomUUID().toString(),
                    target = destination.resolve(FileNameSanitizer.sanitize(dto.fileName)),
                )
            },
        )
        _sessions.value = session
        publishQueue(session)
        return session
    }

    /** Narrows the session to the accepted file ids (the ASK decision). */
    @Synchronized
    fun filterToAccepted(sessionId: String, accepted: Set<String>): Map<String, IncomingFile> {
        val session = _sessions.value?.takeIf { it.sessionId == sessionId } ?: return emptyMap()
        val next = session.copy(
            status = IncomingSessionStatus.PENDING,
            files = session.files.filterKeys { it in accepted },
        )
        _sessions.value = next
        publishQueue(next)
        return next.files
    }

    @Synchronized
    fun removeSession(sessionId: String) {
        _sessions.update { current -> current?.takeIf { it.sessionId != sessionId } }
        publishQueue(current = _sessions.value)
    }

    /**
     * Cancel is only honored for the session's own sender IP (v2.rs:494-503);
     * unknown pairs still fall through to the empty-200 contract.
     */
    @Synchronized
    fun removeSessionForIp(sessionId: String, senderIp: String): Boolean {
        val session = _sessions.value?.takeIf { it.sessionId == sessionId && it.senderIp == senderIp }
        if (session != null) {
            _sessions.value = null
            publishQueue(null)
        }
        return session != null
    }

    @Synchronized
    fun findSession(sessionId: String): IncomingSession? = _sessions.value?.takeIf { it.sessionId == sessionId }

    /**
     * Atomic single-use claim (upstream claims validate + status transition in
     * ONE session-lock block, v2.rs:389-404 — two separate locks would let two
     * concurrent uploads with the same token interleave writes).
     */
    @Synchronized
    fun validateAndClaim(sessionId: String, senderIp: String, fileId: String, token: String): IncomingFile? {
        val session = _sessions.value ?: return null
        if (session.sessionId != sessionId || session.senderIp != senderIp) return null
        val file = session.files[fileId] ?: return null
        if (file.token != token || file.status != IncomingFileStatus.PENDING) return null
        val claimed = file.copy(status = IncomingFileStatus.IN_PROGRESS)
        _sessions.value = session.copy(files = session.files + (fileId to claimed))
        publishQueue(_sessions.value)
        return claimed
    }

    @Synchronized
    fun markProgress(sessionId: String, fileId: String, bytesWritten: Long): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.IN_PROGRESS, bytesWritten = bytesWritten) } != null

    @Synchronized
    fun markDone(sessionId: String, fileId: String): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.DONE) } != null

    /**
     * Checksum-mismatch path (v2.rs:632-636): reset to PENDING while attempts
     * remain (upstream MAX_UPLOAD_ATTEMPTS = 3) so the sender can retry with
     * the same token; FAILED once exhausted. Returns the new status.
     */
    @Synchronized
    fun markChecksumMismatch(sessionId: String, fileId: String): IncomingFileStatus {
        val session = _sessions.value?.takeIf { it.sessionId == sessionId } ?: return IncomingFileStatus.FAILED
        val file = session.files[fileId] ?: return IncomingFileStatus.FAILED
        val attempts = file.attempts + 1
        val next = if (attempts >= 3) IncomingFileStatus.FAILED else IncomingFileStatus.PENDING
        _sessions.value = session.copy(files = session.files + (fileId to file.copy(attempts = attempts, status = next)))
        publishQueue(_sessions.value)
        return next
    }

    @Synchronized
    fun markFailed(sessionId: String, fileId: String): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.FAILED) } != null

    /**
     * Finishes the session when no file is left PENDING/IN_PROGRESS (upstream
     * UploadSessionV2::is_complete — DONE **or FAILED** count), so a failed
     * file can never wedge the single slot.
     */
    @Synchronized
    fun maybeFinishSession(sessionId: String) {
        val session = _sessions.value?.takeIf { it.sessionId == sessionId } ?: return
        val active = session.files.values.any {
            it.status == IncomingFileStatus.PENDING || it.status == IncomingFileStatus.IN_PROGRESS
        }
        if (!active && session.files.isNotEmpty()) {
            _sessions.value = null
            publishQueue(null)
        }
    }

    /** Download-session bookkeeping for /download (upstream web.rs:395-427). */
    @Synchronized
    fun createDownloadSession(sessionId: String, clientIp: String, fileIds: Set<String>) {
        downloadSessions[sessionId] = clientIp to fileIds
    }

    @Synchronized
    fun validateDownloadSession(sessionId: String, clientIp: String, fileId: String): Boolean =
        downloadSessions[sessionId]?.let { (ip, ids) -> ip == clientIp && fileId in ids } == true

    private fun mutateFile(sessionId: String, fileId: String, transform: (IncomingFile) -> IncomingFile): IncomingFile? {
        var changed: IncomingFile? = null
        mutate(sessionId) { session ->
            session.copy(files = session.files.mapValues { (id, file) ->
                if (id == fileId) transform(file).also { changed = it } else file
            })
        }
        return changed
    }

    private fun mutate(sessionId: String, transform: (IncomingSession) -> IncomingSession): IncomingSession? {
        val next = _sessions.value?.takeIf { it.sessionId == sessionId }?.let(transform) ?: return null
        _sessions.value = next
        publishQueue(next)
        return next
    }

    private fun publishQueue(current: IncomingSession?) {
        val items = current?.files?.values?.map { file ->
            TransferItem(
                id = "${current.sessionId}:${file.fileId}",
                peerAlias = current.sender.alias,
                fileName = file.dto.fileName,
                sizeBytes = file.dto.size,
                direction = TransferDirection.INCOMING,
                state = when (file.status) {
                    IncomingFileStatus.PENDING -> TransferState.PENDING
                    IncomingFileStatus.IN_PROGRESS -> TransferState.RUNNING
                    IncomingFileStatus.DONE -> TransferState.DONE
                    IncomingFileStatus.FAILED -> TransferState.FAILED
                },
                progressPct = if (file.dto.size > 0) ((file.bytesWritten * 100) / file.dto.size).toInt().coerceIn(0, 100) else 0,
            )
        } ?: emptyList()
        _queue.value = items
    }
}
