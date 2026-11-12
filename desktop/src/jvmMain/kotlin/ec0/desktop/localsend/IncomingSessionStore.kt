/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Session state + transfer-queue surfacing for the LocalSend receiver.
 * One active/pending session at a time — prepare-upload answers 409 while a
 * session exists (reference server/v2.rs:262-269 "Blocked by another
 * session"). File tokens are plain UUIDs verified by string equality +
 * pending state (server/v2.rs:326/:400; see LocalsendSecurity.kt for why
 * they are NOT crypto tokens).
 */
package ec0.desktop.localsend

import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.desktop.state.TransferDirection
import ec0.desktop.state.TransferItem
import ec0.desktop.state.TransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path
import java.util.UUID

enum class IncomingFileStatus { PENDING, IN_PROGRESS, DONE, FAILED }

data class IncomingFile(
    val fileId: String,
    val dto: FileDto,
    val token: String,
    val status: IncomingFileStatus = IncomingFileStatus.PENDING,
    val target: Path,
    val bytesWritten: Long = 0,
)

enum class IncomingSessionStatus { PENDING, ACTIVE, FINISHED, CANCELLED }

data class IncomingSession(
    val sessionId: String,
    val senderIp: String,
    val sender: RegisterDtoV2,
    val files: Map<String, IncomingFile>,
    val status: IncomingSessionStatus,
    val startedAt: Long = System.currentTimeMillis(),
)

/**
 * Thread-safe store of the receiver's transfer state, surfaced for the UI as
 * a [StateFlow] of [TransferItem]s (agent-1's transfer-queue model) — one
 * item per incoming file, INCOMING direction.
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
    fun hasBlockingSession(): Boolean = _sessions.value?.status.let { it == IncomingSessionStatus.PENDING || it == IncomingSessionStatus.ACTIVE }

    @Synchronized
    fun createPendingSession(sessionId: String, senderIp: String, sender: RegisterDtoV2, accepted: Map<String, FileDto>, destination: Path): IncomingSession {
        val session = IncomingSession(
            sessionId = sessionId,
            senderIp = senderIp,
            sender = sender,
            status = IncomingSessionStatus.PENDING,
            files = accepted.entries.associate { (fileId, dto) ->
                fileId to IncomingFile(
                    fileId = fileId,
                    dto = dto,
                    token = UUID.randomUUID().toString(),
                    target = destination.resolve(dto.fileName),
                )
            },
        )
        _sessions.value = session
        publishQueue(session)
        return session
    }

    @Synchronized
    fun activate(sessionId: String): IncomingSession? = mutate(sessionId) { it.copy(status = IncomingSessionStatus.ACTIVE) }

    @Synchronized
    fun markInProgress(sessionId: String, fileId: String): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.IN_PROGRESS) } != null

    @Synchronized
    fun markProgress(sessionId: String, fileId: String, bytesWritten: Long): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.IN_PROGRESS, bytesWritten = bytesWritten) } != null

    @Synchronized
    fun markDone(sessionId: String, fileId: String): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.DONE) } != null

    @Synchronized
    fun markFailed(sessionId: String, fileId: String): Boolean =
        mutateFile(sessionId, fileId) { it.copy(status = IncomingFileStatus.FAILED) } != null

    @Synchronized
    fun markSessionFinished(sessionId: String) {
        mutate(sessionId) { it.copy(status = IncomingSessionStatus.FINISHED) }
    }

    @Synchronized
    fun removeSession(sessionId: String) {
        _sessions.update { current -> current?.takeIf { it.sessionId == sessionId } }
        publishQueue(current = _sessions.value)
    }

    @Synchronized
    fun findSession(sessionId: String): IncomingSession? = _sessions.value?.takeIf { it.sessionId == sessionId }

    /**
     * Validates an upload attempt against the reference rules (v2.rs:383-402):
     * session ACTIVE + same sender IP + file exists + token string-equal +
     * file still PENDING.
     */
    @Synchronized
    fun validateUpload(sessionId: String, senderIp: String, fileId: String, token: String): IncomingFile? {
        val session = _sessions.value ?: return null
        if (session.sessionId != sessionId || session.senderIp != senderIp) return null
        val file = session.files[fileId] ?: return null
        if (file.token != token || file.status != IncomingFileStatus.PENDING) return null
        return file
    }

    private fun mutate(sessionId: String, transform: (IncomingSession) -> IncomingSession): IncomingSession? {
        val next = _sessions.value?.takeIf { it.sessionId == sessionId }?.let(transform) ?: return null
        _sessions.value = next
        publishQueue(next)
        return next
    }

    private fun mutateFile(sessionId: String, fileId: String, transform: (IncomingFile) -> IncomingFile): IncomingFile? {
        var changed: IncomingFile? = null
        mutate(sessionId) { session ->
            session.copy(files = session.files.mapValues { (id, file) ->
                if (id == fileId) transform(file).also { changed = it } else file
            })
        }
        return changed
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
