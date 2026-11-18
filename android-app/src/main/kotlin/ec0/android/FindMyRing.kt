/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature A2: find-my-phone executor. The desktop's `find_my_ring` quick command
 * (hub dashboard siren toggle) arrives over the engine's handoff/proximity service
 * and lands in [FindMyRingController.onFindMyRing] via
 * `AndroidHonestPlatformCallbacks.onFindMyRingCommand`:
 *   1. alarm stream to max volume (DND override semantics live on the ALARM stream —
 *      DND silences notifications, not alarms, unless total-silence mode is on),
 *   2. the wake activity lights the screen over the lock screen,
 *   3. the alarm loops for the command's duration (default 15 s, wire-capped 120 s),
 *   4. STOP_RING (or the user tapping the activity's stop button) ends it and
 *      restores the previous alarm volume.
 */

package ec0.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import ec0.core.engine.protocol.FindMyRingCommand
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Owns the one-shot ring session. Instantiated by the platform callbacks (service
 * context — the foreground service outlives the Activity, so the controller lives
 * there and only the WAKE screen is Activity-scoped).
 */
class FindMyRingController(
    private val appContext: Context,
    private val log: Logger = Logger.getLogger("ec0.android.findmy"),
) {
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicReference<RingSession?>(null)

    private class RingSession(
        val player: MediaPlayer,
        val previousAlarmVolume: Int,
        val stopRunnable: Runnable,
    )

    /** Entry point for [AndroidHonestPlatformCallbacks.onFindMyRingCommand]. */
    fun onFindMyRing(command: FindMyRingCommand) {
        if (command.action == FindMyRingCommand.ACTION_START_RING) {
            start(command.durationSeconds)
        } else {
            stop()
        }
    }

    fun start(durationSeconds: Int) {
        stop() // re-START restarts the window cleanly
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val previous = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

        val player = MediaPlayer()
        val session = RingSession(
            player = player,
            previousAlarmVolume = previous,
            stopRunnable = Runnable { stop() },
        )
        if (!active.compareAndSet(null, session)) {
            return // another start won the race; our stop() above already reset it
        }

        runCatching {
            // Max alarm volume: DND filters NOTIFICATIONS, alarms ring through
            // (unless total silence — accepted residual, surfaced in the hub note).
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player.setDataSource(appContext, alarmUri)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.isLooping = true
            player.prepare()
            player.start()

            // Wake screen: the activity shows over the lock screen. On API 31+
            // a background activity launch is restricted — the ALARM sound still
            // runs (foreground service context), and the notification path is
            // agent-2's full-screen-intent follow-up (hub note).
            val wake = Intent(appContext, FindMyRingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(FindMyRingActivity.EXTRA_RINGING, true)
            }
            runCatching { appContext.startActivity(wake) }
                .onFailure { log.warning("wake activity launch refused: $it") }

            handler.postDelayed(session.stopRunnable, durationSeconds * 1_000L)
            log.info("find-my ring started for ${durationSeconds}s at alarm volume $max")
        }.onFailure {
            active.set(null)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            runCatching { player.release() }
            log.warning("find-my ring failed to start: $it")
        }
    }

    fun stop() {
        val session = active.getAndSet(null) ?: return
        handler.removeCallbacks(session.stopRunnable)
        runCatching {
            session.player.stop()
            session.player.release()
        }
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audio?.setStreamVolume(AudioManager.STREAM_ALARM, session.previousAlarmVolume, 0)
        log.info("find-my ring stopped, alarm volume restored to ${session.previousAlarmVolume}")
    }

    val isRinging: Boolean get() = active.get() != null
}

/**
 * Wake screen for the find-my-phone siren: lights over the lock screen and shows
 * a big stop affordance. STOP_RING from the desktop (or the duration elapsing)
 * also stops the siren via the shared [FindMyRingController].
 */
class FindMyRingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val button = android.widget.Button(this).apply {
            text = "I'm here — stop ringing"
            textSize = 20f
            setOnClickListener {
                Ec0CompanionService.ringControllerRef?.stop()
                finish()
            }
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF101014.toInt())
            addView(
                android.widget.TextView(this@FindMyRingActivity).apply {
                    text = "ec0 — find my phone"
                    textSize = 22f
                    setTextColor(0xFFFFC77D.toInt())
                    gravity = android.view.Gravity.CENTER
                    setPadding(pad, pad, pad, pad)
                },
            )
            addView(
                button,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ).setMargins(pad, pad, pad, pad),
            )
        }
        setContentView(layout)
    }
}
