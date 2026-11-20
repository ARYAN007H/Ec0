/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Linux BlueZ RSSI proximity scanner feeding [BleProximityStateMachine] for
 * Walk-Away Auto-Lock and Approach Auto-Wake.
 *
 * Security invariants:
 *   - Uses argv-only `bluetoothctl` commands (zero shell invocation).
 *   - Strictly validates Bluetooth MAC addresses (`^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$`)
 *     before passing any device address to `bluetoothctl info <mac>`.
 *   - Never triggers workstation lock unless the user explicitly enables
 *     Walk-Away Auto-Lock and the hysteresis + 15s grace timer expires.
 */
package ec0.core.engine.linux

import ec0.core.engine.protocol.BleProximityStateMachine
import ec0.core.engine.protocol.ProximityEvent
import ec0.core.engine.protocol.ProximityZone
import ec0.core.engine.services.ExternalProcessLauncher
import ec0.core.engine.services.SystemProcessLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class BleDeviceReading(
    val macAddress: String,
    val name: String,
    val rssiDbm: Int?,
)

data class ProximityStatusSnapshot(
    val enabled: Boolean = false,
    val zone: ProximityZone = ProximityZone.UNKNOWN,
    val smoothedRssiDbm: Int? = null,
    val trackedDeviceName: String? = null,
)

class LinuxBleProximityScanner(
    private val launcher: ExternalProcessLauncher = SystemProcessLauncher,
    private val stateMachine: BleProximityStateMachine = BleProximityStateMachine(),
    private val onWalkAwayLock: () -> Unit = {},
    private val onApproachWake: () -> Unit = {},
    private val targetDeviceNameProvider: () -> String? = { null },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = 3_000L,
) : Closeable {

    private val running = AtomicBoolean(false)
    private val enabledFlag = AtomicBoolean(false)
    private var scheduler: ScheduledExecutorService? = null

    private val _status = MutableStateFlow(ProximityStatusSnapshot())
    val status: StateFlow<ProximityStatusSnapshot> = _status.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        enabledFlag.set(enabled)
        if (!enabled) {
            stateMachine.reset()
            stopLoop()
            _status.value = ProximityStatusSnapshot(enabled = false)
        } else {
            _status.value = _status.value.copy(enabled = true)
            startLoopIfNeeded()
        }
    }

    /** Single deterministic scan tick — callable directly in unit tests. */
    fun pollOnce(): ProximityEvent? {
        if (!enabledFlag.get()) return null
        val targetName = targetDeviceNameProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val now = clockMs()

        val devicesRes = runCatching {
            launcher.runBlockingCommand(listOf("bluetoothctl", "devices"), timeoutMs = 2_000L)
        }.getOrNull()

        val devices = if (devicesRes != null && devicesRes.exitCode == 0) {
            parseBluetoothctlDevices(devicesRes.stdout)
        } else {
            emptyList()
        }

        val matched = if (targetName != null) {
            devices.firstOrNull { it.second.contains(targetName, ignoreCase = true) }
        } else {
            devices.firstOrNull()
        }

        val rssi = matched?.let { (mac, _) ->
            if (!MAC_REGEX.matches(mac)) return@let null
            val infoRes = runCatching {
                launcher.runBlockingCommand(listOf("bluetoothctl", "info", mac), timeoutMs = 2_000L)
            }.getOrNull()
            if (infoRes != null && infoRes.exitCode == 0) {
                extractRssiDbm(infoRes.stdout)
            } else {
                null
            }
        }

        val evaluation = if (rssi != null) {
            stateMachine.onRssiSample(rssiDbm = rssi, nowMs = now)
        } else {
            stateMachine.onTickWithoutSignal(nowMs = now)
        }

        _status.value = ProximityStatusSnapshot(
            enabled = true,
            zone = evaluation.zone,
            smoothedRssiDbm = stateMachine.smoothedRssiDbm?.toInt(),
            trackedDeviceName = matched?.second ?: targetName,
        )

        when (evaluation.event) {
            ProximityEvent.WALK_AWAY_LOCK -> onWalkAwayLock()
            ProximityEvent.APPROACH_WAKE -> onApproachWake()
            ProximityEvent.NONE -> Unit
        }
        return evaluation.event.takeIf { it != ProximityEvent.NONE }
    }

    private fun startLoopIfNeeded() {
        if (!running.compareAndSet(false, true)) return
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ec0-ble-proximity").apply { isDaemon = true }
        }
        scheduler = exec
        exec.scheduleAtFixedRate(
            { runCatching { pollOnce() } },
            1_000L,
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
        enabledFlag.set(false)
        stopLoop()
    }

    companion object {
        val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        private val DEVICE_LINE_REGEX = Regex("^Device\\s+(([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2})\\s+(.+)$")
        private val RSSI_REGEX = Regex("RSSI:\\s*(?:0x[0-9a-fA-F]+\\s*\\()?(-?\\d+)\\)?")

        /** Parses `bluetoothctl devices` output into validated `(mac, name)` pairs. */
        fun parseBluetoothctlDevices(stdout: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            for (rawLine in stdout.lineSequence()) {
                val m = DEVICE_LINE_REGEX.matchEntire(rawLine.trim()) ?: continue
                val mac = m.groupValues[1]
                val name = m.groupValues[3].trim()
                if (MAC_REGEX.matches(mac) && name.isNotEmpty()) {
                    out.add(mac to name)
                }
            }
            return out
        }

        /** Extracts signed RSSI dBm (clamped to `-120..0`) from `bluetoothctl info <mac>`. */
        fun extractRssiDbm(stdout: String): Int? {
            for (rawLine in stdout.lineSequence()) {
                val m = RSSI_REGEX.find(rawLine) ?: continue
                val value = m.groupValues[1].toIntOrNull() ?: continue
                if (value in -120..0) return value
            }
            return null
        }
    }
}
