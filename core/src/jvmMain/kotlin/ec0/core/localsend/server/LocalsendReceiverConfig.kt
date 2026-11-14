/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Configuration for the desktop LocalSend v2 receiver (T14). The receiver is
 * the RECEIVING side of the protocol: other devices push files here via the
 * 7-route HTTP contract, and optionally pull offered files via the
 * prepare-download/download API.
 */
package ec0.core.localsend.server

import ec0.core.localsend.DeviceType
import ec0.core.localsend.LocalsendV2
import java.nio.file.Path

/**
 * How the receiver decides which offered files to accept.
 */
enum class AcceptPolicy {
    /** Accept every offered file (no user interaction). */
    AUTO_ACCEPT_ALL,

    /** Decline everything (UI decision wiring lands with T13). */
    AUTO_DECLINE_ALL,

    /** Ask the callback per prepare-upload request. */
    ASK,
}

/**
 * The three prepare-upload outcomes (reference server/v2.rs):
 * DECLINE → 403, ACCEPT(empty) → 204 no-content, ACCEPT(non-empty) → 200.
 * Shared by the desktop (com.sun.net.httpserver) and Android (Ktor) receivers.
 */
sealed interface ReceiverAcceptance {
    /** Explicit decline by the recipient → 403. */
    object DECLINE : ReceiverAcceptance

    /** Acceptance decision — an empty set means "accept nothing" → 204. */
    data class ACCEPT(val fileIds: Set<String>) : ReceiverAcceptance
}

data class LocalsendReceiverConfig(
    val port: Int = LocalsendV2.DEFAULT_SERVER_PORT,
    val alias: String = "ec0",
    val deviceModel: String = "ec0 desktop",
    val deviceType: DeviceType = DeviceType.DESKTOP,
    /** Optional PIN requirement — checked against the `pin` query parameter. */
    val requiredPin: String? = null,
    /** Directory where incoming files are written. */
    val destination: Path = Path.of(System.getProperty("user.home"), "Downloads"),
    /** Offered files for the download API (device → files), keyed by fileId. */
    val offeredFiles: Map<String, java.nio.file.Path> = emptyMap(),
    val acceptPolicy: AcceptPolicy = AcceptPolicy.ASK,
    /** Verify sha256 of uploaded files when the sender supplied one. */
    val verifyChecksums: Boolean = true,
    /**
     * Fingerprint: HTTPS mode = SHA-256 of the device certificate; HTTP mode
     * (this receiver, stdlib server) = a random string, per the reference
     * semantics (packages/core/src/model/discovery.rs).
     */
    val fingerprint: String = java.util.UUID.randomUUID().toString().replace("-", ""),
)
