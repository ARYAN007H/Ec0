/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Feature A4: Quick Settings tiles (plan §P4).
 *  - [ClipboardSyncTileService]: toggles the phone's clipboard-sync switch (the
 *    same setting the DataStore persists — the watcher + apply path honor it).
 *  - [FindMyPcTileService]: sends the `workstation_lock` command to the live
 *    desktop session (the desktop's HandoffAndProximityService runs
 *    `loginctl lock-session`).
 *
 * Both are guarded by minSdk 28 (TileService is API 24+, fine).
 */

package ec0.android

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Shared state read/write helpers for the two tiles. */
object Ec0TileState {
    @Volatile
    var clipboardSyncEnabled: () -> Boolean = { true }

    @Volatile
    var setClipboardSyncEnabled: (Boolean) -> Unit = {}

    /** Sends CMD_WORKSTATION_LOCK to any live desktop session; true when sent. */
    var lockWorkstation: () -> Boolean = { false }
}

/**
 * QS tile 1 — clipboard sync on/off. Mirrors the dashboard tile (the setting is
 * the same boolean the service applies to the watcher + apply path).
 */
class ClipboardSyncTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val next = !Ec0TileState.clipboardSyncEnabled()
        Ec0TileState.setClipboardSyncEnabled(next)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = runCatching { Ec0TileState.clipboardSyncEnabled() }.getOrDefault(true)
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Clipboard sync"
        tile.subtitle = if (enabled) "Phone ⇄ PC on" else "Paused"
        tile.updateTile()
    }
}

/**
 * QS tile 2 — lock the paired PC (`workstation_lock` → `loginctl lock-session`
 * on the desktop). Targets any peer with a live engine session.
 */
class FindMyPcTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val sent = runCatching { Ec0TileState.lockWorkstation() }.getOrDefault(false)
            qsTile?.let { tile ->
                tile.label = "Lock my PC"
                tile.subtitle = if (sent) "Locked" else "No live session"
                tile.state = Tile.STATE_INACTIVE
                tile.updateTile()
            }
        }
    }
}
