/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * JVM unit tests for Android Ecosystem Pro security hardening:
 *   - Feature #13: AndroidHardwareKeySealer graceful fallback + EncryptedIdentityStore round-trip.
 *   - Feature #14: Ec0CompanionService.crashLoopWatchdog circuit-breaker protection.
 */
package ec0.android

import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.KeySealerBackend
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidEcosystemProTest {
    @Test
    fun androidHardwareKeySealerFallsBackCleanlyOnJvmAndPersistsIdentity() {
        val dir = Files.createTempDirectory("ec0-android-ks").toFile()
        try {
            val sealer = AndroidHardwareKeySealer(configDir = dir)
            val store = EncryptedIdentityStore(configDir = dir, masterKeyProvider = sealer)
            val id1 = store.loadOrCreateIdentity()
            assertEquals(KeySealerBackend.FILE_0600, sealer.activeBackend)
            val id2 = store.loadOrCreateIdentity()
            assertEquals(id1.deviceId, id2.deviceId)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun companionCrashLoopWatchdogProtectsAgainstRestartStorms() {
        val watchdog = Ec0CompanionService.crashLoopWatchdog
        watchdog.reset()
        assertFalse(watchdog.isCircuitTripped(1000L))
        assertTrue(watchdog.recordFailure(1000L) > 0L)
        watchdog.recordHealthyStart()
        watchdog.reset()
        assertFalse(watchdog.isCircuitTripped(2000L))
    }
}
