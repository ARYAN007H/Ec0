package ec0.core.localsend

import kotlinx.serialization.json.Json

/**
 * LocalSend protocol v2 constants — ported from the vendored reference
 * implementation (localsend-upstream `packages/core`, Rust):
 * `src/model/discovery.rs` (`PROTOCOL_VERSION_V2`), `src/multicast/mod.rs`
 * (UDP :53317), `src/http/server/mod.rs` (route table).
 */
object LocalsendV2 {
    /** Protocol version (major.minor) implemented for the v2 protocol. */
    const val PROTOCOL_VERSION = "2.2"

    /** UDP port used for multicast discovery announcements. */
    const val DISCOVERY_PORT = 53317

    /** Default TCP port of the LocalSend HTTP server. */
    const val DEFAULT_SERVER_PORT = 53317

    /** Base path of every v2 HTTP route. */
    const val API_PATH_V2 = "/api/localsend/v2"

    /**
     * Shared JSON codec for all v2 DTOs.
     *
     * Mirrors the Rust serde configuration exactly:
     * - `ignoreUnknownKeys = true` — serde ignores unknown fields by default.
     * - `explicitNulls = false` — mirrors `#[serde(skip_serializing_if = "Option::is_none")]`.
     * - `encodeDefaults = true` — mirrors `#[serde(default)]` fields
     *   (e.g. `download`) being *always* serialized, even when `false`.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
}
