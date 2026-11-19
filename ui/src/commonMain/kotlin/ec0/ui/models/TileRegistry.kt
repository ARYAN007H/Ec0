/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B3 — Dynamic tile registry (P1): the HOME dashboard renders exactly the
 * tiles the peer's capabilities ∩ local support produce — no hardcoded set.
 * Every tile carries its deviceId so actions target the right peer
 * (firstOrNull targeting is banned). Pure functions — unit-tested.
 */
package ec0.ui.models


/**
 * Computes the registry: for every peer, tiles = peer capabilities ∩ local
 * support. Peers with no overlap contribute nothing. [onlineByDevice]
 * disables (but keeps) tiles for offline peers.
 */

/** One registry-produced dashboard tile, scoped to a specific peer. */
data class CapabilityTile(
    /** EngineCapability wire name ("mirror", "cam", "file" …) — keeps :ui core-free. */
    val capabilityId: String,
    val deviceId: String,
    val enabled: Boolean,
) {
    /** Stable UI key — one tile per (capability, device). */
    val id: String get() = "$capabilityId:$deviceId"
}

/**
 * Canonical tile order (the design board's bento layout, left→right) by
 * wire name. Unknown ids sort last, alphabetically.
 */
val CANONICAL_TILE_ORDER: List<String> = listOf(
    "mirror",
    "cam",
    "file",
    "clip",
    "notif",
    "input",
    "media",
    "find_my",
    "battery",
)

fun computeTileRegistry(
    peerCapabilities: Map<String, Set<String>>,
    localSupport: Set<String>,
    onlineByDevice: Map<String, Boolean> = emptyMap(),
): List<CapabilityTile> {
    val tiles = mutableListOf<CapabilityTile>()
    for ((deviceId, peerCaps) in peerCapabilities) {
        val online = onlineByDevice[deviceId] ?: true
        for (capabilityId in peerCaps.intersect(localSupport)) {
            tiles += CapabilityTile(
                capabilityId = capabilityId,
                deviceId = deviceId,
                enabled = online,
            )
        }
    }
    return tiles.sortedWith(
        compareBy({ tile ->
            CANONICAL_TILE_ORDER.indexOf(tile.capabilityId).let { if (it == -1) CANONICAL_TILE_ORDER.size else it }
        }, { tile -> tile.capabilityId }, { tile -> tile.deviceId }),
    )
}
