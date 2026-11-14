package ec0.core.localsend.client

import ec0.ui.models.DeviceSource
import ec0.ui.models.SettingsState
import ec0.ui.models.UiDevice
import java.util.UUID

/**
 * The production DeviceSource: real LocalSend UDP discovery (works today)
 * + the T4-gated Synergy probe (empty until T4 lands, switchable in Settings).
 */
class RealDeviceSource(
    settingsProvider: () -> SettingsState,
    private val synergyProbe: SynergyDiscoveryProbe,
    /** Announce download=true when this client hosts the HTTP receiver. */
    private val announceDownload: Boolean = false,
) : DeviceSource {
    private val localsend = LocalsendUdpDiscovery(
        settingsProvider = settingsProvider,
        // HTTP-mode fingerprint semantics (protocol): a random string per
        // process run. Replaced by cert SHA-256 when the HTTPS server lands.
        ownFingerprint = { OWN_FINGERPRINT },
        downloadProvider = { announceDownload },
    )

    override suspend fun discover(): List<UiDevice> =
        localsend.discoverSweep() + synergyProbe.discover()

    companion object {
        val OWN_FINGERPRINT: String = UUID.randomUUID().toString()
    }
}
