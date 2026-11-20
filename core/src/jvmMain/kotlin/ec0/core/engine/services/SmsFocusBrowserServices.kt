/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Features #5, #6 & #7:
 *   - Feature #5: SmsInboxService (bounded persistent thread store + rate-limited send)
 *     and OtpAutoTypeController (digits-only, 60s TTL, one-shot auto-type & immediate scrub).
 *   - Feature #6: FocusSyncService (bidirectional DND/Work/Meeting/Sleep Focus sync) +
 *     PipeWireMeetingDetector (auto-enables Meeting Focus when mic/camera goes active).
 *   - Feature #7: BrowserTabHandoffService (passive active tab + video timestamp handoff
 *     with safe URL timestamp rewriting).
 */
package ec0.core.engine.services

import ec0.core.engine.Ec0Envelope
import ec0.core.engine.TokenBucketRateLimiter
import ec0.core.engine.protocol.BrowserHandoffProtocol
import ec0.core.engine.protocol.BrowserTabHandoffInfo
import ec0.core.engine.protocol.FocusStateInfo
import ec0.core.engine.protocol.FocusSyncProtocol
import ec0.core.engine.protocol.HandoffProtocol
import ec0.core.engine.protocol.SmsInboxProtocol
import ec0.core.engine.protocol.SmsMessageRecord
import ec0.core.engine.protocol.SmsSendCommand
import ec0.core.engine.transport.Ec0Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// ---------------------------------------------------------------------------
// Feature #5 — Persistent SMS/MMS Inbox & Global 2FA OTP Auto-Type
// ---------------------------------------------------------------------------

data class SmsThreadUiModel(
    val threadId: String,
    val address: String,
    val contactName: String,
    val latestBody: String,
    val latestTimestampMs: Long,
    val unreadCount: Int,
    val simSlot: Int,
    val messages: List<SmsMessageRecord>,
)

class SmsInboxService(
    private val onRemoteSmsSendRequested: (SmsSendCommand) -> Boolean = { false },
    private val onRemoteSmsSyncRequested: () -> List<SmsMessageRecord> = { emptyList() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private val sendLimiter = TokenBucketRateLimiter(capacityPerPeer = 10L, refillPerSecond = 1L, clockMs = clockMs)
    private val messagesByThread = LinkedHashMap<String, MutableList<SmsMessageRecord>>()

    private val _threads = MutableStateFlow<List<SmsThreadUiModel>>(emptyList())
    val threads: StateFlow<List<SmsThreadUiModel>> = _threads.asStateFlow()

    fun requestSync(session: Ec0Session?, limit: Int = 50) {
        val clamped = limit.coerceIn(1, SmsInboxProtocol.MAX_THREADS_PER_SYNC)
        session?.sendCmd(
            cmdType = SmsInboxProtocol.CMD_SMS_SYNC_REQ,
            cmdBody = buildJsonObject { put("limit", clamped) },
            category = SmsInboxProtocol.CATEGORY_SMS,
            timestampMs = clockMs(),
        )
    }

    fun sendSms(
        recipientAddress: String,
        body: String,
        simSlot: Int = 0,
        session: Ec0Session?,
    ): SmsSendCommand {
        val cmd = SmsSendCommand(
            clientRequestId = "sms_${clockMs()}",
            recipientAddress = recipientAddress,
            body = body,
            simSlot = simSlot,
        )
        session?.sendCmd(
            cmdType = SmsInboxProtocol.CMD_SMS_SEND,
            cmdBody = cmd.toJsonObject(),
            category = SmsInboxProtocol.CATEGORY_SMS,
            timestampMs = clockMs(),
        )
        return cmd
    }

    fun publishThreads(records: List<SmsMessageRecord>, session: Ec0Session?) {
        val bounded = records.take(SmsInboxProtocol.MAX_THREADS_PER_SYNC * 4)
        ingestMessages(bounded)
        session?.sendInfo(
            infoType = SmsInboxProtocol.INFO_SMS_THREADS,
            infoBody = buildJsonObject {
                put(
                    "messages",
                    buildJsonArray {
                        for (r in bounded) add(r.toJsonObject())
                    },
                )
            },
            category = SmsInboxProtocol.CATEGORY_SMS,
            timestampMs = clockMs(),
        )
    }

    fun ingestMessages(records: List<SmsMessageRecord>) = lock.withLock {
        for (rec in records) {
            val list = messagesByThread.getOrPut(rec.threadId) { mutableListOf() }
            if (list.none { it.messageId == rec.messageId }) {
                list.add(rec)
                list.sortBy { it.timestampMs }
                if (list.size > SmsInboxProtocol.MAX_MESSAGES_PER_THREAD) {
                    list.subList(0, list.size - SmsInboxProtocol.MAX_MESSAGES_PER_THREAD).clear()
                }
            }
        }
        while (messagesByThread.size > SmsInboxProtocol.MAX_THREADS_PER_SYNC) {
            val oldestKey = messagesByThread.entries.minByOrNull { e ->
                e.value.maxOfOrNull { it.timestampMs } ?: 0L
            }?.key ?: break
            messagesByThread.remove(oldestKey)
        }
        _threads.value = messagesByThread.values.map { msgs ->
            val last = msgs.last()
            SmsThreadUiModel(
                threadId = last.threadId,
                address = last.address,
                contactName = last.contactName,
                latestBody = last.body,
                latestTimestampMs = last.timestampMs,
                unreadCount = msgs.count { it.incoming && !it.read },
                simSlot = last.simSlot,
                messages = msgs.toList(),
            )
        }.sortedByDescending { it.latestTimestampMs }
    }

    fun handleControlEnvelope(peerDeviceId: String, envelope: Ec0Envelope, session: Ec0Session? = null): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            SmsInboxProtocol.INFO_SMS_THREADS -> {
                val arr = body["messages"] as? JsonArray ?: return true
                val parsed = arr.take(250).mapNotNull { el ->
                    (el as? JsonObject)?.let { runCatching { SmsMessageRecord.fromJsonObject(it) }.getOrNull() }
                }
                ingestMessages(parsed)
                true
            }
            SmsInboxProtocol.CMD_SMS_SYNC_REQ -> {
                val localRecords = runCatching { onRemoteSmsSyncRequested() }.getOrDefault(emptyList())
                publishThreads(localRecords, session)
                true
            }
            SmsInboxProtocol.CMD_SMS_SEND -> {
                if (!sendLimiter.tryAcquire(peerDeviceId)) return true
                val cmd = SmsSendCommand.fromJsonObject(body)
                val ok = runCatching { onRemoteSmsSendRequested(cmd) }.getOrDefault(false)
                session?.sendInfo(
                    infoType = SmsInboxProtocol.INFO_SMS_SEND_RESULT,
                    infoBody = buildJsonObject {
                        put("ok", ok)
                        put("req_id", cmd.clientRequestId)
                    },
                    category = SmsInboxProtocol.CATEGORY_SMS,
                    timestampMs = clockMs(),
                )
                true
            }
            else -> false
        }
    }
}

/**
 * Holds the freshest extracted 2FA OTP code with strict 60s expiry, digits-only
 * validation (`^[0-9]{4,8}$`), one-shot consumption, and immediate scrub on auto-type.
 */
class OtpAutoTypeController(
    private val typeDigits: (String) -> Boolean,
    private val ttlMs: Long = 60_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private var latestCode: String? = null
    private var latestSource: String = ""
    private var recordedAtMs: Long = 0L

    private val _pendingOtp = MutableStateFlow<Pair<String, String>?>(null)
    val pendingOtp: StateFlow<Pair<String, String>?> = _pendingOtp.asStateFlow()

    fun recordOtp(code: String, source: String) = lock.withLock {
        val trimmed = code.trim()
        if (!OTP_DIGITS_REGEX.matches(trimmed)) return@withLock
        latestCode = trimmed
        latestSource = source.take(64)
        recordedAtMs = clockMs()
        _pendingOtp.value = trimmed to latestSource
    }

    fun consumeAndAutoType(): Boolean = lock.withLock {
        val code = latestCode ?: return false
        val expired = (clockMs() - recordedAtMs) > ttlMs
        latestCode = null
        latestSource = ""
        recordedAtMs = 0L
        _pendingOtp.value = null
        if (expired || !OTP_DIGITS_REGEX.matches(code)) return false
        typeDigits(code)
    }

    fun clear() = lock.withLock {
        latestCode = null
        latestSource = ""
        recordedAtMs = 0L
        _pendingOtp.value = null
    }

    companion object {
        private val OTP_DIGITS_REGEX = Regex("^[0-9]{4,8}$")
    }
}

// ---------------------------------------------------------------------------
// Feature #6 — Bidirectional Focus / DND & PipeWire Meeting Auto-Focus
// ---------------------------------------------------------------------------

class FocusSyncService(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val onLocalFocusApplied: (FocusStateInfo) -> Unit = {},
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private var preMeetingMode: String? = null

    private val _focusState = MutableStateFlow(FocusStateInfo(mode = FocusSyncProtocol.MODE_OFF))
    val focusState: StateFlow<FocusStateInfo> = _focusState.asStateFlow()

    fun setLocalFocusMode(
        mode: String,
        source: String = "manual",
        session: Ec0Session? = null,
    ): FocusStateInfo = lock.withLock {
        val next = FocusStateInfo(
            mode = mode,
            suppressNotifications = mode != FocusSyncProtocol.MODE_OFF,
            source = source,
            updatedAtMs = clockMs(),
        )
        _focusState.value = next
        onLocalFocusApplied(next)
        session?.sendInfo(
            infoType = FocusSyncProtocol.INFO_FOCUS_STATE,
            infoBody = next.toJsonObject(),
            category = FocusSyncProtocol.CATEGORY_FOCUS,
            timestampMs = next.updatedAtMs,
        )
        next
    }

    /**
     * Inspects active PipeWire/PulseAudio recording streams (`pactl list source-outputs short`)
     * to detect live conferencing apps (Zoom/Meet/Discord/Teams/OBS) and automatically toggle
     * Meeting Focus without clobbering manual Focus modes.
     */
    fun pollMeetingAutoFocus(session: Ec0Session? = null): Boolean {
        val res = runCatching {
            launcher.runBlockingCommand(listOf("pactl", "list", "source-outputs", "short"), timeoutMs = 2_000L)
        }.getOrNull() ?: return false
        if (res.exitCode != 0) return false
        val activeMicStreams = res.stdout.lineSequence().count { it.isNotBlank() }
        val inMeeting = activeMicStreams > 0
        lock.withLock {
            val current = _focusState.value
            if (inMeeting && current.mode != FocusSyncProtocol.MODE_MEETING) {
                preMeetingMode = current.mode
                setLocalFocusMode(FocusSyncProtocol.MODE_MEETING, source = "meeting_auto", session = session)
                return true
            } else if (!inMeeting && current.mode == FocusSyncProtocol.MODE_MEETING && current.source == "meeting_auto") {
                val restore = preMeetingMode ?: FocusSyncProtocol.MODE_OFF
                preMeetingMode = null
                setLocalFocusMode(restore, source = "meeting_auto_end", session = session)
                return true
            }
        }
        return false
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        return when (envelope.bodyType) {
            FocusSyncProtocol.INFO_FOCUS_STATE, FocusSyncProtocol.CMD_FOCUS_SET -> {
                val incoming = FocusStateInfo.fromJsonObject(body)
                lock.withLock {
                    _focusState.value = incoming
                }
                onLocalFocusApplied(incoming)
                true
            }
            else -> false
        }
    }
}

// ---------------------------------------------------------------------------
// Feature #7 — Passive Browser Tab & Video Timestamp Handoff
// ---------------------------------------------------------------------------

class BrowserTabHandoffService(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = ReentrantLock()
    private val recentTabs = ArrayDeque<BrowserTabHandoffInfo>()

    private val _activeRemoteTab = MutableStateFlow<BrowserTabHandoffInfo?>(null)
    val activeRemoteTab: StateFlow<BrowserTabHandoffInfo?> = _activeRemoteTab.asStateFlow()

    private val _recentRemoteTabs = MutableStateFlow<List<BrowserTabHandoffInfo>>(emptyList())
    val recentRemoteTabs: StateFlow<List<BrowserTabHandoffInfo>> = _recentRemoteTabs.asStateFlow()

    fun publishLocalActiveTab(
        info: BrowserTabHandoffInfo,
        session: Ec0Session?,
    ): Ec0Envelope? {
        return session?.sendInfo(
            infoType = BrowserHandoffProtocol.INFO_BROWSER_TAB,
            infoBody = info.toJsonObject(),
            category = HandoffProtocol.CATEGORY_HANDOFF,
            timestampMs = if (info.timestampMs > 0L) info.timestampMs else clockMs(),
        )
    }

    fun handleControlEnvelope(envelope: Ec0Envelope): Boolean {
        if (envelope.bodyType != BrowserHandoffProtocol.INFO_BROWSER_TAB) return false
        val body = envelope.innerBodyObject ?: (envelope.msgBody as? JsonObject) ?: return false
        val info = BrowserTabHandoffInfo.fromJsonObject(body)
        lock.withLock {
            recentTabs.removeAll { it.tabId == info.tabId || it.url == info.url }
            recentTabs.addFirst(info)
            while (recentTabs.size > MAX_RECENT_TABS) recentTabs.removeLast()
            _activeRemoteTab.value = info
            _recentRemoteTabs.value = recentTabs.toList()
        }
        return true
    }

    companion object {
        const val MAX_RECENT_TABS: Int = 16

        /**
         * Safely appends a resume playback timestamp (`t=<sec>s`) to supported video URLs
         * (YouTube / youtu.be) after strict `HandoffProtocol.validateSafeHandoffUrl` checks.
         */
        fun buildResumeUrl(info: BrowserTabHandoffInfo): String {
            val safe = HandoffProtocol.validateSafeHandoffUrl(info.url)
            if (info.mediaPositionSec <= 0) return safe
            val lower = safe.lowercase()
            val isYoutube = lower.contains("://www.youtube.com/watch") ||
                lower.contains("://m.youtube.com/watch") ||
                lower.contains("://youtu.be/")
            if (!isYoutube) return safe
            val stripped = safe.replace(Regex("([?&])t=\\d+s?(&|$)")) { m ->
                val prefix = m.groupValues[1]
                val suffix = m.groupValues[2]
                if (prefix == "?" && suffix == "&") "?" else if (suffix == "&") "&" else ""
            }.trimEnd('?', '&')
            val sep = if ('?' in stripped) '&' else '?'
            return HandoffProtocol.validateSafeHandoffUrl("$stripped${sep}t=${info.mediaPositionSec}s")
        }
    }
}
