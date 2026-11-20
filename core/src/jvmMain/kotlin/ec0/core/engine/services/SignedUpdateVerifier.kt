/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature #15 — Ed25519 Signed Release Update Manifest Verifier:
 *   - Verifies release manifests signed offline by the project's pinned Ed25519
 *     release key before offering an in-app update.
 *   - Enforces HTTPS-only download URLs (no embedded credentials, no http/file),
 *     strict 64-char SHA-256 hex digest, and constant-time artifact verification.
 */
package ec0.core.engine.services

import ec0.core.engine.CanonicalJson
import ec0.core.engine.EngineEncoding.decodeHex
import ec0.core.engine.EngineEncoding.toHex
import ec0.core.engine.crypto.Ed25519Identity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.security.MessageDigest

data class SignedReleaseManifest(
    val version: String,
    val channel: String,
    val releasedAtMs: Long,
    val downloadUrl: String,
    val sha256Hex: String,
    val releaseNotes: String = "",
    val signatureHex: String,
) {
    fun unsignedCanonicalBytes(): ByteArray {
        val unsignedObj = buildJsonObject {
            put("channel", channel)
            put("download_url", downloadUrl)
            put("release_notes", releaseNotes)
            put("released_at_ms", releasedAtMs)
            put("sha256_hex", sha256Hex.lowercase())
            put("version", version)
        }
        return CanonicalJson.canonicalizeToString(unsignedObj).encodeToByteArray()
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("version", version)
        put("channel", channel)
        put("released_at_ms", releasedAtMs)
        put("download_url", downloadUrl)
        put("sha256_hex", sha256Hex.lowercase())
        put("release_notes", releaseNotes)
        put("signature_hex", signatureHex.lowercase())
    }

    companion object {
        private val SEMVER_REGEX = Regex("""^(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")
        private val SHA256_REGEX = Regex("""^[0-9a-fA-F]{64}$""")
        private val SIG_HEX_REGEX = Regex("""^[0-9a-fA-F]{128}$""")
        private val ALLOWED_CHANNELS = setOf("stable", "beta", "nightly")

        fun fromJsonObject(obj: JsonObject): SignedReleaseManifest {
            val version = (obj["version"] as? JsonPrimitive)?.content?.trim()
                ?: throw IllegalArgumentException("Missing version")
            require(SEMVER_REGEX.matches(version)) { "Invalid semver: $version" }

            val channel = (obj["channel"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: throw IllegalArgumentException("Missing channel")
            require(channel in ALLOWED_CHANNELS) { "Invalid release channel: $channel" }

            val releasedAtMs = (obj["released_at_ms"] as? JsonPrimitive)?.longOrNull
                ?: throw IllegalArgumentException("Missing released_at_ms")
            require(releasedAtMs > 0L) { "Invalid released_at_ms" }

            val downloadUrl = (obj["download_url"] as? JsonPrimitive)?.content?.trim()
                ?: throw IllegalArgumentException("Missing download_url")
            validateHttpsUrl(downloadUrl)

            val sha256Hex = (obj["sha256_hex"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: throw IllegalArgumentException("Missing sha256_hex")
            require(SHA256_REGEX.matches(sha256Hex)) { "Invalid sha256_hex" }

            val releaseNotes = ((obj["release_notes"] as? JsonPrimitive)?.content ?: "").take(2048)

            val signatureHex = (obj["signature_hex"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: throw IllegalArgumentException("Missing signature_hex")
            require(SIG_HEX_REGEX.matches(signatureHex)) { "Invalid signature_hex" }

            return SignedReleaseManifest(
                version = version,
                channel = channel,
                releasedAtMs = releasedAtMs,
                downloadUrl = downloadUrl,
                sha256Hex = sha256Hex,
                releaseNotes = releaseNotes,
                signatureHex = signatureHex,
            )
        }

        fun validateHttpsUrl(url: String) {
            require(url.length in 12..1024) { "Invalid download_url length" }
            require(!url.any { it.code < 0x20 || it.isWhitespace() }) { "Control/whitespace chars in download_url" }
            val uri = URI(url)
            require(uri.scheme.equals("https", ignoreCase = true)) { "Release download_url must use https://" }
            require(!uri.host.isNullOrBlank()) { "Release download_url must include host" }
            require(uri.userInfo == null) { "Release download_url must not contain embedded credentials" }
        }
    }
}

sealed class UpdateVerificationResult {
    data class UpdateAvailable(val manifest: SignedReleaseManifest) : UpdateVerificationResult()
    data class UpToDate(val currentVersion: String) : UpdateVerificationResult()
    data class Rejected(val reason: String) : UpdateVerificationResult()
}

class SignedUpdateVerifier(
    private val trustedPublicKeyRaw: ByteArray,
) {
    init {
        require(trustedPublicKeyRaw.size == 32) { "Trusted Ed25519 release public key must be 32 bytes" }
    }

    fun verifyManifest(manifest: SignedReleaseManifest, currentVersion: String): UpdateVerificationResult {
        try {
            SignedReleaseManifest.validateHttpsUrl(manifest.downloadUrl)
        } catch (e: Exception) {
            return UpdateVerificationResult.Rejected("Invalid download URL: ${e.message}")
        }
        val sigBytes = runCatching { manifest.signatureHex.decodeHex() }.getOrNull()
            ?: return UpdateVerificationResult.Rejected("Malformed signature hex")
        if (sigBytes.size != 64) {
            return UpdateVerificationResult.Rejected("Invalid Ed25519 signature length")
        }
        val verified = Ed25519Identity.verify(
            publicKeyRaw32 = trustedPublicKeyRaw,
            message = manifest.unsignedCanonicalBytes(),
            signature64 = sigBytes,
        )
        if (!verified) {
            return UpdateVerificationResult.Rejected("Ed25519 manifest signature verification failed")
        }
        return if (isNewerVersion(manifest.version, currentVersion)) {
            UpdateVerificationResult.UpdateAvailable(manifest)
        } else {
            UpdateVerificationResult.UpToDate(currentVersion)
        }
    }

    fun verifyDownloadedArtifact(file: File, expectedSha256Hex: String): Boolean {
        if (!file.isFile) return false
        val expectedBytes = runCatching { expectedSha256Hex.trim().lowercase().decodeHex() }.getOrNull()
            ?: return false
        if (expectedBytes.size != 32) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16_384)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return MessageDigest.isEqual(digest.digest(), expectedBytes)
    }

    companion object {
        private val SEMVER = Regex("""^(\d{1,4})\.(\d{1,4})\.(\d{1,4})$""")

        fun isNewerVersion(candidate: String, current: String): Boolean {
            val cMatch = SEMVER.matchEntire(candidate.trim()) ?: return false
            val curMatch = SEMVER.matchEntire(current.trim()) ?: return false
            for (i in 1..3) {
                val cNum = cMatch.groupValues[i].toInt()
                val curNum = curMatch.groupValues[i].toInt()
                if (cNum != curNum) return cNum > curNum
            }
            return false
        }

        fun signManifestForRelease(
            version: String,
            channel: String,
            releasedAtMs: Long,
            downloadUrl: String,
            sha256Hex: String,
            releaseNotes: String,
            signingKeyPair: ec0.core.engine.crypto.Ed25519KeyPair,
        ): SignedReleaseManifest {
            SignedReleaseManifest.validateHttpsUrl(downloadUrl)
            val draft = SignedReleaseManifest(
                version = version,
                channel = channel,
                releasedAtMs = releasedAtMs,
                downloadUrl = downloadUrl,
                sha256Hex = sha256Hex.lowercase(),
                releaseNotes = releaseNotes,
                signatureHex = "0".repeat(128),
            )
            val sig = signingKeyPair.sign(draft.unsignedCanonicalBytes())
            return draft.copy(signatureHex = sig.toHex())
        }
    }
}
