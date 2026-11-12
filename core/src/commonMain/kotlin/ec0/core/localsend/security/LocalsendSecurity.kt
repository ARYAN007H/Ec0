package ec0.core.localsend.security

/**
 * PIN / verification model of LocalSend v2 (shape-level port; crypto wiring
 * lands with the ktor server/client integration).
 *
 * Reference evidence:
 * - PIN: `packages/core/src/http/server/common/pin.rs` — the sender may
 *   require a PIN; the peer sends it as the `pin` **query parameter**.
 *   At most [MAX_PIN_ATTEMPTS] failed attempts per source IP, then the
 *   responder answers `429 Too Many Requests` until a correct PIN arrives
 *   (success clears the counter). A wrong PIN is answered `401/403`
 *   (decline path in `server/v2.rs`).
 * - Fingerprint: `packages/core/src/model/discovery.rs` — in HTTPS mode the
 *   fingerprint is the **SHA-256 hash of the device certificate**; in HTTP
 *   mode it is a random string. Clients pin TLS against the announced
 *   fingerprint (`src/http/client/server_cert_verifier.rs`,
 *   `tests/v2_tls_pinning.rs`).
 * - Session tokens (v2.2): `packages/core/src/crypto/token.rs` — file tokens
 *   issued by prepare-upload are signed (timestamp + nonce, ed25519-style
 *   signing keys); the server verifies token signature, freshness and nonce
 *   on upload/download. This port treats tokens as opaque strings.
 */
object LocalsendSecurity {
    /** Query parameter carrying the PIN. */
    const val PIN_QUERY_PARAM = "pin"

    /** Failed PIN attempts allowed per source IP before 429. */
    const val MAX_PIN_ATTEMPTS = 3

    /**
     * Pure decision model of `check_pin` (pin.rs): whether the supplied pin
     * unlocks the request, and what the responder should answer.
     */
    fun evaluatePin(
        requiredPin: String?,
        suppliedPin: String?,
        previousFailures: Int,
    ): PinDecision {
        if (requiredPin == null) return PinDecision.ALLOWED
        if (previousFailures >= MAX_PIN_ATTEMPTS) return PinDecision.BLOCKED_TOO_MANY_ATTEMPTS
        return if (suppliedPin != null && suppliedPin == requiredPin) {
            PinDecision.ALLOWED
        } else {
            PinDecision.DENIED_WRONG_PIN
        }
    }
}

enum class PinDecision {
    /** No PIN required, or correct PIN supplied (also resets the failure counter). */
    ALLOWED,

    /** Wrong or missing PIN — decline (401/403). */
    DENIED_WRONG_PIN,

    /** Failure limit exceeded for this source IP — 429 Too Many Requests. */
    BLOCKED_TOO_MANY_ATTEMPTS,
}
