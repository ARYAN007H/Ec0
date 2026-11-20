/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Linux screen-edge barrier watcher for Universal Control.
 *
 * Samples local desktop pointer coordinates at ~60 Hz when enabled, detects
 * when the cursor hits the configured monitor edge (`LEFT`, `RIGHT`, `TOP`,
 * `BOTTOM`), triggers [UniversalControlService.evaluateLocalPointerAtEdge],
 * and streams relative pointer deltas to the connected Android peer until the
 * cursor returns across the opposite edge or the user triggers [forceReturnToLocal].
 *
 * Safe on headless environments (e.g. CI runners): gracefully falls back when
 * `GraphicsEnvironment.isHeadless()` is true.
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.CursorHandoffCommand
import ec0.core.engine.protocol.ScreenEdge
import ec0.core.engine.services.UniversalControlService
import ec0.core.engine.transport.Ec0Session
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ScreenPointerSample(
    val x: Int,
    val y: Int,
    val screenWidth: Int,
    val screenHeight: Int,
)

class LinuxScreenEdgeWatcher(
    private val universalControlService: UniversalControlService,
    private val activePeerProvider: () -> Pair<String, Ec0Session?>?,
    private val onRemotePointerDelta: (dx: Int, dy: Int) -> Unit = { _, _ -> },
    private val onHandoffChanged: (CursorHandoffCommand) -> Unit = {},
    private val pointerSampler: () -> ScreenPointerSample? = { defaultAwtPointerSample() },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = 16L,
) : Closeable {

    private val running = AtomicBoolean(false)
    private val enabledEdge = AtomicReference<ScreenEdge?>(null)
    private var scheduler: ScheduledExecutorService? = null
    private var lastX: Int? = null
    private var lastY: Int? = null

    val currentEdge: ScreenEdge?
        get() = enabledEdge.get()

    val isRemoteActive: Boolean
        get() = universalControlService.activeRemotePeerId.value != null

    /**
     * Enables or disables the screen-edge watcher. Passing `null` disables
     * Universal Control and immediately returns pointer focus to local desktop.
     */
    fun configureEdge(edge: ScreenEdge?) {
        enabledEdge.set(edge)
        if (edge == null) {
            forceReturnToLocal()
            stopLoop()
        } else {
            universalControlService.setConfiguredEdge(edge)
            startLoopIfNeeded()
        }
    }

    /** Explicit escape hatch (e.g. Escape key or UI button) returning cursor to local desktop. */
    fun forceReturnToLocal() {
        val peer = activePeerProvider()
        if (universalControlService.activeRemotePeerId.value != null) {
            val cmd = universalControlService.returnCursorToLocal(
                session = peer?.second,
                nowMs = clockMs(),
            )
            onHandoffChanged(cmd)
        }
        lastX = null
        lastY = null
    }

    /** Single deterministic tick — exposed as `internal` for unit testing without AWT threads. */
    internal fun pollOnce() {
        val edge = enabledEdge.get() ?: return
        val peer = activePeerProvider() ?: run {
            if (isRemoteActive) {
                universalControlService.returnCursorToLocal(session = null, nowMs = clockMs())
            }
            return
        }
        val sample = pointerSampler() ?: return
        val now = clockMs()

        if (universalControlService.activeRemotePeerId.value == null) {
            val entered = universalControlService.evaluateLocalPointerAtEdge(
                x = sample.x,
                y = sample.y,
                screenWidth = sample.screenWidth,
                screenHeight = sample.screenHeight,
                peerDeviceId = peer.first,
                session = peer.second,
                nowMs = now,
            )
            if (entered != null) {
                lastX = sample.x
                lastY = sample.y
                onHandoffChanged(entered)
            }
        } else {
            val prevX = lastX ?: sample.x
            val prevY = lastY ?: sample.y
            val dx = (sample.x - prevX).coerceIn(-256, 256)
            val dy = (sample.y - prevY).coerceIn(-256, 256)
            lastX = sample.x
            lastY = sample.y

            // If user pulls cursor cleanly back away from the active edge by > 24 px, return local.
            val pulledBack = when (edge) {
                ScreenEdge.LEFT -> sample.x > 24
                ScreenEdge.RIGHT -> sample.x < sample.screenWidth - 25
                ScreenEdge.TOP -> sample.y > 24
                ScreenEdge.BOTTOM -> sample.y < sample.screenHeight - 25
            }
            if (pulledBack) {
                val leftCmd = universalControlService.returnCursorToLocal(
                    session = peer.second,
                    nowMs = now,
                )
                onHandoffChanged(leftCmd)
                lastX = null
                lastY = null
            } else if (dx != 0 || dy != 0) {
                onRemotePointerDelta(dx, dy)
            }
        }
    }

    private fun startLoopIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ec0-edge-watcher").apply { isDaemon = true }
        }
        scheduler = exec
        exec.scheduleAtFixedRate(
            { runCatching { pollOnce() } },
            pollIntervalMs,
            pollIntervalMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun stopLoop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { scheduler?.shutdownNow() }
        scheduler = null
    }

    override fun close() {
        enabledEdge.set(null)
        stopLoop()
    }

    companion object {
        fun defaultAwtPointerSample(): ScreenPointerSample? {
            if (GraphicsEnvironment.isHeadless()) return null
            return runCatching {
                val pointer = MouseInfo.getPointerInfo() ?: return null
                val loc = pointer.location
                val bounds = pointer.device.defaultConfiguration.bounds
                ScreenPointerSample(
                    x = loc.x - bounds.x,
                    y = loc.y - bounds.y,
                    screenWidth = bounds.width,
                    screenHeight = bounds.height,
                )
            }.getOrNull()
        }
    }
}
