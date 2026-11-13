package ec0.core.localsend.client

import ec0.core.uimodels.DeviceSource
import ec0.core.uimodels.SettingsState
import ec0.core.uimodels.UiDevice
import java.util.UUID

/**
 * The production DeviceSource: real LocalSend UDP discovery (works today)
 * + the T4-gated Synergy probe (empty until T4 lands, switchable in Settings).
 */
class RealDeviceSource(
    settingsProvider: () -> SettingsState,
    private val synergyProbe: SynergyDiscoveryProbe,
) : DeviceSource {
    private val localsend = LocalsendUdpDiscovery(
        settingsProvider = settingsProvider,
        // HTTP-mode fingerprint semantics (protocol): a random string per
        // process run. Replaced by cert SHA-256 when the HTTPS server lands.
        ownFingerprint = { OWN_FINGERPRINT },
    )

    override suspend fun discover(): List<UiDevice> =
        localsend.discoverSweep() + synergyProbe.discover()

    companion object {
        val OWN_FINGERPRINT: String = UUID.randomUUID().toString()
    }
}
