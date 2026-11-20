/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #13 (Android): Hardware-Backed Master Key Sealing via AndroidKeyStore
 *   - Generates an AES-256-GCM wrapping key inside StrongBox (API 28+, fallback TEE).
 *   - Wraps the 32-byte ec0 master key and stores only `iv || ciphertext || tag`
 *     in `0600` `<appFilesDir>/ec0/master.key.sealed`.
 *   - Gracefully falls back to [FileMasterKeyProvider] on JVM unit tests or devices
 *     where AndroidKeyStore is unavailable.
 *
 * Feature #6 (Android): Bidirectional Focus / DND policy applier.
 */
package ec0.android

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.crypto.EncryptedIdentityStore
import ec0.core.engine.crypto.FileMasterKeyProvider
import ec0.core.engine.crypto.KeySealerBackend
import ec0.core.engine.crypto.MasterKeyProvider
import ec0.core.engine.protocol.FocusSyncProtocol
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock

class AndroidHardwareKeySealer(
    private val configDir: File,
    private val keyAlias: String = "ec0_continuity_master_wrap_v1",
) : MasterKeyProvider {
    private val lock = ReentrantLock()
    private val sealedFile = File(configDir, "master.key.sealed")
    private val fallback = FileMasterKeyProvider(configDir)

    @Volatile
    private var backend: KeySealerBackend = KeySealerBackend.FILE_0600

    override val activeBackend: KeySealerBackend
        get() = backend

    override fun loadOrCreateMasterKey(): ByteArray = lock.withLock {
        val hwResult = runCatching { loadOrCreateHardwareSealedLocked() }.getOrNull()
        if (hwResult != null && hwResult.size == 32) {
            return@withLock hwResult
        }
        backend = KeySealerBackend.FILE_0600
        fallback.loadOrCreateMasterKey()
    }

    private fun loadOrCreateHardwareSealedLocked(): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapKey = (ks.getKey(keyAlias, null) as? SecretKey) ?: generateHardwareWrapKey()

        if (sealedFile.isFile) {
            val raw = sealedFile.readText().trim().decodeHex()
            if (raw.size > 12 + 16) {
                val iv = raw.copyOfRange(0, 12)
                val ct = raw.copyOfRange(12, raw.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(128, iv))
                val plain = cipher.doFinal(ct)
                if (plain.size == 32) {
                    if (backend == KeySealerBackend.FILE_0600) {
                        backend = KeySealerBackend.ANDROID_KEYSTORE_TEE
                    }
                    return plain
                }
            }
        }

        // Preserve any legacy unsealed master.key seamlessly on upgrade
        val master = fallback.readExistingOrNull() ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey)
        val iv = cipher.iv
        require(iv.size == 12) { "Unexpected GCM IV length" }
        val ct = cipher.doFinal(master)
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)

        EncryptedIdentityStore.ensureDirectoryPermissions(configDir)
        EncryptedIdentityStore.atomicWriteText(sealedFile, combined.toHex())
        if (backend == KeySealerBackend.FILE_0600) {
            backend = KeySealerBackend.ANDROID_KEYSTORE_TEE
        }
        return master
    }

    private fun generateHardwareWrapKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val strongBoxKey = runCatching {
                val spec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setIsStrongBoxBacked(true)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }.getOrNull()
            if (strongBoxKey != null) {
                backend = KeySealerBackend.ANDROID_KEYSTORE_STRONGBOX
                return strongBoxKey
            }
        }
        val teeSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(teeSpec)
        backend = KeySealerBackend.ANDROID_KEYSTORE_TEE
        return keyGen.generateKey()
    }
}

/**
 * Feature #6: Applies synced Focus / DND state to Android NotificationManager
 * ONLY if the user explicitly granted Notification Policy Access.
 */
object AndroidFocusPolicyApplier {
    fun applyFocusMode(context: Context, mode: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        if (!nm.isNotificationPolicyAccessGranted) return false
        val filter = when (mode.lowercase()) {
            FocusSyncProtocol.MODE_OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
            FocusSyncProtocol.MODE_DND,
            FocusSyncProtocol.MODE_WORK,
            FocusSyncProtocol.MODE_MEETING -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            FocusSyncProtocol.MODE_SLEEP -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> return false
        }
        return runCatching {
            nm.setInterruptionFilter(filter)
            true
        }.getOrDefault(false)
    }
}
