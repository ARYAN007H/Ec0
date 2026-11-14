package ec0.core.engine.smartconnect

/**
 * Motorola Smart Connect ("Ready For" / `com.motorola.mobiledesktop` v09.0.4.005.0)
 * wire constants and staged capability maps for Slice S-A.
 *
 * Normative reference: `docs/engine/smart-connect-design.md` §2.1 & §3-S-A.
 *
 * NOTE ON `WIN_UNLOCKED` PROFILE (`PC_OS_WINDOWS = 6`, `PC_TYPE_LAPTOP = 4`, `PC_SC_TYPE = 2`):
 * The stock Motorola Smart Connect Android APK gates PC-attached feature tiles (Mirroring,
 * App Streaming, Cross Control, Webcam, Clipboard, File Exchange, PC Lock, Hotspot, etc.)
 * behind the peer identity fields `os=6` (Windows PC), `type=4` (Laptop), `scType=2`
 * (`WIN_UNLOCKED`), and `verCode >= 903013000` (`"09.0.3.013.000"`).
 * Advertising a non-Windows OS code causes the stock phone app to hide desktop continuity
 * tiles even when `svc 25 cmd 1` advertises matching capabilities. We explicitly document
 * this wire-compatibility profile so users and maintainers know why the engine presents
 * the `WIN_UNLOCKED` profile during `svc 0` verification.
 */
object SmartConnectConstants {
    /** Outer envelope wire version (`"3.0.0"`). */
    const val ENVELOPE_VERSION: String = "3.0.0"

    /** QR payload scheme prefix (`motorolardpconnection{...}`). */
    const val QR_PREFIX: String = "motorolardpconnection"

    /** Primary control WebSocket URI path (`/readyfor/`). */
    const val WS_CONTROL_PATH: String = "/readyfor/"

    /** Discovery / invite WebSocket URI path (`/readyfor/mdns`). */
    const val WS_DISCOVERY_PATH: String = "/readyfor/mdns"

    /** mDNS service type advertised by PC and phone (`_readyformdns._tcp.local.`). */
    const val MDNS_SERVICE_TYPE: String = "_readyformdns._tcp.local."

    /** mDNS short service type (`_readyformdns._tcp`). */
    const val MDNS_SERVICE_SHORT: String = "_readyformdns._tcp"

    /** Default primary TLS WebSocket port (`9836`). */
    const val DEFAULT_WS_PORT: Int = 9836

    /** Maximum WebSocket frame payload size (`20 MiB`) for inline `svc 25 cmd 2` wallpapers. */
    const val MAX_WS_FRAME_BYTES: Int = 20 * 1024 * 1024

    /** WebSocket ping interval (`20_000 ms`). */
    const val WS_PING_INTERVAL_MS: Long = 20_000L

    /** Dead-session timeout (`70_000 ms`, matching phone `f.h.c.a.p.y = 70s`). */
    const val WS_DEAD_SESSION_TIMEOUT_MS: Long = 70_000L

    /** WebSocket close code sent before intentional user disconnect (`3123`). */
    const val WS_CLOSE_CODE_USER_DISCONNECT: Int = 3123

    /** Unprompted Step 4 fallback timer (`2_000 ms`) if phone does not send `svc 0 cmd 1`. */
    const val UNPROMPTED_EXCHANGE_KEY_DELAY_MS: Long = 2_000L

    /** Post-pair discovery refresh delay (`5_000 ms`) so rotated `nonceId` is re-announced. */
    const val POST_PAIR_DISCOVERY_REFRESH_DELAY_MS: Long = 5_000L

    /** Invite token validity window (`180_000 ms` = 180 seconds; NOT single-use within TTL). */
    const val INVITE_TOKEN_TTL_MS: Long = 180_000L

    // -------------------------------------------------------------------------
    // PC `WIN_UNLOCKED` Wire Profile Constants (svc 0 cmd 0 Verify Reply)
    // -------------------------------------------------------------------------

    /** `os = 6` (Windows PC profile required by stock Smart Connect APK to unlock PC tiles). */
    const val PC_OS_WINDOWS: Int = 6

    /** `type = 4` (Laptop form factor). */
    const val PC_TYPE_LAPTOP: Int = 4

    /** `scType = 2` (`WIN_UNLOCKED` Smart Connect tier). */
    const val PC_SC_TYPE_WIN_UNLOCKED: Int = 2

    /** `appType = 2` (Desktop Smart Connect daemon). */
    const val PC_APP_TYPE_DESKTOP: Int = 2

    /** `verCode = 903013000` (`09.0.3.013.000`). */
    const val PC_VER_CODE: Int = 903_013_000

    /** `verName = "09.0.3.013.000"`. */
    const val PC_VER_NAME: String = "09.0.3.013.000"

    /** `cap = 0` in mDNS TXT and QR `wsData`. */
    const val PC_BASE_CAP: Int = 0

    /** `net = 0` in QR `wsData`. */
    const val PC_NET_DEFAULT: Int = 0

    // -------------------------------------------------------------------------
    // Service IDs (Slice S-A core + wire registry constants from §2.1)
    // -------------------------------------------------------------------------

    /** `svc 0`: Pairing, Verification, `EXCHANGE_KEY`, Disconnect. */
    const val SVC_HANDSHAKE: Int = 0

    /** `svc 1`: Mirroring / RTSP / Display info (`cmd 31` absorbed in S-A connect burst). */
    const val SVC_MIRROR: Int = 1

    /** `svc 3`: Webcam. */
    const val SVC_WEBCAM: Int = 3

    /** `svc 5`: Notification metadata. */
    const val SVC_NOTIFICATIONS: Int = 5

    /** `svc 9`: Clipboard sync. */
    const val SVC_CLIPBOARD: Int = 9

    /** `svc 13`: Instant Hotspot. */
    const val SVC_HOTSPOT: Int = 13

    /** `svc 15`: WebRTC signaling. */
    const val SVC_WEBRTC_SIGNAL: Int = 15

    /** `svc 16`: Virtual Display / App Stream (`cmd 14` absorbed in S-A connect burst). */
    const val SVC_APP_STREAM: Int = 16

    /** `svc 17`: App Stream icons / metadata. */
    const val SVC_APP_ICONS: Int = 17

    /** `svc 22`: PC Lock. */
    const val SVC_PC_LOCK: Int = 22

    /** `svc 23`: File Exchange (`fex`). */
    const val SVC_FILE_EXCHANGE: Int = 23

    /** `svc 25`: Device Status, Wallpaper (`cmd 2`), Replace-Whole Capability Map (`cmd 1`). */
    const val SVC_DEVICE_STATUS: Int = 25

    /** `svc 26`: Cross Control. */
    const val SVC_CROSS_CONTROL: Int = 26

    /** `svc 32`: NFS-over-mTLS File Server. */
    const val SVC_NFS_MTLS: Int = 32

    /** `svc 35`: Phone client event sink. */
    const val SVC_EVENT_SINK: Int = 35

    /** `svc 37`: Notification inline reply & dismiss. */
    const val SVC_NOTIFICATION_ACTIONS: Int = 37

    /** `svc 100`: Telephony / Call notifications. */
    const val SVC_TELEPHONY: Int = 100

    /** `svc 202`: Extend PC Display (`phone-as-display`). */
    const val SVC_EXTEND_DISPLAY: Int = 202

    /** `svc 204`: SMS relay. */
    const val SVC_SMS: Int = 204

    // -------------------------------------------------------------------------
    // Handshake (`svc 0`) & Discovery (`/readyfor/mdns`) Command IDs
    // -------------------------------------------------------------------------

    /** `svc 0 cmd 0`: First-time QR pairing Hello / PC Verify Reply. */
    const val CMD_HELLO_PAIR: Int = 0

    /** `svc 0 cmd 1`: `EXCHANGE_KEY` (phone -> PC and PC -> phone). */
    const val CMD_EXCHANGE_KEY: Int = 1

    /** `svc 0 cmd 3`: Returning paired reconnect Hello. */
    const val CMD_HELLO_RECONNECT: Int = 3

    /** `svc 0 cmd 6`: Intentional user disconnect (`{"cmd":6,"data":{"type":0}}`). */
    const val CMD_USER_DISCONNECT: Int = 6

    /** `/readyfor/mdns` `cmd 0`: Minimal discovery trigger (`{"cmd":0}`). */
    const val DISCOVERY_CMD_TRIGGER: Int = 0

    /** `/readyfor/mdns` `cmd 1`: Full dial-back invite (`{"cmd":1,"ip":...}`). */
    const val DISCOVERY_CMD_INVITE: Int = 1

    // -------------------------------------------------------------------------
    // Device Status (`svc 25`) Command IDs
    // -------------------------------------------------------------------------

    /** `svc 25 cmd 1`: Device status + replace-whole `cap` map. */
    const val STATUS_CMD_CAP_AND_BATTERY: Int = 1

    /** `svc 25 cmd 2`: Phone wallpaper sync (`wallpaper` base64 + `md5`). */
    const val STATUS_CMD_WALLPAPER: Int = 2

    /** `svc 25 cmd 5`: Do-Not-Disturb state update. */
    const val STATUS_CMD_DND: Int = 5

    // -------------------------------------------------------------------------
    // Connect-Burst Absorber Command IDs (logged & ignored in S-A)
    // -------------------------------------------------------------------------

    /** `svc 1 cmd 31`: Phone screen dimensions & DPI query on connect. */
    const val MIRROR_CMD_DISPLAY_METRICS: Int = 31

    /** `svc 16 cmd 14`: Phone display metrics on connect. */
    const val APP_STREAM_CMD_DISPLAY_METRICS: Int = 14

    // -------------------------------------------------------------------------
    // Sealed Discovery Frame & BLE Constants
    // -------------------------------------------------------------------------

    /** `frameType = 0x01`: AES-256-GCM sealed discovery frame (ONLY allowed outgoing type). */
    const val SEALED_FRAME_TYPE_ENCRYPTED: Byte = 0x01

    /**
     * `frameType = 0x02`: Plaintext discovery frame.
     * CRITICAL HAZARD: Sending `0x02` to a paired phone triggers `OnReceiveUnPairedEvent`!
     */
    const val SEALED_FRAME_TYPE_PLAINTEXT_HAZARD: Byte = 0x02

    /**
     * `frameType = 0x03`: Cannot-decrypt error frame.
     * CRITICAL HAZARD: Sending `0x03` to a paired phone causes it to delete the PC pairing!
     */
    const val SEALED_FRAME_TYPE_CANNOT_DECRYPT_HAZARD: Byte = 0x03

    /** 16-byte raw `senderNonceId` prefix length in sealed discovery frames. */
    const val SEALED_FRAME_NONCE_ID_BYTES: Int = 16

    /** 17-byte AAD length (`senderNonceId[0..16) + frameType`). */
    const val SEALED_FRAME_AAD_BYTES: Int = 17

    /** 12-byte AES-GCM IV length. */
    const val SEALED_FRAME_IV_BYTES: Int = 12

    /** 16-byte AES-GCM authentication tag length (128 bits). */
    const val SEALED_FRAME_TAG_BYTES: Int = 16

    /** BLE Advertisement ServiceData UUID `0x5246` (`"RF"` in ASCII). */
    const val BLE_WAKE_SERVICE_UUID_16: Int = 0x5246

    /** BLE Advertisement ServiceData payload length (`20` bytes = `0x01 || nonceId[16] || trailer[3]`). */
    const val BLE_WAKE_SERVICE_DATA_BYTES: Int = 20

    /** BLE GATT Write Characteristic UUID `0x0722` (`00000722-0000-1000-8000-00805f9b34fb`). */
    const val BLE_GATT_INVITE_CHAR_UUID_16: Int = 0x0722

    // -------------------------------------------------------------------------
    // Service Versions & Staged Capability Maps (§2.1 & §3-S-A)
    // -------------------------------------------------------------------------

    /**
     * Service versions advertised inside `svc 0 cmd 0` PC Verify Reply (`serviceVersions`).
     * Note: `rfaServiceVersions` MUST be omitted (`null`), because including it triggers a
     * Gson cast error in the stock phone APK (`c0.o.w`).
     */
    val DEFAULT_SERVICE_VERSIONS: Map<String, Int> = mapOf(
        "0" to 1,
        "1" to 3,
        "3" to 2,
        "5" to 2,
        "9" to 1,
        "13" to 2,
        "15" to 2,
        "16" to 3,
        "17" to 1,
        "22" to 1,
        "23" to 1,
        "25" to 2,
        "26" to 2,
        "32" to 1,
        "35" to 1,
        "37" to 1,
        "100" to 1,
        "202" to 1,
        "204" to 1
    )

    /**
     * Slice `S-A` staged capability map (`svc 25 cmd 1` `cap` object).
     *
     * Per the Slice `S-A` mandate: only advertise handlers that actually exist in the current
     * slice. Since `S-A` implements transport, pairing/discovery, `svc 0` handshake, and
     * `svc 25` device status/cap-map only (no `S-B`..`S-E` feature handlers yet), the default
     * `S-A` capability map is empty (`{}`), which causes the paired phone to display the PC
     * device page with zero feature tiles until `S-B` handlers register their capabilities.
     */
    val DEFAULT_SA_TILE_CAP_MAP: Map<String, Int> = emptyMap()

    /**
     * Full wire-verified capability map constants (§2.1) for progressive registration in
     * slices `S-B` through `S-E` via `SmartConnectCapRegistry`.
     */
    val FULL_WIRE_TILE_CAP_MAP: Map<String, Int> = mapOf(
        "mirror" to 1,
        "webcam" to 1,
        "clipboard" to 1,
        "notification" to 1,
        "fex" to 1,
        "fileServer" to 2,
        "crossControl" to 7,
        "Lock" to 1,
        "hotspot" to 1,
        "appStream" to 5,
        "extendScreen" to 1,
        "sms" to 1,
        "call" to 1,
        "p2p" to 1
    )
}
