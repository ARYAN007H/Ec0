/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Walled-garden rate-limiter tests (audit H1/H2/M1): pure token-bucket behavior
 * with an injected clock — burst admission, sustained refill, per-key isolation,
 * key-table bounding.
 */
package ec0.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {

    private class FakeClock(var nowMs: Long = 0L) {
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    @Test
    fun admitsBurstThenRefusesUntilRefill() {
        val clock = FakeClock()
        val limiter = TokenBucketRateLimiter(
            capacityPerPeer = 3,
            refillPerSecond = 2,
            clockMs = { clock.nowMs },
        )
        assertTrue(limiter.tryAcquire("peer-a"), "token 1 of burst")
        assertTrue(limiter.tryAcquire("peer-a"), "token 2 of burst")
        assertTrue(limiter.tryAcquire("peer-a"), "token 3 of burst")
        assertFalse(limiter.tryAcquire("peer-a"), "burst exhausted — refused")

        clock.advance(500) // 0.5 s at 2/s = +1 token
        assertTrue(limiter.tryAcquire("peer-a"), "one token refilled after 500 ms")
        assertFalse(limiter.tryAcquire("peer-a"), "refill exhausted again")

        clock.advance(1500) // +3 tokens (caps at capacity)
        assertTrue(limiter.tryAcquire("peer-a"), "refilled again")
        assertTrue(limiter.tryAcquire("peer-a"))
        assertTrue(limiter.tryAcquire("peer-a"))
        assertFalse(limiter.tryAcquire("peer-a"), "capacity ceiling holds — no super-burst accumulation")
    }

    @Test
    fun keysAreIsolated() {
        val clock = FakeClock()
        val limiter = TokenBucketRateLimiter(
            capacityPerPeer = 1,
            refillPerSecond = 1,
            clockMs = { clock.nowMs },
        )
        assertTrue(limiter.tryAcquire("peer-a"))
        assertFalse(limiter.tryAcquire("peer-a"), "peer-a exhausted")
        assertTrue(limiter.tryAcquire("peer-b"), "peer-b has its own bucket")
    }

    @Test
    fun trackedKeysBoundedByEviction() {
        val clock = FakeClock()
        val limiter = TokenBucketRateLimiter(
            capacityPerPeer = 1,
            refillPerSecond = 1,
            clockMs = { clock.nowMs },
            maxTrackedKeys = 4,
        )
        repeat(10) { i -> limiter.tryAcquire("peer-$i") }
        assertTrue(limiter.trackedKeys <= 4, "key table stays bounded (got ${limiter.trackedKeys})")
    }

    @Test
    fun availableTokensStartsAtCapacity() {
        val clock = FakeClock()
        val limiter = TokenBucketRateLimiter(
            capacityPerPeer = 5,
            refillPerSecond = 1,
            clockMs = { clock.nowMs },
        )
        assertEquals(5.0, limiter.availableTokens("unknown-key"))
        limiter.tryAcquire("k")
        assertEquals(4.0, limiter.availableTokens("k"))
    }

    @Test
    fun invalidConstructionRejected() {
        assertFailsWith<IllegalArgumentException> {
            TokenBucketRateLimiter(capacityPerPeer = 0, refillPerSecond = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TokenBucketRateLimiter(capacityPerPeer = 1, refillPerSecond = 0)
        }
    }
}
