package ec0.desktop.discovery

import ec0.core.synergy.SynergyWsClient
import ec0.core.synergy.SynergyWsClient.Profile
import ec0.core.synergy.SynergyWsClient.SynergyHandshake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.logging.Logger

/**
 * Synergy device probe — the T10-transport side of the discovery seam.
 *
 * STATUS: T4-gated. The TLS transport (pinned cert + port walk) is real
 * (T10, merged), but **who to dial** and **what to say after the WebSocket
 * upgrade** are still T4 unknowns (no synergy discovery constants, no
 * handshake capture). This probe therefore:
 * - connects via [SynergyWsClient] with [SynergyHandshake.None] when a
 *   candidate host is supplied manually ([probeHost]),
 * - returns an empty device list from [discover] until T4 lands.
 *
 * When T4 produces discovery constants + handshake frames, [discover] grows
 * the real sweep (candidate hosts: SoftAP gateway 192.168.137.1 / Wi-Fi
 * subnet / mDNS) and the UI picks devices up without further changes.
 */
class SynergyDiscoveryProbe(
    private val enabled: () -> Boolean = { false },
    private val logger: Logger = Logger.getLogger("SynergyDiscoveryProbe"),
) {
    /**
     * Manual probe: proves the pinned-TLS path reaches a candidate host and
     * yields a session (or a diagnostic failure). NOT device discovery —
     * identity resolution is T4.
     */
    suspend fun probeHost(host: String): ProbeResult = withContext(Dispatchers.IO) {
        val client = SynergyWsClient(
            host = host,
            profile = Profile.COMPUTER,
            handshake = SynergyHandshake.None,
        )
        try {
            val session = client.connect()
            ProbeResult.Connected(
                host = host,
                close = { runCatching { session.close() } },
            )
        } catch (t: Throwable) {
            ProbeResult.Failed(host, t.message ?: t.javaClass.simpleName)
        }
    }

    suspend fun discover(): List<ec0.desktop.state.UiDevice> = withContext(Dispatchers.IO) {
        if (!enabled()) {
            return@withContext emptyList()
        }
        // T4 unknowns: candidate hosts + identity handshake. Keep empty —
        // do NOT fabricate devices.
        logger.info("Synergy probe enabled but T4 (discovery constants + handshake) pending — no sweep")
        emptyList()
    }

    sealed interface ProbeResult {
        data class Connected(val host: String, val close: () -> Unit) : ProbeResult
        data class Failed(val host: String, val reason: String) : ProbeResult
    }
}
