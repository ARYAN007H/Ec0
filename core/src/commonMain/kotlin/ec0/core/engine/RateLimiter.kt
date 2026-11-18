/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Per-key token-bucket rate limiter (walled-garden hardening): bounds the
 * consume-rate of every network-reachable surface that can arrive at line rate
 * from an authenticated-but-hostile peer (input events, VFS replies,
 * notification relays).
 *
 * Pure and injectable-clock so the same limiter is unit-testable without I/O.
 * Keys are bounded (oldest-evicted) so a peer rotation cannot grow the map.
 */
package ec0.core.engine

/**
 * Token bucket per key: starts full ([capacityPerPeer] tokens), refills at
 * [refillPerSecond] tokens/second, refuses when empty. One `tryAcquire` unit is
 * a single event.
 */
class TokenBucketRateLimiter(
    private val capacityPerPeer: Long,
    private val refillPerSecond: Long,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val maxTrackedKeys: Int = 256,
) {
    init {
        require(capacityPerPeer > 0L) { "capacityPerPeer must be positive" }
        require(refillPerSecond > 0L) { "refillPerSecond must be positive" }
        require(maxTrackedKeys > 0) { "maxTrackedKeys must be positive" }
    }

    private class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
    )

    private val buckets = LinkedHashMap<String, Bucket>()

    val trackedKeys: Int
        get() = buckets.size

    /**
     * Attempts to take [units] tokens for [key]. Returns `false` (and mutates
     * nothing for the caller's accounting) when the bucket cannot satisfy it.
     */
    fun tryAcquire(key: String, units: Long = 1): Boolean {
        require(units > 0L) { "units must be positive" }
        val now = clockMs()
        val bucket = synchronized(buckets) {
            val existing = buckets[key]
            if (existing != null) {
                existing
            } else {
                // Evict the oldest key when the table would grow past the cap.
                while (buckets.size >= maxTrackedKeys) {
                    val oldest = buckets.keys.firstOrNull() ?: break
                    buckets.remove(oldest)
                }
                Bucket(tokens = capacityPerPeer.toDouble(), lastRefillMs = now).also {
                    buckets[key] = it
                }
            }
        }
        synchronized(bucket) {
            refill(bucket, now)
            if (bucket.tokens >= units) {
                bucket.tokens -= units
                return true
            }
            return false
        }
    }

    /** Current token count for [key] (refilled lazily) — test/telemetry seam. */
    fun availableTokens(key: String): Double {
        val now = clockMs()
        val bucket = synchronized(buckets) { buckets[key] } ?: return capacityPerPeer.toDouble()
        synchronized(bucket) {
            refill(bucket, now)
            return bucket.tokens
        }
    }

    private fun refill(bucket: Bucket, nowMs: Long) {
        val elapsedMs = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L)
        if (elapsedMs > 0L) {
            val refillTokens = elapsedMs * refillPerSecond / 1000.0
            bucket.tokens = (bucket.tokens + refillTokens).coerceAtMost(capacityPerPeer.toDouble())
            bucket.lastRefillMs = nowMs
        }
    }
}
