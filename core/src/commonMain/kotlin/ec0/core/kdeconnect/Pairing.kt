/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from kdeconnect-android (PairingHandler.kt, upstream SPDX:
 * GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL; GPL-3.0-only
 * option taken). Pairing packet shape, state machine constants, timeout
 * values, and the verification-key derivation are 1:1 with upstream. The
 * coroutine-driven handler itself stays platform-side; this file is the
 * pure model the transport will drive.
 */
package ec0.core.kdeconnect

/** States of the pairing state machine — 1:1 with upstream PairState. */
enum class PairState {
    NotPaired,
    Requested,
    RequestedByPeer,
    Paired,
}

/**
 * `kdeconnect.pair` packet body. Upstream always sets "pair"; "timestamp"
 * (epoch seconds) is required when the peer speaks protocol version >= 8
 * (sent by requestPairing, echoed by acceptPairing upstream only sets "pair").
 */
object PairingPackets {
    const val PROTOCOL_VERSION_TIMESTAMP_SUPPORT = 8

    fun requestPair(timestampSeconds: Long): NetworkPacket = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).also {
        it["pair"] = true
        it["timestamp"] = timestampSeconds
    }

    fun acceptPair(): NetworkPacket = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).also {
        it["pair"] = true
    }

    fun unpair(): NetworkPacket = NetworkPacket(NetworkPacket.PACKET_TYPE_PAIR).also {
        it["pair"] = false
    }

    fun wantsPair(np: NetworkPacket): Boolean = np.getBoolean("pair")

    /** Protocol >= 8 requires the timestamp; missing or skewed timestamps reject the request. */
    fun pairingTimestamp(np: NetworkPacket): Long? = np.getLong("timestamp", -1L).takeIf { it != -1L }
}

/**
 * Pairing-time verification key — what both screens show for the user to
 * compare. Pure model over the two peers' **public key encodings** (upstream:
 * certificate.publicKey.encoded, i.e. SPKI DER bytes).
 */
object VerificationKey {
    // Upstream PairingHandler companion constants
    const val ALLOWED_TIMESTAMP_DIFFERENCE_SECONDS = 1_800 // 30 minutes

    /**
     * Concatenate in a deterministic order so both devices derive the same
     * input (upstream sortedConcat: unsigned byte comparison).
     */
    private fun sortedConcat(a: ByteArray, b: ByteArray): ByteArray =
        if (compareUnsigned(a, b) < 0) b + a else a + b

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val va = a[i].toInt() and 0xff
            val vb = b[i].toInt() and 0xff
            if (va != vb) return va - vb
        }
        return a.size - b.size
    }

    /**
     * Upstream humanReadableHash: SHA-256 over the input, rendered lowercase
     * hex, truncated to the first 8 characters, uppercased.
     */
    private fun humanReadableHash(bytes: ByteArray): String =
        Sha256.digest(bytes).toHexString().substring(0, 8).uppercase()

    /** Protocol >= 8: hash includes the pairing timestamp (epoch seconds, as text). */
    fun getVerificationKey(publicKeyA: ByteArray, publicKeyB: ByteArray, timestampSeconds: Long): String {
        val certsConcat = sortedConcat(publicKeyA, publicKeyB)
        return humanReadableHash(certsConcat + timestampSeconds.toString().encodeToByteArray())
    }

    /** Protocol < 8 (v7): hash over the sorted key concatenation only. */
    fun getVerificationKeyV7(publicKeyA: ByteArray, publicKeyB: ByteArray): String =
        humanReadableHash(sortedConcat(publicKeyA, publicKeyB))
}
