/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * B3 — Dynamic tile registry (P1): the HOME dashboard renders exactly the
 * tiles the peer's capabilities ∩ local support produce — no hardcoded set.
 * Every tile carries its deviceId so actions target the right peer
 * (firstOrNull targeting is banned). Pure functions — unit-tested.
 */
package ec0.ui.models

import ec0.core.engine.EngineCapability

/** One registry-produced dashboard tile, scoped to a specific peer. */
data class CapabilityTile(
    val capability: EngineCapability,
    val deviceId: String,
    val enabled: Boolean,
) {
    /** Stable UI key — one tile per (capability, device). */
    val id: String get() = "${capability.wireName}:${deviceId}"
}

/**
 * Canonical tile order (the design board's bento layout, left→right).
 * Capabilities outside this list sort last, alphabetically by wireName.
 */
val CANONICAL_TILE_ORDER: List<EngineCapability> = listOf(
    EngineCapability.MIRROR,
    EngineCapability.CAMERA,
    EngineCapability.FILE,
    EngineCapability.CLIPBOARD,
    EngineCapability.NOTIFICATION,
    EngineCapability.INPUT,
    EngineCapability.BATTERY,
)

/**
 * Computes the registry: for every peer, tiles = peer capabilities ∩ local
 * support. Peers with no overlap contribute nothing. [onlineByDevice]
 * disables (but keeps) tiles for offline peers.
 */
fun computeTileRegistry(
    peerCapabilities: Map<String, Set<EngineCapability>>,
    localSupport: Set<EngineCapability>,
    onlineByDevice: Map<String, Boolean> = emptyMap(),
): List<CapabilityTile> {
    val tiles = mutableListOf<CapabilityTile>()
    for ((deviceId, peerCaps) in peerCapabilities) {
        val online = onlineByDevice[deviceId] ?: true
        for (capability in peerCaps.intersect(localSupport)) {
            tiles += CapabilityTile(
                capability = capability,
                deviceId = deviceId,
                enabled = online,
            )
        }
    }
    return tiles.sortedWith(
        compareBy({ tile ->
            CANONICAL_TILE_ORDER.indexOf(tile.capability).let { if (it == -1) CANONICAL_TILE_ORDER.size else it }
        }, { tile -> tile.capability.wireName }, { tile -> tile.deviceId }),
    )
}
