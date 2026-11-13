/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Unified Multi-Protocol DeviceSource & System Tray / Background Daemon Controller
 * (`ec0.desktop.continuity`, Improvement #10):
 *   1. Ec0UnifiedDeviceSource — implements `ec0.ui.models.DeviceSource` without
 *      touching any files owned by PR #9 or PR #10, merging:
 *        - Live `ec0-engine` mDNS peers (`_ec0._tcp.local.`)
 *        - Persistently pinned `ec0-engine` peers (`PersistentPinStore`)
 *        - Live `CamDroid` camera servers (`_camdroid._tcp.local.`)
 *        - Optional secondary `DeviceSource` (e.g. LocalSend UDP / Synergy)
 *   2. ContinuityTrayController — manages window minimize-to-tray state, quick tray
 *      actions (Send Clipboard, Toggle Continuity Camera, Mirror Phone Screen,
 *      Instant Hotspot), and `systemd --user` service unit generation (`ec0-desktop.service`).
 */
package ec0.desktop.continuity

import ec0.core.engine.EngineConstants
import ec0.core.localsend.DeviceType
import ec0.ui.models.DeviceSource
import ec0.ui.models.ProtocolFamily
import ec0.ui.models.UiDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ContinuityTrayQuickItem(
    val id: String,
    val label: String,
    val subtitle: String,
    val enabled: Boolean = true,
)

class Ec0UnifiedDeviceSource(
    private val controller: ContinuityHubController,
    private val secondarySource: DeviceSource? = null,
) : DeviceSource {

    override suspend fun discover(): List<UiDevice> {
        controller.node.mdnsDiscovery.triggerBurstNow()
        val byId = linkedMapOf<String, UiDevice>()

        // 1. Live mDNS peers from ec0-engine
        for (peer in controller.discoveredPeers.value) {
            val devType = when (peer.role.lowercase()) {
                EngineConstants.ROLE_PHONE -> DeviceType.MOBILE
                EngineConstants.ROLE_DESKTOP -> DeviceType.DESKTOP
                else -> DeviceType.DESKTOP
            }
            byId[peer.deviceId] = UiDevice(
                id = peer.deviceId,
                alias = peer.displayName,
                deviceModel = "ec0-engine (${peer.role})",
                deviceType = devType,
                protocol = ProtocolFamily.SYNERGY,
                port = peer.port,
                fingerprint = peer.deviceId.take(16),
                download = true,
                online = true,
            )
        }

        // 2. Pinned peers from PersistentPinStore (shown even when currently offline)
        for (pinned in controller.pinnedPeers.value) {
            if (!byId.containsKey(pinned.deviceId)) {
                val online = controller.node.activeSessionFor(pinned.deviceId) != null
                byId[pinned.deviceId] = UiDevice(
                    id = pinned.deviceId,
                    alias = pinned.alias,
                    deviceModel = "Pinned (${pinned.trustLevel.name})",
                    deviceType = DeviceType.MOBILE,
                    protocol = ProtocolFamily.SYNERGY,
                    port = EngineConstants.PRIMARY_PORT,
                    fingerprint = pinned.deviceId.take(16),
                    download = true,
                    online = online,
                )
            }
        }

        // 3. Auto-discovered CamDroid Continuity Camera servers (_camdroid._tcp.local.)
        for (cam in controller.discoveredCamDroidServices.value) {
            val syntheticId = "camdroid-${cam.instanceName.lowercase()}"
            if (!byId.containsKey(syntheticId)) {
                byId[syntheticId] = UiDevice(
                    id = syntheticId,
                    alias = "${cam.deviceName} (CamDroid)",
                    deviceModel = "CamDroid ${cam.resolution} [${cam.codecs.joinToString(",")}]",
                    deviceType = DeviceType.MOBILE,
                    protocol = ProtocolFamily.KDECONNECT,
                    port = cam.port,
                    fingerprint = cam.instanceName.take(16),
                    download = false,
                    online = !cam.isGoodbye,
                )
            }
        }

        // 4. Merge optional secondary DeviceSource (e.g. LocalSend multicast / HTTP)
        val secondary = secondarySource?.discover().orEmpty()
        for (dev in secondary) {
            if (!byId.containsKey(dev.id)) {
                byId[dev.id] = dev
            }
        }

        return byId.values.toList()
    }
}

class ContinuityTrayController(
    private val hubController: ContinuityHubController,
) {
    private val _windowVisible = MutableStateFlow(true)
    val windowVisible: StateFlow<Boolean> = _windowVisible.asStateFlow()

    private val _minimizeToTrayOnClose = MutableStateFlow(true)
    val minimizeToTrayOnClose: StateFlow<Boolean> = _minimizeToTrayOnClose.asStateFlow()

    fun setWindowVisible(visible: Boolean) {
        _windowVisible.value = visible
    }

    fun setMinimizeToTrayOnClose(enabled: Boolean) {
        _minimizeToTrayOnClose.value = enabled
    }

    /**
     * Handles the window close request: if [minimizeToTrayOnClose] is true, hides
     * the main window while keeping the background Continuity daemon alive.
     * Returns `true` if the application should stay alive in the system tray.
     */
    fun onWindowCloseRequested(): Boolean {
        return if (_minimizeToTrayOnClose.value) {
            _windowVisible.value = false
            true
        } else {
            hubController.close()
            false
        }
    }

    /**
     * Builds the current System Tray menu items reflecting live peer battery,
     * camera state, clipboard sync, and hotspot status.
     */
    fun buildTrayMenuItems(): List<ContinuityTrayQuickItem> {
        val presence = hubController.peerPresence.value.entries.firstOrNull()
        val batterySubtitle = if (presence != null) {
            "Battery ${presence.value.batteryPct}%" + if (presence.value.isCharging) " ⚡" else ""
        } else {
            "No phone beacon"
        }
        val camState = hubController.cameraStatus.value
        val hotspot = hubController.hotspotStatus.value

        return listOf(
            ContinuityTrayQuickItem(
                id = "toggle_window",
                label = if (_windowVisible.value) "Hide Continuity Window" else "Show Continuity Window",
                subtitle = batterySubtitle,
            ),
            ContinuityTrayQuickItem(
                id = "sync_clipboard",
                label = "Sync Linux Clipboard Now",
                subtitle = hubController.lastSyncedClipSummary.value,
            ),
            ContinuityTrayQuickItem(
                id = "send_copied_files",
                label = "AirDrop Copied Files from Clipboard",
                subtitle = "Reads text/uri-list from Dolphin / Nautilus",
            ),
            ContinuityTrayQuickItem(
                id = "toggle_camera",
                label = "Continuity Camera (${camState.state.wireName})",
                subtitle = camState.v4l2DevicePath,
            ),
            ContinuityTrayQuickItem(
                id = "instant_hotspot",
                label = if (hotspot.enabled) "Hotspot Active (${hotspot.ssid})" else "Enable Instant Hotspot",
                subtitle = if (hotspot.enabled) "Click to disconnect" else "1-click phone tether + nmcli join",
            ),
        )
    }

    /**
     * Generates a `systemd --user` unit file (`~/.config/systemd/user/ec0-desktop.service`)
     * so the Linux Continuity daemon starts automatically on graphical login.
     */
    fun generateSystemdUserServiceUnit(
        executablePath: String = "/usr/local/bin/ec0-desktop",
    ): String = buildString {
        appendLine("[Unit]")
        appendLine("Description=ec0 Continuity Daemon (Android + Linux Sync & CamDroid Camera)")
        appendLine("After=graphical-session.target network-online.target")
        appendLine("PartOf=graphical-session.target")
        appendLine()
        appendLine("[Service]")
        appendLine("Type=simple")
        appendLine("ExecStart=$executablePath --tray")
        appendLine("Restart=on-failure")
        appendLine("RestartSec=3")
        appendLine()
        appendLine("[Install]")
        appendLine("WantedBy=default.target")
    }
}
