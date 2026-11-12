package ec0.core.synergy

/**
 * Transport constants reverse-engineered from com.oplus.linker 17.7.35
 * (base/socket/WebSocketConnectManager.java) — see docs/re/oplus-connect-findings.md §7.1
 * and docs/re/synergy-catalog.yaml (reviewer-verified vs embedded constants).
 */
object SynergyTransport {
    /**
     * Default WebSocket port lists per peer profile, faithful to source
     * (duplicates included — RetryAndChangePortInterceptor walks list order,
     * RetryCount = list.size()). WSCM.java:267 (computer) / :481 (pad).
     */
    val COMPUTER_PORT_LIST: List<Int> = listOf(80_80, 39_888, 80_80, 49_888, 59_888)
    val PAD_PORT_LIST: List<Int> = listOf(8_792, 39_888, 8_792, 49_888, 59_888)

    /** Scene override port (WebSocketScene.java:190 — synergy.mServerPort = "8792"). */
    const val SCENE_PORT = 8_792

    /** Pinned TLS certs bundled as APK assets (WSCM.java:447/:477 — trustManagerForCertificates). */
    const val CERT_ASSET_COMPUTER = "server.cer"
    const val CERT_ASSET_PAD = "server_pad.cer"

    /** SoftAP gateway subnets used when Wi-Fi Direct / hotspot transport is active. */
    const val SOFTAP_GATEWAY_HOTSPOT = "192.168.137.1"
    const val SOFTAP_GATEWAY_WIFI_DIRECT_PREFIX = "192.168.49."

    /** mDNS port observed on the official Windows daemon (devicespace.exe). */
    const val MDNS_PORT = 5_353

    /** Cast relay scene key (WiseConnectScene). */
    const val SCENE_APPLICATION_CAST_RELAY = "APPLICATION_CAST_RELAY"
}
