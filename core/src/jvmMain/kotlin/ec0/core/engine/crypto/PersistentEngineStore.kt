/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Persistent Encrypted Keystore & Peer Pin/Endpoint Store (`~/.config/ec0/`):
 *   1. EncryptedIdentityStore — persists the node's 32-byte Ed25519 private seed
 *      encrypted at rest with RFC 8439 ChaCha20-Poly1305 (`AeadFrameCodec.encryptRaw`)
 *      under an HKDF-SHA256 key derived from a local master secret file (`0600` POSIX
 *      permissions) or explicit passphrase, using atomic temp-file rename.
 *   2. PersistentPinStore — implements `PinStore`, atomically persisting `PinnedPeer`
 *      entries (`TRUSTED_QR` / `TOFU_PINNED`) and last-known peer `(host, port)`
 *      endpoints to JSON on disk so devices reconnect immediately across restarts.
 */
package ec0.core.engine.crypto

import ec0.core.engine.CanonicalJson
import ec0.core.engine.EngineConstants
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.HkdfSha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PersistedEndpoint(
    val host: String,
    val port: Int,
    val updatedAtMs: Long,
)

/**
 * Encrypts and persists the node's long-term [Ed25519KeyPair] on disk using
 * ChaCha20-Poly1305 (`AeadFrameCodec`) and HKDF-SHA256 key derivation.
 */
class EncryptedIdentityStore(
    private val configDir: File = defaultConfigDir(),
    private val secretOverride: ByteArray? = null,
) {
    private val lock = ReentrantLock()
    private val identityFile = File(configDir, "identity.enc.json")
    private val masterSecretFile = File(configDir, "master.key")

    /**
     * Loads the persisted [Ed25519KeyPair] from [identityFile], or generates and
     * atomically saves a new one if none exists yet.
     */
    fun loadOrCreateIdentity(): Ed25519KeyPair = lock.withLock {
        if (identityFile.isFile) {
            return loadIdentityLocked()
        }
        val fresh = Ed25519Identity.generate()
        saveIdentityLocked(fresh)
        fresh
    }

    fun saveIdentity(keyPair: Ed25519KeyPair) = lock.withLock {
        saveIdentityLocked(keyPair)
    }

    private fun saveIdentityLocked(keyPair: Ed25519KeyPair) {
        ensureDirectoryPermissions(configDir)
        val masterSecret = resolveMasterSecretLocked()
        val rng = SecureRandom()
        val salt16 = ByteArray(16).also { rng.nextBytes(it) }
        val nonce12 = ByteArray(EngineConstants.AEAD_NONCE_BYTES).also { rng.nextBytes(it) }
        val wrappingKey = HkdfSha256.extractAndExpand(
            salt = salt16,
            ikm = masterSecret,
            info = KEYSTORE_HKDF_INFO.encodeToByteArray(),
            length = EngineConstants.SESSION_KEY_BYTES,
        )
        val aad = keyPair.deviceId.encodeToByteArray()
        val ciphertext = AeadFrameCodec.encryptRaw(
            key32 = wrappingKey,
            nonce12 = nonce12,
            aad = aad,
            plaintext = keyPair.privateSeedRaw,
        )
        val root = buildJsonObject {
            put("version", 1)
            put("device_id", keyPair.deviceId)
            put("public_key_hex", keyPair.publicKeyRaw.toHex())
            put("salt_hex", salt16.toHex())
            put("nonce_hex", nonce12.toHex())
            put("ciphertext_hex", ciphertext.toHex())
        }
        atomicWriteText(identityFile, CanonicalJson.canonicalizeToString(root))
        restrictFilePermissions0600(identityFile)
    }

    private fun loadIdentityLocked(): Ed25519KeyPair {
        val rawText = identityFile.readText()
        val obj = Json.parseToJsonElement(rawText) as? JsonObject
            ?: throw AeadSecurityException("Corrupted identity store JSON")
        val deviceId = (obj["device_id"] as? JsonPrimitive)?.content
            ?: throw AeadSecurityException("Missing device_id in identity store")
        val pubHex = (obj["public_key_hex"] as? JsonPrimitive)?.content
            ?: throw AeadSecurityException("Missing public_key_hex in identity store")
        val saltHex = (obj["salt_hex"] as? JsonPrimitive)?.content
            ?: throw AeadSecurityException("Missing salt_hex in identity store")
        val nonceHex = (obj["nonce_hex"] as? JsonPrimitive)?.content
            ?: throw AeadSecurityException("Missing nonce_hex in identity store")
        val ctHex = (obj["ciphertext_hex"] as? JsonPrimitive)?.content
            ?: throw AeadSecurityException("Missing ciphertext_hex in identity store")

        val masterSecret = resolveMasterSecretLocked()
        val wrappingKey = HkdfSha256.extractAndExpand(
            salt = saltHex.decodeHex(),
            ikm = masterSecret,
            info = KEYSTORE_HKDF_INFO.encodeToByteArray(),
            length = EngineConstants.SESSION_KEY_BYTES,
        )
        val seed32 = AeadFrameCodec.decryptRaw(
            key32 = wrappingKey,
            nonce12 = nonceHex.decodeHex(),
            aad = deviceId.encodeToByteArray(),
            ciphertextWithTag = ctHex.decodeHex(),
        )
        val kp = Ed25519Identity.fromRawKeyPair(seed32, pubHex.decodeHex())
        if (!kp.deviceId.equals(deviceId, ignoreCase = true)) {
            throw AeadSecurityException("Decrypted identity deviceId mismatch")
        }
        return kp
    }

    private fun resolveMasterSecretLocked(): ByteArray {
        if (secretOverride != null) {
            require(secretOverride.isNotEmpty()) { "secretOverride must not be empty" }
            return secretOverride.copyOf()
        }
        ensureDirectoryPermissions(configDir)
        if (masterSecretFile.isFile) {
            val hex = masterSecretFile.readText().trim()
            val bytes = hex.decodeHex()
            if (bytes.size == 32) return bytes
        }
        val freshSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        atomicWriteText(masterSecretFile, freshSecret.toHex())
        restrictFilePermissions0600(masterSecretFile)
        return freshSecret
    }

    companion object {
        private const val KEYSTORE_HKDF_INFO = "ec0-v1-local-keystore-aead"

        fun defaultConfigDir(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(System.getProperty("user.home", "."), ".config")
            return File(base, "ec0")
        }

        internal fun ensureDirectoryPermissions(dir: File) {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            runCatching {
                Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }

        internal fun restrictFilePermissions0600(file: File) {
            runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            }
        }

        internal fun atomicWriteText(target: File, content: String) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            restrictFilePermissions0600(tmp)
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}

/**
 * Persistent implementation of [PinStore] backed by `~/.config/ec0/pinned_peers.json`.
 * Also stores each peer's last verified `(host, port)` endpoint for Tier-2 reconnect.
 */
class PersistentPinStore(
    private val storeFile: File = File(EncryptedIdentityStore.defaultConfigDir(), "pinned_peers.json"),
    allowAutoTofuOnUnknownPeer: Boolean = false,
) : PinStore {
    private val lock = ReentrantLock()
    private val delegate = InMemoryPinStore(allowAutoTofuOnUnknownPeer = allowAutoTofuOnUnknownPeer)
    private val endpoints = ConcurrentHashMap<String, PersistedEndpoint>()

    init {
        loadFromDisk()
    }

    override fun get(deviceId: String): PinnedPeer? = lock.withLock {
        delegate.get(deviceId.lowercase())
    }

    override fun all(): List<PinnedPeer> = lock.withLock {
        delegate.all()
    }

    override fun verifyOrPin(
        deviceId: String,
        publicKeyRaw: ByteArray,
        alias: String,
        trustLevel: TrustLevel,
        nowMs: Long,
    ): PinnedPeer = lock.withLock {
        val normalizedId = deviceId.lowercase()
        val pinned = delegate.verifyOrPin(
            deviceId = normalizedId,
            publicKeyRaw = publicKeyRaw,
            alias = alias,
            trustLevel = trustLevel,
            nowMs = nowMs,
        )
        persistToDiskLocked()
        pinned
    }

    override fun unpair(deviceId: String): Boolean = lock.withLock {
        val normalizedId = deviceId.lowercase()
        val removed = delegate.unpair(normalizedId)
        endpoints.remove(normalizedId)
        if (removed) {
            persistToDiskLocked()
        }
        removed
    }

    fun recordKnownEndpoint(
        deviceId: String,
        host: String,
        port: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) = lock.withLock {
        val normalizedId = deviceId.lowercase()
        endpoints[normalizedId] = PersistedEndpoint(host = host, port = port, updatedAtMs = nowMs)
        persistToDiskLocked()
    }

    fun getKnownEndpoint(deviceId: String): PersistedEndpoint? =
        endpoints[deviceId.lowercase()]

    fun allKnownEndpoints(): Map<String, PersistedEndpoint> =
        HashMap(endpoints)

    private fun loadFromDisk() = lock.withLock {
        if (!storeFile.isFile) return@withLock
        val root = runCatching {
            Json.parseToJsonElement(storeFile.readText()) as? JsonObject
        }.getOrNull() ?: return@withLock

        val peersArr = root["peers"] as? JsonArray ?: JsonArray(emptyList())
        for (el in peersArr) {
            val obj = el as? JsonObject ?: continue
            val deviceId = (obj["device_id"] as? JsonPrimitive)?.content?.lowercase() ?: continue
            val pubHex = (obj["public_key_hex"] as? JsonPrimitive)?.content ?: continue
            val alias = (obj["alias"] as? JsonPrimitive)?.content ?: deviceId.take(8)
            val trustStr = (obj["trust_level"] as? JsonPrimitive)?.content ?: TrustLevel.TOFU_PINNED.name
            val pinnedAt = (obj["pinned_at_ms"] as? JsonPrimitive)?.longOrNull ?: 0L
            val trust = runCatching { TrustLevel.valueOf(trustStr) }.getOrDefault(TrustLevel.TOFU_PINNED)

            runCatching {
                delegate.verifyOrPin(
                    deviceId = deviceId,
                    publicKeyRaw = pubHex.decodeHex(),
                    alias = alias,
                    trustLevel = trust,
                    nowMs = pinnedAt,
                )
            }
            val host = (obj["last_host"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val port = (obj["last_port"] as? JsonPrimitive)?.intOrNull
            val endpointUpdated = (obj["endpoint_updated_ms"] as? JsonPrimitive)?.longOrNull ?: pinnedAt
            if (host != null && port != null && port in 1..65535) {
                endpoints[deviceId] = PersistedEndpoint(host = host, port = port, updatedAtMs = endpointUpdated)
            }
        }
    }

    private fun persistToDiskLocked() {
        val parent = storeFile.parentFile
        if (parent != null) {
            EncryptedIdentityStore.ensureDirectoryPermissions(parent)
        }
        val peersJson = buildJsonArray {
            for (peer in delegate.all()) {
                val ep = endpoints[peer.deviceId.lowercase()]
                add(
                    buildJsonObject {
                        put("device_id", peer.deviceId.lowercase())
                        put("public_key_hex", peer.publicKeyRaw.toHex())
                        put("alias", peer.alias)
                        put("trust_level", peer.trustLevel.name)
                        put("pinned_at_ms", peer.pinnedAtMs)
                        if (ep != null) {
                            put("last_host", ep.host)
                            put("last_port", ep.port)
                            put("endpoint_updated_ms", ep.updatedAtMs)
                        }
                    },
                )
            }
        }
        val root = buildJsonObject {
            put("version", 1)
            put("peers", peersJson)
        }
        EncryptedIdentityStore.atomicWriteText(storeFile, CanonicalJson.canonicalizeToString(root))
    }
}
