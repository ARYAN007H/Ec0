/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * End-to-End Universal Control receiver on Android (`AccessibilityService`):
 *   - Protected by `android.permission.BIND_ACCESSIBILITY_SERVICE` with
 *     `android:exported="false"` in AndroidManifest.xml.
 *   - Accepts ONLY events forwarded in-process from `Ec0CompanionService` over
 *     an authenticated Ed25519 + X25519 + AES-256-GCM desktop session.
 *   - Maintains a clamped virtual pointer `(x, y)` in [VirtualCursorState] and
 *     dispatches taps / drags / scrolls via Android's `dispatchGesture` API.
 */
package ec0.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.InputPointerEvent
import ec0.core.engine.protocol.InputProtocol
import ec0.core.engine.protocol.ScreenEdge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Pure-Kotlin virtual cursor coordinate state machine (100% JVM unit-testable).
 * Keeps coordinates strictly clamped to `[0, width-1] x [0, height-1]` and prevents
 * pathological negative/overflow gesture durations or off-screen coordinates.
 */
class VirtualCursorState(
    initialWidth: Int = 1080,
    initialHeight: Int = 2400,
) {
    @Volatile
    var screenWidth: Int = initialWidth.coerceIn(1, 8192)
        private set

    @Volatile
    var screenHeight: Int = initialHeight.coerceIn(1, 8192)
        private set

    @Volatile
    var x: Float = screenWidth / 2f
        private set

    @Volatile
    var y: Float = screenHeight / 2f
        private set

    @Volatile
    var active: Boolean = false
        private set

    fun updateDisplaySize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            screenWidth = width.coerceIn(1, 8192)
            screenHeight = height.coerceIn(1, 8192)
            x = x.coerceIn(0f, (screenWidth - 1).toFloat())
            y = y.coerceIn(0f, (screenHeight - 1).toFloat())
        }
    }

    fun onHandoff(command: CursorHandoffCommand) {
        when (command.action) {
            CursorHandoffCommand.ACTION_ENTER -> {
                active = true
                val ratio = (command.positionPermille.coerceIn(0, 1000) / 1000f)
                when (command.edge) {
                    ScreenEdge.LEFT -> {
                        x = 4f.coerceAtMost((screenWidth - 1).toFloat())
                        y = (ratio * (screenHeight - 1)).coerceIn(0f, (screenHeight - 1).toFloat())
                    }
                    ScreenEdge.RIGHT -> {
                        x = (screenWidth - 5).coerceAtLeast(0).toFloat()
                        y = (ratio * (screenHeight - 1)).coerceIn(0f, (screenHeight - 1).toFloat())
                    }
                    ScreenEdge.TOP -> {
                        x = (ratio * (screenWidth - 1)).coerceIn(0f, (screenWidth - 1).toFloat())
                        y = 4f.coerceAtMost((screenHeight - 1).toFloat())
                    }
                    ScreenEdge.BOTTOM -> {
                        x = (ratio * (screenWidth - 1)).coerceIn(0f, (screenWidth - 1).toFloat())
                        y = (screenHeight - 5).coerceAtLeast(0).toFloat()
                    }
                }
            }
            CursorHandoffCommand.ACTION_LEAVE -> {
                active = false
            }
        }
    }

    fun applyMove(dx: Int, dy: Int): Pair<Float, Float> {
        val clampedDx = dx.coerceIn(-4000, 4000)
        val clampedDy = dy.coerceIn(-4000, 4000)
        x = (x + clampedDx).coerceIn(0f, (screenWidth - 1).toFloat())
        y = (y + clampedDy).coerceIn(0f, (screenHeight - 1).toFloat())
        return x to y
    }

    fun computeScrollStroke(scrollY: Int): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val clamped = scrollY.coerceIn(-20, 20)
        val deltaPx = clamped * 45f
        val startX = x.coerceIn(1f, (screenWidth - 2).coerceAtLeast(1).toFloat())
        val startY = y.coerceIn(1f, (screenHeight - 2).coerceAtLeast(1).toFloat())
        val endY = (startY + deltaPx).coerceIn(1f, (screenHeight - 2).coerceAtLeast(1).toFloat())
        return (startX to startY) to (startX to endY)
    }
}

class Ec0UniversalControlAccessibilityService : AccessibilityService() {

    private val log = Logger.getLogger("ec0.android.uc")

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        refreshDisplayMetrics()
        log.info("Universal Control accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ec0 never reads screen text or inspects other apps' UI hierarchy.
        // This service is strictly gesture-injection-only for Universal Control.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        cursorState.onHandoff(
            CursorHandoffCommand(
                action = CursorHandoffCommand.ACTION_LEAVE,
                edge = ScreenEdge.RIGHT,
            ),
        )
        super.onDestroy()
    }

    private fun refreshDisplayMetrics() {
        runCatching {
            val dm = resources.displayMetrics
            cursorState.updateDisplaySize(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun dispatchTap(tapX: Float, tapY: Float): Boolean {
        val path = Path().apply { moveTo(tapX, tapY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 120L): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(40L, 500L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        private const val MASK_RIGHT_BUTTON = 1 shl 1
        private const val MASK_MIDDLE_BUTTON = 1 shl 2

        private val cursorState = VirtualCursorState()
        private val gestureInFlight = AtomicBoolean(false)

        @Volatile
        private var activeInstance: Ec0UniversalControlAccessibilityService? = null

        fun isConnected(): Boolean = activeInstance != null

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (activeInstance != null) return true
            val enabled = runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()
            val pkg = runCatching { context.packageName }.getOrDefault("ec0.android")
            val full = "$pkg/${Ec0UniversalControlAccessibilityService::class.java.name}"
            val short = "$pkg/.Ec0UniversalControlAccessibilityService"
            return enabled.split(':').any { token ->
                val t = token.trim()
                t == full || t == short
            }
        }

        fun openAccessibilitySettings(context: Context): Boolean = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.getOrDefault(false)

        fun onCursorHandoff(command: CursorHandoffCommand): Boolean {
            val service = activeInstance ?: return false
            service.refreshDisplayMetrics()
            cursorState.onHandoff(command)
            return true
        }

        fun onRemotePointerEvent(event: InputPointerEvent): Boolean {
            val service = activeInstance ?: return false
            if (!cursorState.active) return false
            service.refreshDisplayMetrics()
            return when (event.action) {
                InputProtocol.POINTER_ACTION_MOVE -> {
                    cursorState.applyMove(event.dx, event.dy)
                    true
                }
                InputProtocol.POINTER_ACTION_DOWN,
                InputProtocol.POINTER_ACTION_CLICK,
                -> {
                    if (!gestureInFlight.compareAndSet(false, true)) return false
                    try {
                        when {
                            (event.buttonsMask and MASK_RIGHT_BUTTON) != 0 ->
                                service.performGlobalAction(GLOBAL_ACTION_BACK)
                            (event.buttonsMask and MASK_MIDDLE_BUTTON) != 0 ->
                                service.performGlobalAction(GLOBAL_ACTION_HOME)
                            else -> service.dispatchTap(cursorState.x, cursorState.y)
                        }
                    } finally {
                        gestureInFlight.set(false)
                    }
                }
                InputProtocol.POINTER_ACTION_SCROLL -> {
                    if (event.scrollY == 0) return true
                    val (start, end) = cursorState.computeScrollStroke(event.scrollY)
                    service.dispatchSwipe(start.first, start.second, end.first, end.second)
                }
                else -> true
            }
        }
    }
}
