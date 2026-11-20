/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure BLE Proximity Engine for Auto-Wake & Walk-Away PC Auto-Lock:
 *   - Privacy-preserving rotating ephemeral BLE tags derived via HKDF-SHA256
 *     from a paired peer's Ed25519 public key + 15-minute time epoch (never
 *     broadcasts static device IDs, hostnames, or long-term keys over BLE).
 *   - EMA RSSI smoother + hysteresis grace-window state machine so transient
 *     RF dips never spuriously lock the workstation.
 */
package ec0.core.engine.protocol

import ec0.core.engine.EngineEncoding
import ec0.core.engine.HkdfSha256

enum class ProximityZone {
    UNKNOWN,
    NEAR,
    FAR,
}

enum class ProximityEvent {
    NONE,
    APPROACH_WAKE,
    WALK_AWAY_LOCK,
}

data class ProximityEvaluation(
    val zone: ProximityZone,
    val smoothedRssiDbm: Double,
    val event: ProximityEvent,
)

/**
 * Derives privacy-preserving 8-byte rotating BLE proximity tags for a paired peer.
 * Verifiers check `[epoch - 1, epoch, epoch + 1]` to tolerate ±15 min clock skew.
 */
object BleProximityTag {
    const val EPOCH_DURATION_MS: Long = 15L * 60_000L
    const val TAG_BYTES: Int = 8
    private val SALT = "ec0-ble-proximity-v1".encodeToByteArray()

    fun currentEpoch(nowMs: Long): Long = (nowMs / EPOCH_DURATION_MS).coerceAtLeast(0L)

    fun deriveTagHex(peerPublicKeyRaw32: ByteArray, epoch: Long): String {
        require(peerPublicKeyRaw32.size == 32) { "Ed25519 public key must be 32 bytes" }
        val info = "ble-tag:$epoch".encodeToByteArray()
        val raw = HkdfSha256.deriveKey(
            ikm = peerPublicKeyRaw32,
            salt = SALT,
            info = info,
            outLen = TAG_BYTES,
        )
        return raw.joinToString("") { b ->
            val v = b.toInt() and 0xFF
            val hi = "0123456789abcdef"[v ushr 4]
            val lo = "0123456789abcdef"[v and 0x0F]
            "$hi$lo"
        }
    }

    /** Constant-time match against current and ±1 adjacent rotation epochs. */
    fun matchesPeer(candidateTagHex: String, peerPublicKeyRaw32: ByteArray, nowMs: Long): Boolean {
        val normalized = candidateTagHex.trim().lowercase()
        if (normalized.length != TAG_BYTES * 2) return false
        val epoch = currentEpoch(nowMs)
        for (delta in -1L..1L) {
            val e = (epoch + delta).coerceAtLeast(0L)
            val expected = deriveTagHex(peerPublicKeyRaw32, e)
            if (EngineEncoding.constantTimeEquals(
                    normalized.encodeToByteArray(),
                    expected.encodeToByteArray(),
                )
            ) {
                return true
            }
        }
        return false
    }
}

/**
 * Stateful RSSI smoother with dual-threshold hysteresis and grace-window lock timer.
 */
class BleProximityStateMachine(
    private val nearThresholdDbm: Double = -68.0,
    private val farThresholdDbm: Double = -82.0,
    private val lockGraceMs: Long = 15_000L,
    private val emaAlpha: Double = 0.30,
) {
    init {
        require(nearThresholdDbm > farThresholdDbm) { "nearThresholdDbm must be stronger than farThresholdDbm" }
        require(lockGraceMs >= 1_000L) { "lockGraceMs must be >= 1000ms" }
    }

    private var smoothedRssi: Double? = null
    private var currentZone: ProximityZone = ProximityZone.UNKNOWN
    private var farSinceMs: Long? = null
    private var lastSeenMs: Long? = null
    private var lockDispatchedForCurrentDeparture: Boolean = false

    val zone: ProximityZone get() = currentZone
    val smoothedRssiDbm: Double? get() = smoothedRssi

    fun reset() {
        smoothedRssi = null
        currentZone = ProximityZone.UNKNOWN
        farSinceMs = null
        lastSeenMs = null
        lockDispatchedForCurrentDeparture = false
    }

    fun onRssiSample(rssiDbm: Int, nowMs: Long): ProximityEvaluation {
        lastSeenMs = nowMs
        val clamped = rssiDbm.coerceIn(-120, 0).toDouble()
        val prev = smoothedRssi
        val next = if (prev == null) clamped else (emaAlpha * clamped) + ((1.0 - emaAlpha) * prev)
        smoothedRssi = next

        var event = ProximityEvent.NONE
        when {
            next >= nearThresholdDbm -> {
                if (currentZone != ProximityZone.NEAR) {
                    event = ProximityEvent.APPROACH_WAKE
                }
                currentZone = ProximityZone.NEAR
                farSinceMs = null
                lockDispatchedForCurrentDeparture = false
            }
            next <= farThresholdDbm -> {
                if (farSinceMs == null) {
                    farSinceMs = nowMs
                }
                val elapsed = nowMs - (farSinceMs ?: nowMs)
                if (elapsed >= lockGraceMs) {
                    if (currentZone == ProximityZone.NEAR && !lockDispatchedForCurrentDeparture) {
                        event = ProximityEvent.WALK_AWAY_LOCK
                        lockDispatchedForCurrentDeparture = true
                    }
                    currentZone = ProximityZone.FAR
                }
            }
            else -> {
                // Inside hysteresis deadband: cancel pending walk-away countdown.
                farSinceMs = null
            }
        }
        return ProximityEvaluation(
            zone = currentZone,
            smoothedRssiDbm = next,
            event = event,
        )
    }

    /** Convenience tick when no BLE RSSI sample was seen during the current scan cycle. */
    fun onTickWithoutSignal(nowMs: Long): ProximityEvaluation {
        val seen = lastSeenMs ?: return ProximityEvaluation(
            zone = currentZone,
            smoothedRssiDbm = smoothedRssi ?: -100.0,
            event = ProximityEvent.NONE,
        )
        return onSignalTimeout(lastSeenMs = seen, nowMs = nowMs)
    }

    /** Evaluates signal loss (peer BLE advertisement disappeared for >= [lockGraceMs]). */
    fun onSignalTimeout(lastSeenMs: Long, nowMs: Long): ProximityEvaluation {
        val elapsed = (nowMs - lastSeenMs).coerceAtLeast(0L)
        var event = ProximityEvent.NONE
        if (elapsed >= lockGraceMs && currentZone == ProximityZone.NEAR && !lockDispatchedForCurrentDeparture) {
            event = ProximityEvent.WALK_AWAY_LOCK
            lockDispatchedForCurrentDeparture = true
            currentZone = ProximityZone.FAR
        }
        return ProximityEvaluation(
            zone = currentZone,
            smoothedRssiDbm = smoothedRssi ?: -100.0,
            event = event,
        )
    }
}
