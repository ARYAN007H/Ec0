/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Phone call state observer & remote call action executor for the Desktop
 * Incoming Call Overlay.
 *
 * Defensive properties:
 *   - Checks runtime `READ_PHONE_STATE` permission before registering a
 *     telephony listener; gracefully no-ops when denied (never crashes).
 *   - Checks `ANSWER_PHONE_CALLS` before invoking `TelecomManager.acceptRingingCall()`
 *     or `TelecomManager.endCall()`; returns `false` honestly when denied.
 *   - Sanitizes caller labels/numbers before emitting `CallStateInfo` over the
 *     encrypted engine session.
 */
package ec0.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ec0.core.engine.protocol.CallActionCommand
import ec0.core.engine.protocol.CallStateInfo
import ec0.core.engine.protocol.PhoneCallState
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** Pure-Kotlin mapper from Android telephony states to `:core` [CallStateInfo] (unit-testable). */
object CallStateMapper {
    fun mapCallState(
        androidState: Int,
        activeCallId: String?,
        incomingNumber: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Pair<String?, CallStateInfo?> {
        val sanitizedNumber = sanitizeCallerField(incomingNumber, maxLen = 64)
        return when (androidState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val id = activeCallId ?: "call-${UUID.randomUUID().toString().take(8)}"
                id to CallStateInfo(
                    callId = id,
                    state = PhoneCallState.RINGING,
                    callerNumber = sanitizedNumber,
                    callerName = sanitizedNumber.ifBlank { "Incoming call" },
                    timestampMs = nowMs,
                )
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val id = activeCallId ?: "call-${UUID.randomUUID().toString().take(8)}"
                id to CallStateInfo(
                    callId = id,
                    state = PhoneCallState.ACTIVE,
                    callerNumber = sanitizedNumber,
                    callerName = sanitizedNumber.ifBlank { "Active call" },
                    timestampMs = nowMs,
                )
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val prevId = activeCallId ?: return null to null
                null to CallStateInfo(
                    callId = prevId,
                    state = PhoneCallState.ENDED,
                    callerNumber = sanitizedNumber,
                    callerName = sanitizedNumber,
                    timestampMs = nowMs,
                )
            }
            else -> activeCallId to null
        }
    }

    fun sanitizeCallerField(raw: String?, maxLen: Int = 64): String =
        raw.orEmpty()
            .filter { ch -> ch >= ' ' && ch != '\u007f' }
            .trim()
            .take(maxLen)
}

class Ec0CallStateMonitor(
    private val appContext: Context,
    private val onCallStateChanged: (CallStateInfo) -> Unit,
) {
    private val log = Logger.getLogger("ec0.android.call")
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ec0-call-monitor").apply { isDaemon = true }
    }

    @Volatile
    private var activeCallId: String? = null

    @Volatile
    private var lastNumber: String? = null

    private var api31Callback: Any? = null

    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    fun hasReadPhoneStatePermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun hasAnswerPhoneCallsPermission(): Boolean = runCatching {
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ANSWER_PHONE_CALLS,
        ) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasReadPhoneStatePermission()) {
            log.info("READ_PHONE_STATE not granted — call overlay monitoring stays idle")
            return
        }
        if (!started.compareAndSet(false, true)) return
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleStateTransition(state, lastNumber)
                    }
                }
                api31Callback = cb
                tm.registerTelephonyCallback(executor, cb)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        if (!phoneNumber.isNullOrBlank()) {
                            lastNumber = phoneNumber
                        }
                        handleStateTransition(state, phoneNumber ?: lastNumber)
                    }
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        }.onFailure {
            started.set(false)
            log.warning("Failed to start call monitor: ${it.message}")
        }
    }

    private fun handleStateTransition(state: Int, number: String?) {
        val (nextId, info) = CallStateMapper.mapCallState(
            androidState = state,
            activeCallId = activeCallId,
            incomingNumber = number,
        )
        activeCallId = nextId
        if (nextId == null) {
            lastNumber = null
        }
        if (info != null) {
            runCatching { onCallStateChanged(info) }
        }
    }

    @SuppressLint("MissingPermission")
    fun handleCallAction(command: CallActionCommand): Boolean {
        if (!hasAnswerPhoneCallsPermission()) {
            log.info("ANSWER_PHONE_CALLS not granted — cannot execute ${command.action}")
            return false
        }
        val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false
        return runCatching {
            when (command.action.lowercase()) {
                CallActionCommand.ACTION_ANSWER_SPEAKER -> {
                    @Suppress("DEPRECATION")
                    telecom.acceptRingingCall()
                    true
                }
                CallActionCommand.ACTION_REJECT,
                CallActionCommand.ACTION_END_CALL,
                CallActionCommand.ACTION_REJECT_WITH_SMS,
                -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        @Suppress("DEPRECATION")
                        telecom.endCall()
                    } else {
                        false
                    }
                }
                else -> false
            }
        }.getOrDefault(false)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (api31Callback as? TelephonyCallback)?.let { tm?.unregisterTelephonyCallback(it) }
                api31Callback = null
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
                legacyListener = null
            }
        }
        activeCallId = null
        lastNumber = null
        executor.shutdown()
    }
}
