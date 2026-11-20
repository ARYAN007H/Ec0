/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #13 — Hardware-Backed & OS-Keyring Master Key Sealing:
 *   - Linux: seals the 32-byte `master.key` inside the desktop Secret Service
 *     (`org.freedesktop.secrets`, GNOME Keyring / KWallet) via `secret-tool`,
 *     passing the secret strictly over stdin (NEVER via CLI argv, preventing
 *     exposure in `/proc/<pid>/cmdline` or `ps`).
 *   - Android: Android app provides `AndroidHardwareKeySealer` backed by
 *     AndroidKeyStore StrongBox/TEE AES-256-GCM wrapping.
 *   - Fallback: `0600` file-backed `master.key` inside `0700` config dir when
 *     no desktop keyring daemon is running (e.g. headless CI or minimal TTY).
 */
package ec0.core.engine.crypto

import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class KeySealerBackend(val displayName: String) {
    SECRET_SERVICE("Linux Secret Service (Keyring)"),
    ANDROID_KEYSTORE_STRONGBOX("Android Keystore (StrongBox)"),
    ANDROID_KEYSTORE_TEE("Android Keystore (TEE)"),
    FILE_0600("Local 0600 Key File"),
}

interface MasterKeyProvider {
    val activeBackend: KeySealerBackend
    fun loadOrCreateMasterKey(): ByteArray
}

/**
 * Fallback 32-byte master key provider storing hex inside `0600` `<configDir>/master.key`.
 */
class FileMasterKeyProvider(
    private val configDir: File = EncryptedIdentityStore.defaultConfigDir(),
) : MasterKeyProvider {
    private val lock = ReentrantLock()
    private val masterSecretFile = File(configDir, "master.key")

    override val activeBackend: KeySealerBackend = KeySealerBackend.FILE_0600

    override fun loadOrCreateMasterKey(): ByteArray = lock.withLock {
        EncryptedIdentityStore.ensureDirectoryPermissions(configDir)
        if (masterSecretFile.isFile) {
            val hex = masterSecretFile.readText().trim()
            val bytes = runCatching { hex.decodeHex() }.getOrNull()
            if (bytes != null && bytes.size == 32) {
                return@withLock bytes
            }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        EncryptedIdentityStore.atomicWriteText(masterSecretFile, fresh.toHex())
        EncryptedIdentityStore.restrictFilePermissions0600(masterSecretFile)
        fresh
    }

    fun readExistingOrNull(): ByteArray? = lock.withLock {
        if (!masterSecretFile.isFile) return@withLock null
        val hex = masterSecretFile.readText().trim()
        runCatching { hex.decodeHex() }.getOrNull()?.takeIf { it.size == 32 }
    }
}

/**
 * Abstraction for running `secret-tool` (injectable for deterministic unit tests).
 */
interface SecretToolCommandRunner {
    data class ExecResult(val exitCode: Int, val stdout: String)

    fun run(argv: List<String>, stdinText: String? = null, timeoutMs: Long = 3_000L): ExecResult
}

object SystemSecretToolRunner : SecretToolCommandRunner {
    override fun run(argv: List<String>, stdinText: String?, timeoutMs: Long): SecretToolCommandRunner.ExecResult {
        return try {
            val proc = ProcessBuilder(argv).redirectErrorStream(false).start()
            if (stdinText != null) {
                proc.outputStream.use { out ->
                    out.write(stdinText.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            } else {
                proc.outputStream.close()
            }
            val stdout = proc.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                SecretToolCommandRunner.ExecResult(-1, "")
            } else {
                SecretToolCommandRunner.ExecResult(proc.exitValue(), stdout)
            }
        } catch (_: Exception) {
            SecretToolCommandRunner.ExecResult(-1, "")
        }
    }
}

/**
 * Linux Secret Service (`secret-tool`) master key provider with automatic migration
 * from an existing `master.key` file and safe fallback to [FileMasterKeyProvider].
 */
class LinuxSecretServiceMasterKeyProvider(
    private val configDir: File = EncryptedIdentityStore.defaultConfigDir(),
    private val serviceAttr: String = "ec0-continuity-keystore",
    private val accountAttr: String = "master-key-v1",
    private val runner: SecretToolCommandRunner = SystemSecretToolRunner,
) : MasterKeyProvider {
    private val lock = ReentrantLock()
    private val fileFallback = FileMasterKeyProvider(configDir)

    @Volatile
    private var backendState: KeySealerBackend = KeySealerBackend.FILE_0600

    override val activeBackend: KeySealerBackend
        get() = backendState

    override fun loadOrCreateMasterKey(): ByteArray = lock.withLock {
        // 1. Try retrieving from Secret Service first.
        val existingHex = lookupInKeyring()
        if (existingHex != null) {
            val decoded = runCatching { existingHex.decodeHex() }.getOrNull()
            if (decoded != null && decoded.size == 32) {
                backendState = KeySealerBackend.SECRET_SERVICE
                return@withLock decoded
            }
        }

        // 2. If a legacy 0600 master.key already exists, preserve it (zero data loss)
        //    and attempt to seal a copy into Secret Service.
        val legacyKey = fileFallback.readExistingOrNull()
        val keyToUse = legacyKey ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

        val storedInKeyring = storeInKeyring(keyToUse.toHex())
        if (storedInKeyring) {
            // Verify readback before declaring SECRET_SERVICE active.
            val verifyHex = lookupInKeyring()
            if (verifyHex != null && verifyHex.equals(keyToUse.toHex(), ignoreCase = true)) {
                backendState = KeySealerBackend.SECRET_SERVICE
                return@withLock keyToUse
            }
        }

        // 3. Fallback cleanly to 0600 file when Secret Service is unavailable/locked.
        backendState = KeySealerBackend.FILE_0600
        if (legacyKey != null) {
            return@withLock legacyKey
        }
        EncryptedIdentityStore.ensureDirectoryPermissions(configDir)
        val masterFile = File(configDir, "master.key")
        EncryptedIdentityStore.atomicWriteText(masterFile, keyToUse.toHex())
        EncryptedIdentityStore.restrictFilePermissions0600(masterFile)
        keyToUse
    }

    private fun lookupInKeyring(): String? {
        val result = runner.run(
            argv = listOf("secret-tool", "lookup", "service", serviceAttr, "account", accountAttr),
            stdinText = null,
        )
        if (result.exitCode != 0) return null
        val trimmed = result.stdout.trim()
        return trimmed.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }
    }

    private fun storeInKeyring(secretHex: String): Boolean {
        // CRITICAL: secretHex is passed ONLY via stdin, NEVER in argv.
        val result = runner.run(
            argv = listOf(
                "secret-tool",
                "store",
                "--label=ec0 Continuity Master Key",
                "service",
                serviceAttr,
                "account",
                accountAttr,
            ),
            stdinText = secretHex,
        )
        return result.exitCode == 0
    }
}
