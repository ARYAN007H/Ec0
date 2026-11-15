/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Unit tests for Android settings persistence (DataStore Preferences roundtrip
 * including alias, darkTheme, requirePin, pin, synergyProbeEnabled, and
 * trustedFingerprints set) and explicit File(context.filesDir, "ec0") engine
 * stores for AndroidCompanionRuntime.
 */
package ec0.android

import ec0.core.engine.android.AndroidCompanionRuntime
import ec0.core.engine.android.AndroidPlatformCallbacks
import ec0.core.engine.crypto.Ed25519Identity
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.PersistentPinStore
import ec0.core.engine.crypto.TrustLevel
import ec0.core.localsend.DeviceType
import ec0.core.localsend.ProtocolType
import ec0.ui.models.SettingsState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

class AndroidSettingsStoreTest {

    @Test
    fun settingsStoreRoundtripPersistsAliasThemePinAndTrustedFingerprintsSet() = runBlocking {
        val tempDir = Files.createTempDirectory("ec0-android-datastore-test").toFile()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val defaultSettings = SettingsState(
                alias = "OPPO A5x",
                deviceModel = "OPPO CPH2733IN",
                deviceType = DeviceType.MOBILE,
                protocol = ProtocolType.HTTP,
            )

            val store1 = AndroidSettingsStore.create(
                storageDir = tempDir,
                scope = scope1,
                fileName = "roundtrip.preferences_pb",
            )

            // 1. Unpopulated store returns defaults
            val initial = store1.load(defaultSettings)
            assertEquals("OPPO A5x", initial.alias)
            assertNull(initial.darkTheme)
            assertTrue(initial.trustedFingerprints.isEmpty())

            // 2. Save full custom SettingsState including trustedFingerprints set
            val trustedSet = setOf(
                "fp-arch-desktop-1111",
                "fp-workstation-2222",
                "fp-laptop-3333",
            )
            val savedState = defaultSettings.copy(
                alias = "Aryan-A5x-5G",
                port = 53318,
                protocol = ProtocolType.HTTPS,
                downloadDir = "/storage/emulated/0/Download/ec0",
                requirePin = true,
                pin = "482910",
                synergyProbeEnabled = true,
                darkTheme = true,
                trustedFingerprints = trustedSet,
            )
            store1.save(savedState)

            val loadedSameSession = store1.load(defaultSettings)
            assertEquals(savedState, loadedSameSession)
            assertEquals(trustedSet, loadedSameSession.trustedFingerprints)
        } finally {
            scope1.coroutineContext[Job]?.cancelAndJoin()
        }

        // 3. Re-open from disk in a new CoroutineScope (simulating process restart)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store2 = AndroidSettingsStore.create(
                storageDir = tempDir,
                scope = scope2,
                fileName = "roundtrip.preferences_pb",
            )
            val reloaded = store2.load(
                SettingsState(
                    alias = "Fallback",
                    deviceModel = "Fallback",
                    deviceType = DeviceType.MOBILE,
                    protocol = ProtocolType.HTTP,
                ),
            )
            assertEquals("Aryan-A5x-5G", reloaded.alias)
            assertEquals(53318, reloaded.port)
            assertEquals(ProtocolType.HTTPS, reloaded.protocol)
            assertEquals("/storage/emulated/0/Download/ec0", reloaded.downloadDir)
            assertEquals(true, reloaded.requirePin)
            assertEquals("482910", reloaded.pin)
            assertEquals(true, reloaded.synergyProbeEnabled)
            assertEquals(true, reloaded.darkTheme)
            assertEquals(
                setOf("fp-arch-desktop-1111", "fp-workstation-2222", "fp-laptop-3333"),
                reloaded.trustedFingerprints,
            )

            // 4. Write-on-change incremental update (theme Light -> System, add trusted peer)
            val updatedLight = store2.update(reloaded) { s ->
                s.copy(
                    darkTheme = false,
                    trustedFingerprints = s.trustedFingerprints + "fp-pad-4444",
                )
            }
            assertEquals(false, updatedLight.darkTheme)
            assertTrue("fp-pad-4444" in updatedLight.trustedFingerprints)
            assertEquals(4, store2.load().trustedFingerprints.size)

            val updatedSystem = store2.update(reloaded) { s -> s.copy(darkTheme = null) }
            assertNull(updatedSystem.darkTheme)
            assertNull(store2.load().darkTheme)
        } finally {
            scope2.coroutineContext[Job]?.cancelAndJoin()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun androidCompanionRuntimeUsesExplicitAppFilesDirEc0Stores() {
        val fakeContextFilesDir = Files.createTempDirectory("ec0-android-files-dir").toFile()
        val explicitStoreDir = File(fakeContextFilesDir, "ec0")
        val runtime1 = AndroidCompanionRuntime.createWithPersistentStores(
            storeDir = explicitStoreDir,
            phoneModelName = "Aryan-A5x",
            callbacks = object : AndroidPlatformCallbacks {},
            listenPort = 0,
        )
        try {
            val identityFile = File(explicitStoreDir, "identity.enc.json")
            val masterKeyFile = File(explicitStoreDir, "master.key")
            assertTrue(identityFile.isFile, "Expected identity.enc.json inside File(filesDir, \"ec0\")")
            assertTrue(masterKeyFile.isFile, "Expected master.key inside File(filesDir, \"ec0\")")

            val peerKey = Ed25519Identity.generate()
            runtime1.node.pinStore.verifyOrPin(
                deviceId = peerKey.deviceId,
                publicKeyRaw = peerKey.publicKeyRaw,
                alias = "arch-desktop",
                trustLevel = TrustLevel.TRUSTED_QR,
                nowMs = 1710000000000L,
            )
            val pinFile = File(explicitStoreDir, "pinned_peers.json")
            assertTrue(pinFile.isFile, "Expected pinned_peers.json inside File(filesDir, \"ec0\")")

            val firstDeviceId = runtime1.phoneIdentity.deviceId

            // Re-instantiate against the same explicit File(context.filesDir, "ec0") directory
            val runtime2 = AndroidCompanionRuntime.createWithPersistentStores(
                storeDir = explicitStoreDir,
                phoneModelName = "Aryan-A5x",
                callbacks = object : AndroidPlatformCallbacks {},
                listenPort = 0,
            )
            try {
                assertEquals(firstDeviceId, runtime2.phoneIdentity.deviceId)
                val restoredPin = runtime2.node.pinStore.get(peerKey.deviceId)
                assertEquals("arch-desktop", restoredPin?.alias)
                assertEquals(TrustLevel.TRUSTED_QR, restoredPin?.trustLevel)

                // Also verify EncryptedIdentityStore.forAppFilesDir and PersistentPinStore.forAppFilesDir
                val idFromHelper = EncryptedIdentityStore.forAppFilesDir(fakeContextFilesDir).loadOrCreateIdentity()
                val pinsFromHelper = PersistentPinStore.forAppFilesDir(fakeContextFilesDir)
                assertEquals(firstDeviceId, idFromHelper.deviceId)
                assertEquals("arch-desktop", pinsFromHelper.get(peerKey.deviceId)?.alias)
            } finally {
                runtime2.close()
            }
        } finally {
            runtime1.close()
            fakeContextFilesDir.deleteRecursively()
        }
    }
}
