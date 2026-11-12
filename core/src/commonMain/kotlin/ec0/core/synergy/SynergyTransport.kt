package ec0.core.synergy

/**
 * Transport constants reverse-engineered from com.oplus.linker 17.7.35
 * (base/socket/WebSocketConnectManager.java) — see docs/re/oplus-connect-findings.md §7.1.
 */
object SynergyTransport {
    /** WebSocket port fallback chain (RetryAndChangePortInterceptor). */
    val PORT_FALLBACK_CHAIN: List<Int> = listOf(39_888, 49_888, 59_888, 8_792)

    /** SoftAP gateway subnets used when Wi-Fi Direct / hotspot transport is active. */
    const val SOFTAP_GATEWAY_HOTSPOT = "192.168.137.1"
    const val SOFTAP_GATEWAY_WIFI_DIRECT_PREFIX = "192.168.49."

    /** mDNS port observed on the official Windows daemon (devicespace.exe). */
    const val MDNS_PORT = 5_353

    /** Cast relay scene key (WiseConnectScene). */
    const val SCENE_APPLICATION_CAST_RELAY = "APPLICATION_CAST_RELAY"
}
