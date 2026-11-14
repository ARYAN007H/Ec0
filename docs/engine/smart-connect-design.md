# `ec0-engine` — Smart Connect Full-Parity Architecture & Wire Design (`T16`)

**Status:** Slice 0 Normative Design Specification (`T16` — Design Gate before `S-A..S-E` implementation)  
**Author:** `gemini-argon`  
**Target Namespace:** `ec0.core.engine.smartconnect.*`, `ec0.core.engine.transport.*`, `ec0.core.engine.services.*`  
**Platforms:** Linux Desktop (`:desktop`) & Android 14+ (`:android-app`, `minSdk = 34`, `compileSdk = 35`)  
**License:** GPL-3.0-only (clean-room specification from wire-verified protocol facts; zero upstream code copied)

---

## 0. Executive Summary, Strategic Unlock & Dual-Track Architecture

### 0.1 Mission (`T16`)
`ec0` is expanding from its working LocalSend v2 AirDrop layer (`T5`/`T13`/`T14`, verified E2E phone↔desktop on Aryan's OPPO A5x hotspot LAN) and first-party `ec0-engine` (`Slices 0–11`) to implement **100% native Motorola Smart Connect (`com.motorola.mobiledesktop` / Ready For) parity across both desktop and phone**:

1. **Screen Mirroring, Ready For Mobile Desktop & Virtual Phone Views** (`services 1, 15, 16`)
2. **Per-App Android Streaming Windows** (`services 15, 17` + `fex` icon pull + Android `VirtualDeviceManager` API 34+)
3. **Cross Control ("Universal Control" Mouse & Keyboard across Screen Edge)** (`services 26, 16`)
4. **Continuity Webcam (Wireless WebRTC → `v4l2loopback` / PipeWire Camera Node)** (`service 3`)
5. **File Exchange (`fex` Direct-TLS Pull/Push + File Station Tunnelled NFSv3 over Mutual TLS)** (`services 2, 168, 24`)
6. **Bidirectional Clipboard Sync (Inline Text + `fex` Image/File Clips)** (`service 9`)
7. **Notification Mirroring, Inline Quick Reply & Stream-Routed Actions/Open** (`services 5, 37`)
8. **SMS & MMS Conversation Relay** (`services 204, 100`)
9. **Remote PC Lock** (`service 22`)
10. **Instant Hotspot Auto-Join** (`service 13`)
11. **Phone-as-Display ("R4SS" PC Screen Mirror/Extend to Phone + SCTP Touch/Mouse/Key/Pen Input)** (`service 202`)
12. **Device Status, Wallpaper Sync & Dynamic Feature-Tile Capability Map** (`service 25`)

### 0.2 The Unlock Discovery (`docs/re/smart-connect-unlock.md` & `smart-connect-findings.md`)
Static JADX verification of the `com.motorola.mobiledesktop` APK (`09.0.4.005.0`, `verCode = 9040050`, `targetSdk = 36`) pulled from Aryan's OPPO A5x (`CPH2733IN`) combined with device-verified findings from `docs/re/vendor/smartconnect-linux/` established two foundational truths:

1. **The Peer Capability Map is the Only Gate for PC-Attached Features (`[OBSERVED]` + `[APK-STATIC]`):**
   - `BoardUtils.isMotoDevice()` (`"motorola".equalsIgnoreCase(SystemProperties.get("ro.product.manufacturer"))` at `com/zui/continuity/board/util/BoardUtils.java:225`) gates **only** LenovoID cloud SSO (`AccountActivity`, `WebAuth`), `NearbyDisplaysTile` (hardware marketplace), and a CN-region branch.
   - None of the PC-attached feature packages (`readyfor/{dashboard,appstream,webcam,files,display,pairing,controller}`, `smartconnect/core`) check `Build.MANUFACTURER`.
   - Instead, the phone draws a feature tile on the PC's device page **if and only if** the service ID is present in **both**:
     1. The PC's `service 0 cmd 0` verify-reply `serviceVersions` map, **AND**
     2. The PC's `service 25 cmd 1` `cap` map (`Map<String, Int>`).
   - App streaming on the phone uses standard Android 14+ `android.companion.virtual.VirtualDeviceManager` (`ScVirtualDeviceManager`, 13 VDM call sites, zero vendor lock).
2. **Immediate Day-One Test Peer on Aryan's OPPO A5x:**
   - Because Aryan's A5x already runs stock Smart Connect `09.0.4.005.0`, `ec0` Desktop becomes a **live, full-featured paired peer for the A5x as soon as Slice `S-A` lands** — before a single line of Smart Connect code needs to be written on the phone side.
   - In parallel, `:android-app` implements the phone side of the protocol (absorbable into `Ec0EngineNode`) so `ec0` Android ↔ `ec0` Desktop achieves full native parity on any Android device without requiring the Motorola APK.

### 0.3 Provenance & Clean-Room Boundary (`docs/re/vendor/smartconnect-linux/PROVENANCE.md`)
- Wire facts, command numbers, JSON field schemas, and pitfall notes in this specification are derived from `docs/re/vendor/smartconnect-linux/` (MIT licensed) and our own static analysis of `MotoSmartConnect-base.apk` (`09.0.4.005.0`).
- Confidence tags are preserved explicitly throughout this design:
  - **`[OBSERVED]`**: Verified on the wire with a real device or read directly from decompiled handlers/constants.
  - **`[APK-STATIC]`**: Verified by `ec0`'s static analysis of `com.motorola.mobiledesktop` `09.0.4.005.0` on the A5x.
  - **`[INFERRED]`**: Deduced from surrounding code or behavior; treated as a hypothesis with explicit defensive fallbacks.
- **Zero Code Transplant:** `ec0` is GPL-3.0-only and Kotlin Multiplatform; no Rust code from `smartconnect-linux` is copied or translated line-for-line.

---

## 1. Unified Transport Trait: `PinnedTlsWebSocketTransport` & `SealedDiscoveryFrameCodec`

### 1.1 Structural Convergence Across Smart Connect, O+ Synergy & `ec0-engine`

As identified in `docs/re/smart-connect-ec0-delta.md` §2, Motorola Smart Connect and O+ Connect (`com.oplus.linker`, `T2`/`T10`) share the exact same underlying transport spine. Rather than maintaining parallel socket stacks, `ec0.core.engine.transport` defines two shared abstractions that host Smart Connect, O+ Synergy, and `ec0-engine`:

| Transport Dimension | Motorola Smart Connect (`com.motorola.mobiledesktop`) | O+ Connect / Synergy (`ec0.core.synergy`) | First-Party `ec0-engine` (`ec0.core.engine`) |
|---|---|---|---|
| **Bootstrap & Port** | QR (`motorolardpconnection{...}`) or sealed invite → PC `wsPort` (default `52020`) | QR / mDNS → per-profile port walk (`8080/8792 → 39888 → 49888 → 59888`) | QR (`ec0://pair?...`) or `_ec0._tcp` → `[49880, 39888, 49888, 59888]` |
| **TLS Trust Anchor** | Self-signed RSA-2048 X.509 cert; peer pins **leaf DER `SHA-256` (`fp`)** from QR/invite; chain/CN/validity/SNI/ALPN ignored `[OBSERVED]` | Bundled per-profile X.509 leaf DER (`server.cer` `a892ccac…`, `server_pad.cer` `afc50cd2…`) `[OBSERVED]` | Ephemeral `X25519` ECDH + pinned `Ed25519` SPKI (`ChaCha20-Poly1305` frame AEAD) |
| **WebSocket Upgrade Path** | `GET /readyfor/` (prefix match) for control; `GET /readyfor/mdns` for sealed discovery `[OBSERVED]` | `GET /` (`wss://<host>:<port>/`) `[OBSERVED]` | `GET /ec0/v1` |
| **Control Frame Format** | RFC 6455 `TEXT` frames only (`>= 16 MiB` max payload), UTF-8 JSON envelope (`deviceId`, `srcServiceId`, `serviceId`, `msgId`, `data`) `[OBSERVED]` | RFC 6455 `TEXT` frames only, UTF-8 JSON envelope (`msg_type`, `msg_body`, `src_role`, `category`, `timestamp`, `noncestr`, `signature`) `[OBSERVED]` | RFC 6455 `BINARY` AEAD (`0x01..0x04`) carrying signed `Ec0Envelope` JSON |
| **Keepalive & Timeouts** | TLS `15 s`; Hello within `40 s`; Verify reply within `5 s` (`50 s` total); WS `PING` every `20 s`; drop after `70 s` silence; phone close code `3123` `[OBSERVED]` | WS `PING` every `5 s` (`WSCM.pingInterval`); close-frame echo `[OBSERVED]` | WS `PING` every `5 s`; full-jitter backoff |
| **Sealed Discovery Frame** | Binary WS on `/readyfor/mdns` & BLE `0x0722`: `[16B nonceId][1B type][12B IV][AES-256-GCM ct ‖ 16B tag]`, `AAD = bytes[0..17)`, `key = perPairKey` (no KDF) `[OBSERVED]` | Wiseconnect discovery frames: `AES-256-GCM` under per-pair key `[OBSERVED]` | Signed `Ec0Envelope` / mDNS `_ec0._tcp.local.` |
| **Background Wake** | **BLE GATT Central → Peripheral `0x5246`** (write sealed invite to `0x0722`; wakes backgrounded phone in `~0.3 s` dial-back) `[OBSERVED]` | Foreground-only phone listener (or USB ADB tunnel) | `_ec0._tcp` + Gateway fallback + **BLE `0x5246` wake (shared!)** |

### 1.2 `PinnedTlsWebSocketTransport` Kotlin Trait (`core/src/jvmMain/kotlin/ec0/core/engine/transport/`)

```kotlin
package ec0.core.engine.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.security.cert.X509Certificate

/**
 * Shared TLS-pinned RFC 6455 WebSocket transport trait hosting both
 * Motorola Smart Connect (`/readyfor/`, `/readyfor/mdns`) and O+ Synergy (`/`).
 */
sealed interface TlsPinMode {
    /**
     * Pins the exact SHA-256 digest (32 bytes) of the peer's leaf X509Certificate.encoded (DER).
     * Ignores certificate chain, subject CN/SAN, validity dates, and X.509 version (v1 vs v3),
     * verifying only the leaf DER hash and the TLS handshake signature.
     */
    data class LeafDerSha256(val expectedSha256: ByteArray) : TlsPinMode

    /**
     * Server-side or discovery dial-back mode where the TLS layer accepts any leaf cert
     * (or presents our pinned self-signed cert) because authentication happens via an
     * invite token, a known realDeviceId, or an AES-256-GCM SealedDiscoveryFrame.
     */
    data object UnpinnedTlsPeer : TlsPinMode
}

data class PinnedTlsWsConfig(
    val wsPathPrefix: String = "/readyfor/",
    val discoveryWsPath: String = "/readyfor/mdns",
    val maxFrameBytes: Int = 20 * 1024 * 1024, // 20 MiB (> 16 MiB requirement for wallpapers/big-picture JPEGs)
    val tlsHandshakeTimeoutMs: Int = 15_000,
    val firstMessageTimeoutMs: Int = 40_000,
    val verifyReplyTimeoutMs: Int = 5_000,
    val pingIntervalMs: Long = 20_000L,
    val deadSessionTimeoutMs: Long = 70_000L,
)

interface PinnedTlsWebSocketSession : Closeable {
    val remoteAddress: String
    val localAddress: String
    val upgradePath: String
    val peerLeafCertSha256Hex: String?

    fun sendTextFrame(utf8Json: String)
    fun sendBinaryFrame(payload: ByteArray)
    fun receiveFrame(): WsFrame? // TEXT, BINARY, PING, PONG, CLOSE(code, reason)
}
```

#### Why Reuse `Ec0WsCodec` + Custom `X509TrustManager` Instead of Standard WebPKI
1. **Strict Leaf-DER Pinning (`R4CertException` & X.509 v1 Compatibility):**
   - When the phone dials `wss://<pc-ip>:<wsPort>/readyfor/`, it base64-decodes the QR/invite `fp` (44 chars → 32 bytes), hex-encodes it, and compares it case-insensitively against `SHA-256(serverLeafCert.encoded)` `[OBSERVED]`.
   - Conversely, when the PC dials the phone's File Station NFS server (`svc 168`) or `fex` server (`svc 2`/`17`), the phone presents a minimal **X.509 v1** self-signed certificate that standard PKIX/WebPKI validators reject due to missing v3 extensions or validity ranges `[OBSERVED]`.
   - Our `LeafDerPinningTrustManager` (extending the pattern proven in `ec0.core.synergy.SynergyTrust`) computes `MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)` and compares it via `MessageDigest.isEqual(actualSha256, expectedSha256)` — completely bypassing PKIX path validation while guaranteeing cryptographic leaf pinning during the TLS 1.2/1.3 handshake.
2. **16 MiB+ Frame Support & Write-Lock Safety:**
   - Smart Connect sends ~280 KB base64 wallpapers (`svc 25 cmd 2`) and multi-megabyte full-resolution notification `big_picture` JPEGs (`svc 5`) inside single WebSocket `TEXT` frames `[OBSERVED]`.
   - `PinnedTlsWebSocketTransport` configures `maxFrameBytes = 20 MiB` (`20_971_520` bytes) on `/readyfor/` and guards all outbound `TEXT`/`PING`/`PONG`/`CLOSE` writes with a per-session `ReentrantLock` (closing the T10 P2 follow-up).

---

### 1.3 Identity, Privacy-Preserving Salted Nonce IDs & Serial Number (`sn`) Invariants

Smart Connect separates a device's **long-term pairing identity (`realDeviceId`)** from its **wire-visible session/discovery identifier (`nonceId`)** so passive LAN/BLE observers cannot track a device across time `[OBSERVED]`:

| Field | Wire Encoding | Specification & Invariants | Tag |
|---|---|---|---|
| `realDeviceId` | Base64 (standard, padded, 24 chars) of `16` random bytes | Generated once on first boot and persisted in `~/.config/ec0/smartconnect/identity.json` (`0600`). **Every per-peer state map (`PairedPeerStore`, cached `serviceVersions`, active sessions) MUST be keyed on `device.realDeviceId`, NEVER on the outer envelope `deviceId`.** | `[OBSERVED]` |
| `nonceId` (`deviceId`) | Base64 (standard, padded, 24 chars) on JSON/mDNS; raw `16` bytes on BLE `0x5246` & discovery frames | Derived from `realDeviceId` (raw 16 bytes) and a fresh 8-byte random `salt`:<br>$$\text{nonceId\_raw16} = \text{salt}_8 \parallel \text{SHA-256}(\text{realDeviceId\_raw16} \parallel \text{salt}_8)[0..8)$$<br>Rotated every `30 minutes` on the phone and once per daemon startup on the PC. | `[OBSERVED]` |
| `sn` | UUID string (36 chars, e.g. `UUID.nameUUIDFromBytes(realDeviceIdRaw).toString()`) | **CRITICAL INVARIANT:** `sn` MUST be non-empty and stable across runs. On every connect, the phone upserts its pairing SQLite table by `realDeviceId`, but probes for existing rows by `sn`. Sending `sn = ""` creates an un-deletable zombie row that causes every subsequent pairing to fail on primary-key constraint (`[OBSERVED]`). | `[OBSERVED]` |
| TLS Identity Cert | Self-signed `RSA-2048` (`SHA256withRSA`), validity `now - 30d` to `now + 20y` | Generated once and persisted (`cert.der`, `key.pkcs8` mode `0600`). Regenerating the cert invalidates all existing phone pairings because the phone pins `fp = SHA-256(cert.der)` (`[OBSERVED]`). | `[OBSERVED]` |
| `fp` (Fingerprint) | Base64 (44 chars) in QR & JSON invites; lowercase Hex (64 chars) in mDNS TXT & `fex`/`svc 168` | Exact same 32-byte `SHA-256(cert.der)`. Note: in mDNS packets, `0x43` (`'C'`, 67 bytes) before `fp=` is the RFC 6763 TXT string length byte (`3 + 64 = 67`), not part of the key name (`[OBSERVED]`). | `[OBSERVED]` |
| `perPairKey` | Base64 (standard, padded, 44 chars) of `32` random bytes | Minted by the PC per paired phone, sent in `svc 0 cmd 0` Verify Reply and `svc 0 cmd 1` `EXCHANGE_KEY`. Used **directly** (no KDF) as the 256-bit `AES-256-GCM` key for `SealedDiscoveryFrame`s. | `[OBSERVED]` |

#### Normative Salted-Hash Peer Lookup (`matchNonceIdToPairedPeer`)
When `ec0` receives an mDNS advert (`TXT deviceId`), a BLE `0x5246` advertisement (`bytes 4..19`), or a `/readyfor/mdns` binary discovery frame (`bytes 0..15`), it identifies which paired peer sent it via:
1. Extract `salt = nonceIdRaw16.copyOfRange(0, 8)` and `expectedHashPrefix = nonceIdRaw16.copyOfRange(8, 16)`.
2. For each `peer` in `PairedPeerStore`:
   - Check exact match against `peer.lastSeenNonceIdRaw16` (fast path).
   - Compute `digest = Sha256.hash(peer.realDeviceIdRaw16 + salt)` and check `MessageDigest.isEqual(digest.copyOfRange(0, 8), expectedHashPrefix)`.
3. **Pinned Golden Vector (verified via Python `hashlib` & reference `pairing-and-discovery.md`):**
   - `realDeviceIdRaw16` = `000102030405060708090a0b0c0d0e0f` (`"AAECAwQFBgcICQoLDA0ODw=="`)
   - `salt8` = `1011121314151617`
   - `SHA-256(realDeviceIdRaw16 || salt8)[0..8)` = `1d64add2a6388367`
   - `nonceIdRaw16` = `10111213141516171d64add2a6388367` (`"EBESExQVFhcdZK3SpjiDZw=="`)

---

### 1.4 Sealed Discovery Frames (`SealedDiscoveryFrameCodec`)

Reconnection without scanning a new QR code uses single binary WebSocket frames on `/readyfor/mdns` (or GATT writes to `0x0722`) encrypted with `AES-256-GCM` `[OBSERVED]`:

```text
┌───────────────────────────┬──────────────────┬──────────────────┬──────────────────────────────────────┐
│ senderNonceId (16 bytes)  │ frameType (1 B)  │ iv (12 bytes)    │ ciphertext (N bytes) ‖ gcmTag (16 B) │
└───────────────────────────┴──────────────────┴──────────────────┴──────────────────────────────────────┘
  Bytes 0..15 (raw bytes,     Byte 16:           Bytes 17..28       Bytes 29..(29 + N + 16 - 1)
  NOT base64)                 0x01 = SEALED      Random 96-bit IV   Plaintext = UTF-8 JSON (Invite cmd 1
                              0x02 = PLAINTEXT   per frame          or Trigger cmd 0)
                              0x03 = DECRYPT_ERR
  |<────────── AAD (17 bytes: 0..16) ─────────>|
```

- **Cryptographic Parameters (`javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")`):**
  - **Key:** 32-byte `perPairKey` (Base64-decoded from `paired.json`), used directly with zero KDF.
  - **IV:** 12-byte random nonce (`GCMParameterSpec(128, iv, 0, 12)`).
  - **AAD (Additional Authenticated Data):** Exactly the first `17` bytes (`senderNonceId[0..16) || frameType`), authenticating both sender identity and frame type.
  - **Fallback Key Trial on Receive:** If decryption under `peer.ourPerPairKey` fails with `AEADBadTagException`, try `peer.phoneKey` (the key the phone sent in `svc 0 cmd 1` during initial pairing).
- **CRITICAL UNPAIR HAZARD (`frameType = 2` or `3`):**
  - The phone treats incoming `frameType == 2` (`PLAINTEXT`) as *"this PC unpaired us"* and `frameType == 3` (`CANNOT_DECRYPT`) as an unrecoverable key mismatch — **both cause the phone to immediately delete the PC's pairing record** `[OBSERVED]`.
  - **Normative Rule in `ec0`:** `SealedDiscoveryFrameCodec.seal()` hardcodes `frameType = 0x01`. On decryption failure, `open()` logs a diagnostic warning and drops the frame silently — `ec0` **never** emits `0x02` or `0x03`.
- **Pinned Golden Test Vector (`54` bytes total, pinned for `SmartConnectDiscoveryFrameTest`):**
  - `perPairKey` (32B hex): `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f`
  - `senderNonceId` (16B hex): `a0a1a2a3a4a5a6a7a8a9aaabacadaeaf`
  - `frameType` (1B hex): `01`
  - `iv` (12B hex): `000102030405060708090a0b`
  - `plaintext` (9B ASCII): `{"cmd":1}`
  - `ciphertext` (9B hex): `3c20b576a1c7f82af0`
  - `gcmTag` (16B hex): `f7245b9e966e997f676a43dd38cacf53`

---

### 1.5 Discovery (`_readyformdns._tcp.local.`) & Bluetooth LE Wake (`0x5246`)

#### 1. `_readyformdns._tcp.local.` mDNS Discovery
Both PC and phone advertise `_readyformdns._tcp.local.` on UDP `224.0.0.251:5353` `[OBSERVED]`:

| DNS-SD Field | PC Value (`ec0` Desktop) | Phone Value (Stock Smart Connect / `:android-app`) | Tag |
|---|---|---|---|
| **Service Type** | `_readyformdns._tcp.local.` | `_readyformdns._tcp.local.` | `[OBSERVED]` |
| **SRV Port** | Control/discovery listener port (default `52020`) | Ephemeral port in `11000..11999` (foreground only) | `[OBSERVED]` |
| **TXT `deviceId`** | `<pc-nonce-id-b64>` | `<phone-nonce-id-b64>` | `[OBSERVED]` |
| **TXT `deviceName`** | `<pc-alias>` | `<phone-alias>` | `[OBSERVED]` |
| **TXT `type` / `os`** | `"4"` / `"6"` | `"1"` / `"1"` | `[OBSERVED]` |
| **TXT `appType`** | `"1"` | *(omitted)* | `[OBSERVED]` |
| **TXT `cap`** | `"0"` | `"<int>"` | `[OBSERVED]` |
| **TXT `timestamp`** | Unix epoch **seconds** | Unix epoch **milliseconds** | `[OBSERVED]` |
| **TXT `fp`** | 64-char lowercase hex `SHA-256(cert.der)` | 64-char lowercase hex `SHA-256(cert.der)` | `[OBSERVED]` |

- **Linux Avahi Coexistence + Pure-Kotlin `MdnsCodec`:**
  - `ec0`'s pure-Kotlin `MdnsCodec` (`core/src/commonMain/kotlin/ec0/core/engine/MdnsCodec.kt`) is extended with `encodeReadyForMdnsAnnounce()` and `extractReadyForMdnsPeers()`.
  - On Linux desktops where `avahi-daemon` already binds UDP `5353`, `LinuxSystemBridge` also supports spawning supervised `avahi-publish -s <name> _readyformdns._tcp <port> ...` and `avahi-browse -r -p -k _readyformdns._tcp` child processes when present, falling back to `MulticastMdnsTransport` when `avahi` is absent (and using `MulticastMdnsTransport` + `WifiManager.MulticastLock` on Android).
- **Phone Foreground-Only Discovery Quirk & `ECONNREFUSED` Presence Probe (`[OBSERVED]`):**
  - The stock Smart Connect phone app only advertises `_readyformdns._tcp` and listens on its `11000..11999` `/readyfor/mdns` port while the app UI is in the foreground on a secured Wi-Fi network.
  - When backgrounded, an active `/readyfor/` control session stays alive, but the phone closes its `/readyfor/mdns` listener.
  - Consequently, a TCP dial to the phone's last-known IP that returns **`ECONNREFUSED` (TCP RST in ~35 ms)** proves the phone is **present on the LAN with the app in the background**, whereas a connect timeout means the phone is off the LAN.

#### 2. Bluetooth LE Wake (`0x5246`) — Waking a Backgrounded Phone Without Cloud
Because a backgrounded phone stops its `/readyfor/mdns` TCP listener, **Bluetooth LE GATT `0x5246` is the only local channel that wakes a backgrounded phone without a Lenovo cloud account** (`[OBSERVED]`):

1. **Phone BLE Advertisement (`0x5246`, runs 24/7 at 250 ms interval, -7 dBm):**
   - 20-byte Service Data under 16-bit UUID `0x5246` (`00005246-0000-1000-8000-00805f9b34fb`):
     - `byte[0]`: `0x10` (`0x12` for tablet)
     - `byte[1]`: `(subtype << 4) | osId` (`osId = 1` Android, `4` Windows, `5` Linux)
     - `bytes[2..3]`: Little-endian `u16` capability bitmask
     - `bytes[4..19]`: **Phone's raw 16-byte `nonceId`**
2. **PC BLE Wake Sequence (`BleWakeCoordinator` in `ec0.core.engine.linux`):**
   - **Scan Rule:** Never filter the BlueZ scan by Advertised Service UUIDs — when backgrounded, the phone omits `0x5246` from the UUID list and places it **only** in the `ServiceData` map (`[OBSERVED]`). Filter on `ServiceData[0x5246]` length `== 20` with a current RSSI (ignoring stale BlueZ cache entries) where `bytes[4..19]` matches a paired phone's `realDeviceId` via `matchNonceIdToPairedPeer()`.
   - **GATT Write Sequence:**
     1. Connect as GATT Central to the rotating BLE MAC address (12 s timeout; if timed out, explicitly invoke BlueZ `Disconnect` to cancel the pending controller connection before retrying).
     2. Discover Primary Service `0x5246`.
     3. Enable indications on characteristic `0x0722` and notifications on `0x0726`.
     4. Write one `SealedDiscoveryFrame` (`type = 1`, prefix = PC's raw 16-byte `nonceId`, sealed with `perPairKey`, JSON payload = `cmd 1` Invite) to characteristic `0x0722`.
     5. The phone wakes from background and dials `wss://<pc-ip>:<wsPort>/readyfor/` within **~0.3 seconds** (5–6 s total from scan start). Once the control WebSocket verifies, disconnect the BLE link.
   - **Audio Safety Rule:** Never power-cycle the Linux Bluetooth adapter (`bluetoothctl power off/on`), as BlueZ will disrupt/reconnect the user's active A2DP/HFP headphones.

---

## 2. Cap-Map Constants, Versioning Gates & Security Model (Wire Truth)

### 2.1 Normative `SmartConnectConstants` (`core/src/commonMain/kotlin/ec0/core/engine/smartconnect/SmartConnectConstants.kt`)

```kotlin
package ec0.core.engine.smartconnect

object SmartConnectConstants {
    // QR prefix: literal ASCII followed immediately (no separator) by JSON
    const val QR_PREFIX: String = "motorolardpconnection"
    const val OUTER_ENVELOPE_VER: String = "3.0.0"
    const val DEFAULT_WS_PORT: Int = 52020

    // WebSocket paths
    const val WS_PATH_CONTROL: String = "/readyfor/"
    const val WS_PATH_MDNS: String = "/readyfor/mdns"
    const val MDNS_SERVICE_TYPE: String = "_readyformdns._tcp.local."

    // Close codes
    const val WS_CLOSE_NO_USED_OR_DUPLICATE: Int = 3123

    // PC identity constants required by the phone's version & peer-type gates
    const val PC_OS_WINDOWS: Int = 6
    const val PC_TYPE_DESKTOP: Int = 4
    const val PC_SC_TYPE: Int = 2
    const val PC_VER_CODE: Long = 903013000L       // Must be >= 700_000_000L (else VersionMatchingDialogActivity)
    const val PC_VER_NAME: String = "09.0.3.013.000" // Must be non-null in verify reply (null crashes phone app)

    // Service IDs on the control WebSocket
    const val SVC_MANAGER: Int = 0
    const val SVC_SESSION_CONTROL: Int = 1
    const val SVC_FEX: Int = 2
    const val SVC_WEBCAM: Int = 3
    const val SVC_NOTIF_SHOW_LIST: Int = 5
    const val SVC_WEBVIEW_CONFIRM: Int = 8
    const val SVC_CLIPBOARD: Int = 9
    const val SVC_HOTSPOT: Int = 13
    const val SVC_APP_STREAM: Int = 15
    const val SVC_INPUT: Int = 16
    const val SVC_APP_LIST: Int = 17
    const val SVC_PAIRING_HELPER: Int = 19
    const val SVC_FILE_EXPLORER_WIDGET: Int = 20
    const val SVC_PC_LOCK: Int = 22
    const val SVC_DRAG_DROP: Int = 24
    const val SVC_DEVICE_STATUS: Int = 25
    const val SVC_CROSS_CONTROL: Int = 26
    const val SVC_SHARE_HUB: Int = 30
    const val SVC_NOTIF_OTHER: Int = 37
    const val SVC_MESSAGES_STATUS: Int = 100
    const val SVC_FILE_STATION: Int = 168
    const val SVC_PHONE_AS_DISPLAY: Int = 202
    const val SVC_MESSAGES_SMS: Int = 204
    const val SVC_FILES_TAB_PC_SHARE: Int = 206
    const val SVC_LOCAL_DAEMON_EVENT: Int = -1

    // Service 25 `cmd 1` capability bitmask flags (per-service int value in `cap` map)
    const val CAP_AVAILABLE: Int = 1        // 1 shl 0: Feature tile enabled & available [OBSERVED]
    const val CAP_EXTRA_MODE: Int = 2       // 1 shl 1: Extra feature mode [INFERRED]
    const val CAP_CONNECTED_SELF: Int = 4   // 1 shl 2: Active session on phone's own map [INFERRED]
    const val CAP_IN_USE: Int = 8           // 1 shl 3: Feature active/in-use (required by svc 26 within 1s of reconnect) [OBSERVED]
    const val CAP_USER_TURNED_OFF: Int = 16 // 1 shl 4: Disabled by user / missing permission on phone [OBSERVED]

    // Baseline maps advertised in Service 0 Verify Reply (staged as slices S-A..S-E come online)
    val MIN_VER_CODE_MAP: Map<String, Long> = mapOf(
        "1" to 7_000_000L,
        "2" to 700_000_000L,
        "3" to 7_000_000L,
        "4" to 7_000_000L,
    )

    val FULL_SERVICE_VERSIONS: Map<String, Int> = mapOf(
        "0" to 3,    // Manager
        "1" to 10,   // Mirror / Mobile Desktop / Screen Share (>= 6 includes desktop resolution)
        "2" to 5,    // fex File Exchange
        "3" to 3,    // Webcam
        "5" to 2,    // Notifications
        "9" to 4,    // Clipboard (MUST be >= 2 or phone never sends NEGO)
        "13" to 1,   // Hotspot
        "15" to 16,  // App Stream (>= 10 includes cmp; >= 15 accepts cmd 24 visible windows)
        "22" to 2,   // PC Lock (MUST be >= 2 or phone ignores lock tile)
        "24" to 2,   // Drag & Drop
        "25" to 1,   // Device Status & Tiles
        "26" to 1,   // Cross Control
        "202" to 1,  // Phone-as-Display (R4SS)
        "204" to 1,  // SMS / Messages
    )

    val FULL_DEVICE_CAPS: Map<String, Int> = mapOf(
        "1" to 2,
        "2" to 3,
        "3" to 3,
        "5" to 2,
        "9" to 3,
        "13" to 2,
        "15" to 3,
        "16" to 3,
        "22" to 1,
        "24" to 3,
        "26" to 2,
        "168" to 3,
        "202" to 1,
        "204" to 1,
    )

    // Default Service 25 `cmd 1` tile capability map (see §2.2 for "202" & "206" rules)
    val DEFAULT_TILE_CAP_MAP: Map<String, Int> = mapOf(
        "1" to CAP_AVAILABLE,  // Screen Share & Mobile Desktop tiles
        "3" to CAP_AVAILABLE,  // Webcam tile
        "13" to CAP_AVAILABLE, // Hotspot tile
        "15" to CAP_AVAILABLE, // App Streaming tile
        "22" to CAP_AVAILABLE, // PC Lock tile
        "26" to CAP_AVAILABLE, // Cross Control tile
    )
}
```

### 2.2 The Four Tile-Gating Rules & Replace-Whole Invariants (`[OBSERVED]`)

1. **Four-Check Tile Gate:** The phone renders a tile for service `S` on the PC's device page only when:
   - Check 1: Phone hardware/OS supports `S`.
   - Check 2: Peer has `type = 4, scType = 2` (PC view bits).
   - Check 3: `S` is a key in the verify-reply `serviceVersions` map (cached per `realDeviceId` at verify time; changes require a reconnect). **Never include `rfaServiceVersions`** in the verify reply — if present, the phone uses it **instead of** `serviceVersions` (`[OBSERVED]`).
   - Check 4: `S` is a key in the most recent `svc 25 cmd 1` `cap` map sent by the PC.
2. **Replace-Whole Semantics (`SmartConnectCapRegistry`):**
   - Every `svc 25 cmd 1` message **completely replaces** the phone's stored `cap` map (`[OBSERVED]`).
   - In `ec0-engine`, helpers (such as Cross Control setting `"26": 9` when active, or File Station `serve` setting `"206": 1, "20": 1` while exporting a PC folder) **never send raw `svc 25 cmd 1` frames directly**. Instead, they call `capRegistry.updateOverride(serviceId, flags)`, which merges the override into the active base map and emits the **complete** map in every `svc 25 cmd 1` frame.
3. **Why `"202"` (Phone-as-Display) is Omitted from `DEFAULT_TILE_CAP_MAP` (`[OBSERVED]`):**
   - The phone merges Service `26` (Cross Control) and Service `202` (PC Display) into a single dashboard tile and dispatches whichever connected last (`202` defaults to `"extend"`). If `"202": 1` is in `svc 25 cap`, tapping the Cross Control tile after any display session forces `svc 202 cmd 50` (Extend display) with no picker.
   - Keeping `"202": 1` in `serviceVersions` and `device.caps` while omitting `"202"` from `svc 25 cap` ensures the phone tile always triggers Service `26` (`cmd 0`), while `svc 202` can still be launched at any time from the `ec0` Desktop Display panel (or enabled on the tile via an explicit Settings toggle).
4. **Honest Handler Advertising:**
   - Only services whose PC handler is registered and enabled are placed into `DEFAULT_TILE_CAP_MAP` during each slice (`S-A` → `S-E`). Advertising Service `13` (Hotspot) without a handler causes the phone to start Wi-Fi tethering before the PC answers (`[INFERRED]`).

### 2.3 Security Model: Cert Pinning, Invite Tokens, Per-Pair Keys & Replay Defense

| Security Mechanism | Normative `ec0-engine` Implementation |
|---|---|
| **QR / Invite Leaf Cert Pinning (`fp`)** | PC generates an `RSA-2048` self-signed cert once (`EncryptedIdentityStore`). QR and invites carry `fp = Base64(SHA-256(cert.der))`; mDNS TXT carries `fp = hexLower(SHA-256(cert.der))`. Phone pins `fp` on `/readyfor/`. For outbound connections to the phone (`fex` on `50000..59999`, NFS `SOCKET_SSL` on `svc 168`), `ec0` pins the phone's `SHA-256(DER)` fingerprint advertised over the authenticated control channel. |
| **Short-Lived Invite Token (`token`)** | 32 bytes from `SecureRandom`, Base64-encoded with `=`, `+`, `/` mapped to random alphanumeric chars (`~44` chars). Default TTL = `180 s` (configurable). Verified using constant-time `MessageDigest.isEqual`. **Not single-use within TTL** because the phone opens multiple connections during initial pairing (`[OBSERVED]`). Invalid/expired token → immediate TCP socket close with **no** WebSocket close frame (`[OBSERVED]`). |
| **Per-Pair Secret Storage (`paired.json`)** | Stored in `~/.config/ec0/smartconnect_paired.json` (`0600` POSIX permissions) via `PersistentEngineStore`: `{realDeviceId, deviceName, model, ourPerPairKeyB64, phoneKeyB64, lastKnownIp, lastMdnsPort, lastFileStationFps}`. |
| **Reconnect Auth & Known Protocol Gap Mitigation** | Stock Smart Connect phones reconnect either with the fresh `token` from a `/readyfor/mdns` / BLE invite **or** by presenting a previously paired `device.realDeviceId` (`paired: 1`) with an opaque reconnect token (`[OBSERVED]`). To mitigate LAN spoofing of a known `realDeviceId`, `ec0` enforces: (1) strict token verification whenever an invite was issued in the last `60 s`, (2) subnet/IP consistency logging, and (3) a user toggle `"Require Fresh Invite Token on Reconnect"` (defaulting to compatible mode for stock phone behavior, strict mode when pairing with `:android-app`). |
| **Envelope Ordering & Per-Service Deduplication** | The Smart Connect control channel runs inside TLS 1.2/1.3 (which provides record-level sequence numbers and anti-replay). At the application layer, `ec0` tracks monotonic outbound `msgId` (`1 .. Int.MAX_VALUE`), deduplicates `svc 16` input via `(instanceId, messageId)`, suppresses `svc 9` clipboard echoes via `SHA-256`, and coalesces `svc 5/37` notification bursts by `(key, visibleTextHash)` at `1 Hz`. |
| **Session Log Redaction (`--session-log`)** | Diagnostic JSONL frame logging is **off by default**. When enabled (`0600` file mode), `SmartConnectRedactor` strips all values of keys named `key`, `token`, `pwd`, `password`, `passwd`, `secret`, `psk` (replacing with `<redacted:len=N>`), redacts `svc 5/37` notification bodies/images, `svc 9` clipboard text, `svc 100/204` SMS bodies/addresses, and replaces `svc 25 cmd 2` `wallpaper` base64 with `<wallpaper:len=N>`. |

---

## 3. Phased Slice Plan (`S-A` … `S-E`)

### Slice `S-A` — Transport Trait, Pairing/Discovery, `svc 0` Handshake & `svc 25` Status/Cap-Map
**Goal:** Achieve **first live pairing and tile rendering with Aryan's OPPO A5x (`CPH2733IN`)** running stock Smart Connect `09.0.4.005.0`, plus Wi-Fi `/readyfor/mdns` and BLE `0x5246` auto-reconnect.

1. **QR Code Generation (`SmartConnectQrGenerator`):**
   - Renders `motorolardpconnection{"wsData":{...},"version":"3.0.0"}` using `ec0`'s existing zero-dependency `QrCodeMatrix` (`core/src/commonMain/kotlin/ec0/core/engine/QrCodeMatrix.kt`).
   - Required `wsData` fields: `cmd: 1` (**must be `1`**, `0` triggers a bogus Bluetooth timeout error), `ver: 2`, `appVer: "09.0.3.013.000"`, `i3: 1`, `deviceId: <pcNonceId>`, `realDeviceId: <pcRealId>`, `deviceName`, `serviceId: 19`, `token`, `fp: <base64-44>`, `wsPort: 52020`, `policies: [{"wifi": [<reachable-lan-ipv4s>]}]` (filtering out `docker0`, `veth*`, `tun*`, `wg*`, `virbr*`), `os: 6`, `type: 4`, `verCode: 903013000`, `verName: "09.0.3.013.000"`, `bleAddress: ""`, and nested `device` object.
2. **6-Step Service 0 Handshake & Commit State Machine (`SmartConnectHandshakeCoordinator`):**
   - **Step 1 (Inbound Upgrade):** Accept TLS on `wsPort` (`15 s` limit) → WebSocket upgrade on `/readyfor/`.
   - **Step 2 (Phone Hello `svc 0 cmd 0` or `cmd 3`):** Must arrive within `40 s`. Validate `token` (constant-time) OR verify `data.device.realDeviceId` is in `PairedPeerStore`. Tolerate `09.0.4.005.0` extra `DeviceBean` fields (`cloudPush`, `topNetworkType`, `requestId`, `hostIp`, `parentFolder`, `completedSize`, `totalSize`) via `ignoreUnknownKeys = true`. Cache the phone's `serviceVersions` and `ips`.
   - **Step 3 (PC Verify Reply `svc 0 cmd 0`):** Send within `< 100 ms`: `token: "OK"`, `paired: 1`, `connType: 1`, complete `device` object (`deviceId: <pcNonceId>`, `realDeviceId: <pcRealId>`, `deviceName`, `os: 6`, `type: 4`, `scType: 2`, `verCode: 903013000`, `verName: "09.0.3.013.000"`, `appVer: "09.0.3.013.000"`, `minVerCode`, `sn: <non-empty-uuid>`, `Sku`, `sku`, `cap: 0`, `caps`, `serviceVersions`, `key: <perPairKeyB64>`, `isPaired: true`) and top-level `serviceVersions` (**never** `rfaServiceVersions`).
   - **Step 4 (Immediate `svc 25 cmd 1` Tile Cap Map):** Immediately after Verify Reply, send `svc 25 cmd 1` with `cap` map, `battery`, `deviceName`, `realDeviceName`, `model`.
   - **Step 5 (Pairing Commit `svc 0 cmd 1` `EXCHANGE_KEY`):**
     - When the phone sends `svc 0 cmd 1` (`sendSecretKey` carrying `device.key = <phoneKeyB64>`), record `phoneKeyB64` and reply within `< 50 ms` with `svc 0 cmd 1` (`EXCHANGE_KEY`) where **`device.deviceId` AND `device.realDeviceId` are BOTH set to `<pcRealId>`** (NOT `<pcNonceId>` — the phone marks the PC tile online only when `liveSession.realDeviceId == storedPairingRecord.deviceId` `[OBSERVED]`).
     - **2-Second Unprompted `EXCHANGE_KEY` Timer:** If the phone has not sent `cmd 1` within `2,000 ms` of Verify Reply, send `EXCHANGE_KEY` unprompted anyway (recovers the PC tile if the user deleted the PC from the phone while the phone still sent `paired: 1` `[OBSERVED]`).
   - **Step 6 (Post-Pair 5-Second Discovery Refresh):** `5 s` after a first pair commits, push a `SealedDiscoveryFrame` (`cmd 1` Invite) to the phone's `/readyfor/mdns` endpoint so the phone reconnects and flips its newly created PC tile from *"Offline"* to *"Connected"* (`[OBSERVED]`).
3. **Service 25 Device Status & Keepalive Absorbers:**
   - Parse incoming phone `svc 25 cmd 1` (`battery`, `isCharging`, `wifi`, `wifiSignal`, `dataSignal`, `bluetooth`, `deviceName`, `model`, `cap` — checking bit `16` on `"5"`, `"9"`, `"204"` to surface UI warnings when phone-side sync permissions/switches are off).
   - Parse incoming phone `svc 25 cmd 2` (`wallpaper` base64 JPEG + `md5`; decode and write to `$XDG_RUNTIME_DIR/ec0/phone-wallpaper.jpg` only when `md5` changes; never log base64).
   - Absorb/echo phone connect bursts (`svc 1 cmd 31` supported views bitmask, `svc 16 cmd 14` `SYNC_INPUT_MODE`) and honour `svc 0 cmd 6` user disconnect (latching `userDisconnected = true` to suppress auto-reconnect until manual connect).
4. **Discovery & Reconnect Loop (`SmartConnectReconnectLoop`):**
   - `/readyfor/mdns` server handling incoming phone `cmd 0` sealed triggers by replying on the same socket with a `cmd 1` sealed invite (echoing `reqId` if non-zero) AND dialing back the phone's mDNS-advertised port.
   - Backoff schedule: `8 s` startup grace → Wi-Fi `/readyfor/mdns` dial-back → BLE `0x5246` GATT wake (`0x0722`) → backoff `2s, 5s, 15s, 30s, 60s` (capped at `60s` for 15 attempts while `ECONNREFUSED` 30s presence probe confirms phone is on LAN, then `300s`), with instant nudge on suspend-resume (`System.nanoTime()` vs `System.currentTimeMillis()` drift), LAN IP change, or BT adapter power-on.

---

### Slice `S-B` — Control-WS Services: Clipboard (`svc 9`), Notifications (`svc 5/37`), PC Lock (`svc 22`)
**Goal:** Ship the three highest-value, zero-media-plane features over the established `S-A` control WebSocket.

1. **Service 9 — Bidirectional Clipboard (`SmartConnectClipboardService`):**
   - **Envelope Quirk:** Dispatch key is `data.msgId` (not `cmd`), with payload nested in `data.data` (`{"msgId": 3, "data": {"clipId": 1001, "text": "..."}}`) `[OBSERVED]`.
   - **Handshake:** Requires `serviceVersions["9"] = 4` (`>= 2`). On receiving `msgId = 1` (`NEGO`, `version: 1`) from the phone, reply within `50 ms` with `msgId = 2` (`CONFIRM`, `version: 1`) and mark clipboard session ready. If no `NEGO` arrives within `3 s` of Verify Reply (or `phoneCap["9"] & 16 != 0`), surface *"Phone clipboard sync switch is off"* in UI.
   - **Text Sync (`msgId = 3` `UPDATE_NOTIFY`):**
     - Phone → PC: Extract `data.text` (skip blank or `clipId == 0` multi-PC broadcasts), compute `SHA-256`, record in `lastRemoteClipSha256`, and apply to OS clipboard via `LinuxClipboardBridge` (`wl-copy` / `xclip`) or Android `ClipboardManager`.
     - PC → Phone: Triggered by `wl-paste --watch` (ignoring `sensitive` password-manager copies and `SHA-256 == lastRemoteClipSha256` echoes). Assign monotonic positive 32-bit `clipId` (seeded from `(System.currentTimeMillis() / 1000).toInt() and 0x3FFFFFFF`), truncate or warn above `100,000` chars, and send `{"msgId": 3, "data": {"clipId": id, "text": text}}` (omitting all file fields).
   - **Forbidden Message Guard:** Strictly block outbound `msgId` `13..17` (`13` infinite-loops the phone handler; `15` throws `ClassCastException`), block `msgId = 4` (`PULL`) on text clips, and never send `msgId = 101` (`GLOBAL_SETTINGS_DISABLE`, which permanently disables clipboard sync for this PC on the phone). `msgId = 100` (`GLOBAL_SETTINGS_ENABLE`) is exposed only via an explicit user button.
2. **Services 5 & 37 — Notification Mirroring, Quick Reply & Action Routing (`SmartConnectNotificationService`):**
   - **Inbound Events (`event = 0` Posted, `1` Updated, `2` Removed):**
     - `svc 5` carries show-list apps; `svc 37` carries other apps; removals (`event = 2`) arrive on both.
     - Drop `type == 7` (custom view, matching Windows behavior). Support styles `0` (simple), `1` (big picture), `2` (big text), `3` (messaging), `4` (inbox), `5` (media), `6` (call).
     - **Rate-Limiting & Deduplication:** Compare visible text (`title`, `content`, `big_text`, `messages`) ignoring re-encoded JPEG bytes; coalesce updates (`event = 1`) to at most **1 update per second per `key`**.
     - **Avatar Disambiguation (`person_icon` vs `apk_icon`):** For `type == 3` (Messaging), Android's `MessagingStyle` serializes the device owner ("self") in `person_name` / `person_icon` (e.g., WhatsApp puts the phone owner's photo on every chat `[OBSERVED]`). Use `person_icon` **only** when `person_name == title` (or `type == 6` Call); otherwise resolve the app icon from `~/.cache/ec0/app-icons/<pkg>*.png` → desktop theme icon → `apk_icon` (stripping 76-col base64 line breaks before decoding).
   - **Outbound Commands (sent on the same `serviceId` `5` or `37` the notification arrived on):**
     - **Quick Reply (`cmd: 0, event: 0`):** `{"cmd":0,"event":0,"key":key,"version":"1.0","data":{"reply_content":text,"smart_reply":false}}`. Works directly on the control WS without app streaming!
     - **Action Button (`cmd: 0, event: 1`) & Open (`cmd: 0, event: 2`):** Emit local event `service = -1` (`notification_action`, `kind = "action" | "open"`, `pkg`, `key`, `actionId`) to the stream host before sending the frame so `S-D` answers the phone's resulting `svc 15 cmd 2` app-stream invite (headlessly for `"action"`, closing with `cmd 7` `2.5 s` after `cmd 13` focus; in a window for `"open"`).
3. **Service 22 — Remote PC Lock (`SmartConnectPcLockService`):**
   - Advertise `serviceVersions["22"] = 2` and `svc 25 cap["22"] = 1`.
   - On receiving `svc 22 cmd 1` (`{"ver":"1","cmd":1}`):
     1. Execute desktop lock cascade: custom lock command (if configured) → Noctalia IPC (`qs ipc --pid <pid> call lockScreen lock`) → `loginctl lock-session <graphicalSessionId>`.
     2. Poll `loginctl show-session <id> -p LockedHint` for up to `3 s`.
     3. Once locked, send `svc 22 cmd 2` (`{"ver":"1","cmd":2,"state":1}`), then poll `LockedHint` every `2 s` and send `{"ver":"1","cmd":2,"state":0}` when the user unlocks the PC. If locking failed (e.g., GNOME without GDM), send `state: 0` and display a desktop notification explaining why.

---

### Slice `S-C` — File Exchange: `fex` (`svc 2` & `svc 17` App Icons) + File Station NFSv3 over mTLS (`svc 168`)
**Goal:** Enable app-icon harvesting (`svc 17`, unlocking `recentApps`), clipboard image/file pulls (`svc 9` + `svc 2`), and bidirectional zero-copy file browsing (`svc 168` NFSv3 over mutual TLS).

1. **`fex` / FileEx Client & Server (`SmartConnectFexClient`, `SmartConnectFexServer`):**
   - **Wire Protocol (`[OBSERVED]`):** When the phone offers app icons (`svc 17 cmd 1` carrying `port`, `fingerprint`, `token`) or clipboard images/files (`svc 9 msgId 4 PULL` → `svc 2 FileExSendData` on a random port in `50000..59999`), the sender runs an HTTPS + WebSocket server on `phone-ip:port` using a fresh self-signed RSA-2048 cert.
   - **Client Pull Sequence:**
     1. Connect TLS 1.3 to `phone-ip:port` offering **ALPN `http/1.1` only** (needed for WebSocket upgrade), pinning the certificate by lowercase hex `SHA-256(DER) == advert.fingerprint`.
     2. Upgrade `GET /websocket`.
     3. Receive `action:Server1001:versionNegotiation?{"version":1}` → send `ack:Server1001:versionNegotiation?{"version":1}`.
     4. Receive `action:Server1002:sendRequest?{"id":"<taskId>",...,"totalSize":<zipBytes>,"file":[...]}` → send `ack:Server1002:sendRequest?{}`.
     5. Open second pinned TLS connection: `GET /download?taskId=<taskId>&token=<token>` (verify response is `application/zip` and not the 200 text error `"download -> taskId ... does not exist!"`; or `GET /downloadFile?taskId=<taskId>&token=<token>&index=<n>` for direct single-file mode).
     6. Stream and extract PNG icons into `~/.cache/ec0/app-icons/` (sanitizing entry names, rejecting paths/`..`, atomic temp-file rename, guarded by `$XDG_RUNTIME_DIR/ec0/app-icons.lock`).
     7. Send `action:Client1001:status?{"taskId":"<taskId>","type":1,"reason":""}` and await `ack:Client1001:status` (which triggers the phone's `onComplete` and causes `svc 17` to push `cmd 10` `recentApps`!).
2. **Service 168 — File Station Tunnelled NFSv3 over Mutual TLS (`SmartConnectFileStationService`):**
   - **Transport Selection (`dt2type = 2` `SOCKET_SSL`, `[OBSERVED]`):**
     - Sent and received on **`serviceId = 168` (`srcServiceId = 168`)** (never `30`, which is dead Share Hub).
     - Outer `data` is `StationData`: `{"ver":"1.0","cmd":1001,"data":"<JSON-string>"}`.
     - We always send and reply with **`"dt2type": 2` (`SOCKET_SSL`)**, which forces the phone to use plain **mutual-TLS over TCP** instead of the proprietary native `dt2socket` QUIC tunnel (`dt2type: 3`).
     - Both sides authenticate via a dedicated File Station X.509 keypair (`~/.config/ec0/smartconnect/files-identity/{cert.der,key.pkcs8}`, mode `0600`), pinning each other's uppercase-hex `SHA-256(DER)` `fp` and bypassing PKIX v3 validation so the phone's X.509 v1 certificate is accepted.
   - **Direction A — Browse Phone Storage from Desktop (`type: 0` Request → `type: 1` Reply):**
     - PC sends `cmd = 1001` with inner JSON `{"type":0,"dt2type":2,"nfs_dependency":8,"fp":"<PC-files-cert-fp-hex>"}`.
     - Phone replies with `{"type":1,"path":"/sdcard","port":<pm>,"nfsport":<nfs>,"fp":"<phone-cert-fp>","ip":"<phone-ip>","nfs_version":8,"nfs_capability":25,"dt2type":2}`.
     - PC connects mTLS to `ip:nfsport` (skipping `PORTMAP` `port` due to the phone's every-4th-call `GETPORT` bug), issues RFC 1813 `MOUNT` v3 `MNT("/sdcard")` → receives the 64-byte all-zero root handle, and speaks standard NFSv3 (`GETATTR`, `LOOKUP`, `READDIRPLUS`, `READ`) to power the Desktop File Browser and optional FUSE mount (`~/Phone`). Automatically re-runs `cmd = 1001` upon session reconnect because phone NFS ports are per-session.
   - **Direction B — Share PC Folder with Phone (`serve`, Phone auto-sends `type: 0` → PC replies `type: 1`):**
     - When enabled by the user, `capRegistry` sets `"206": 1, "20": 1` in `svc 25`.
     - PC answers the phone's auto-sent `cmd = 1001` (`type: 0`) with `type: 1` advertising a single mTLS port (`port = nfsport = nfsDataPort`, `dt2type: 2`, `nfs_version: 8`, `nfs_capability: 25`, `path: "/PhoneShare"`).
     - **64-Byte File Handle Invariant (`[OBSERVED]`):** Every file handle returned by the PC's NFSv3 server (`LOOKUP`, `READDIRPLUS`) MUST be **exactly 64 bytes** (`8B generation + 56B inodeId`), substituting the real root handle whenever the phone sends the 64-byte all-zero root handle.
     - Implements `cmd = 2006 -> cmd = 2007` (`{"port":P,"nfsport":P}`) 3-second I/O recovery and Lenovo custom NFS procedures `22` (`READTHUMBNAIL` ≤320px JPEG), `23` (`READCATEGORYDIR` categories `0..7` with JSON strings), and `27` (`REMOVEBATCH` → moves to Trash or refuses when `--read-only`). Hidden dotfiles (`~/.ssh`, keys) are excluded by default.

---

### Slice `S-D` — WebRTC Media Plane & Supervised Helpers (`svc 1/15/16`, `svc 3`, `svc 15/17`, `svc 26`, `svc 202`)
**Goal:** Deliver wireless, ADB-free Screen Mirroring, Ready For Desktop, Per-App Streaming Windows, Continuity Webcam, Cross Control, and Phone-as-Display using a shared WebRTC engine and supervised helper architecture.

#### 1. Shared WebRTC Engine Rules (`SmartConnectWebRtcEngine`)
All four video features (`mirror`, `appstream`, `webcam`, `phone-as-display`) share one set of device-verified WebRTC invariants (`[OBSERVED]`):
- **Signalling Roles:** The **phone is always the SDP Offerer**; the **PC is always the SDP Answerer** (`a=setup:active`, DTLS client, ICE-controlled).
- **ICE Candidate Filtering (`pcAddress` Rule):** No STUN/TURN servers; UDP host candidates only; TCP candidates disabled. Offer **only** the local IPv4 address that the phone's `/readyfor/` control socket connected to (`pcAddress`), preventing the phone from wasting its connect timeout probing Docker, VPN, or virtual bridge interfaces.
- **SDP Answer Trimming:** The phone lists `H.265` before `H.264` in its offer (and offers RTX/VP8/VP9 on `svc 202`). Before generating the SDP answer, strip all non-`H.264` video payload types (unless H.265 hardware decode is explicitly enabled) and strip `urn:3gpp:video-orientation` so the phone rotates frames in hardware before encoding.

#### 2. Screen Mirroring, Mobile Desktop & Virtual Phone (`Services 1, 15, 16`)
- **Start Paths:**
  - PC-initiated: Send `svc 1 cmd 1` `{"ver":"1.0.0","cmd":1,"view":1,"reply":0,"drawerMode":false}` (`view = 1` mirror, `2` Ready For desktop, `3` virtual phone; `reply` **must be `0`**).
  - Phone-initiated (`Screen share` / `Mobile desktop` tiles): Phone sends `svc 1 cmd 0` (`{view, resolution?}`) with a 20 s timer; PC answers `svc 1 cmd 1` with `reply: 1` and echoed `view`.
- **Stream Setup (`svc 15`):**
  - Phone sends `svc 15 cmd 2` (`pkg: "mirror" | "desktop" | "mobile"`, `source`).
  - PC replies `svc 15 cmd 1` with `supportWebRTC: 1`, `supportCustomDisplay: false` (**must be JSON boolean `false`, never `0`**), `vrSupportUnfolded: false`, `width: 1920`, `height: 1080`, `phyWidth: 344`, `phyHeight: 194`, and a login-session-persistent `instanceId: Long` (`$XDG_RUNTIME_DIR/ec0/mirror.instance` — changing `instanceId` makes the phone drop all open streams!).
  - Phone sends `svc 15 cmd 3` (`sessionId` parsed as 64-bit `Long`, `webRTCData` JSON string offer) → PC replies `svc 15 cmd 4` (answer) + `cmd 20` ICE candidates → phone sends second `cmd 3` / `cmd 8 state:1` with `displayId`.
- **Watchdog Heartbeat:** PC sends `svc 15 cmd 9` every **30 seconds** (phone watchdogs at 35 s initially, 65 s thereafter; one `cmd 9` covers all active `svc 15` sessions).
- **Input (`svc 16`):** Send `cmd 1 REQUEST_CAP` (`{ver:1, displayId:D, caps:3}`) once per `displayId`, then forward `cmd 4` (`MOUSE_MOVE` absolute frame pixels), `cmd 5` (`MOUSE_BUTTON` `1` left, `2` middle, `3` right, `4` wheel), `cmd 3` (`KEYBOARD` PC/AT set-1 scancodes, `flags: 0x100` for E0 keys, `flags: 0x1000` for Android HOME `3` / BACK `4` / APP_SWITCH `187` / media keys), and `cmd 7` (`TEXT_COMMITTED`).

#### 3. Continuity Webcam (`Service 3`)
- **Dual Start Paths (`[OBSERVED]`):**
  - Phone Webcam tile tapped: Phone sends `svc 3 cmd 0` (`srcServiceId: 19`, `isReply: 0, heartbeat: 1, lite: 0|1`). PC replies `svc 3 cmd 0` with `{"isReply":1,"heartbeat":1,"port":6466}` (or `cmd 65535 {"code":12,"cmd":0}` if no virtual camera sink is available).
  - PC-initiated: PC sends `svc 3 cmd 0` with `{"heartbeat":1}` and **NO `isReply` key at all** (the phone distinguishes request vs reply by the presence of the `isReply` key!). Phone answers `cmd 0` (`isReply: 1`), then PC sends `cmd 1` (`{"port":6466}`).
- **WebRTC & Sink:** Phone sends `cmd 1` (with `data` as a nested JSON string containing `webRTCData` offer and `url = <phoneIp>`) → PC replies `cmd 17` (answer) + `cmd 18` ICE candidates (offering UDP candidates on the phone's `/24` subnet). Decodes 1280×720@30fps H.264 → fixed 1280×720 YUY2 → writes to `/sys/devices/virtual/video4linux/video*` (`v4l2loopback`) or PipeWire `Video/Source` node (`use-bufferpool=false`).
- **Heartbeat & Controls:** PC sends `svc 3 cmd 13` every **10 seconds** (phone's 30 s watchdog armed by `heartbeat: 1`). Supports `cmd 4` (front/back lens switch), `cmd 7` (torch toggle), `cmd 9` (zoom `0..8`), and parses `cmd 5` camera status (nested JSON string). Integrates directly with `ContinuityCameraCoordinator` alongside `CamDroid` (`Slice 8`) and `scrcpy` fallback.

#### 4. Per-App Android Streaming (`Services 15 & 17` + Android VDM API 34+)
- **App List & Icons (`svc 17`):** Parses `svc 17 cmd 1` (`apps`, `wpApps`, `appLimit: 5`, `port`, `fingerprint`, `token`), pulls the icon zip via `SmartConnectFexClient` (Slice `S-C`), caches `apps.json` + `~/.cache/ec0/app-icons/`, and receives `cmd 10` (`recentApps`).
- **Multi-Window Stream Host (`ec0-mirror-host`):**
  - Launches one window + WebRTC pipeline per streamed app (up to 5 concurrent virtual displays `AppStream:<pkg>`), queuing starts one at a time.
  - Handles `cmd 12` (merge into existing display when >5 apps or same app re-launched) + `cmd 14` (raise window), `cmd 13` (focus change → update window title/icon), `cmd 17`/`cmd 26` (debounced 400 ms in-place virtual display resize), `cmd 24` (visible window list for encoder prioritization), and `cmd 7` (`{sessionId, error:0}` on window close).
  - **Headless Notification Action Runner:** Subscribes to `service = -1` (`notification_action`). When `kind == "action"` arrives for `pkg`, answers the phone's `svc 15 cmd 2` invite within `6 s` using a headless `fakesink` pipeline, waits for `cmd 13` (which triggers the phone's trampoline activity to fire the `PendingIntent`), and closes the stream with `cmd 7` `2.5 s` later.
  - **A5x ColorOS 15 VDM Live Probe & Fallback:** On the phone side (`:android-app`), probes `context.getSystemService(VirtualDeviceManager::class.java)` on API 34/35; if OEM policy restricts `createVirtualDevice`, falls back to `DisplayManager.createVirtualDisplay` + `ActivityOptions.setLaunchDisplayId()` so app streaming works on both stock Moto and ColorOS.

#### 5. Cross Control ("Universal Control", `Service 26` + `Service 16 displayId: 0`)
- **Session & Liveness Gate:** Phone `cmd 0` (tile or sensor auto-resume) or PC edge push sends `svc 26 cmd 1` `{"ver":"1","cmd":1,"cid":1,"code":0,"trigger":1,"controller":true}` (always answering `cmd 0` with a fresh `cmd 1` so the phone's `cmd 2` reply carries `width`, `height`, `supportTouch`).
- **Critical Reconnect Bit (`cap["26"] = 9`):** Immediately upon `cmd 2` (`code == 1`), `capRegistry` sets `"26"` to `CAP_AVAILABLE or CAP_IN_USE` (`9`) in `svc 25 cmd 1` — the phone checks bit `8` within `1 s` of any reconnect and drops Cross Control if unset (`[OBSERVED]`).
- **Linux Edge Capture Backends:** Supports both Wayland `wlr-layer-shell` (1px edge strip + fullscreen transparent `pointer-constraints` / `relative-pointer` lock) and GNOME `xdg-desktop-portal` `InputCapture` (`libei` with persistent v2 restore token), integrating with `ec0`'s existing `SpatialMonitorCanvas` (`Slice 10`) and `UniversalControlService` (`Slice 9`). Coalesces relative motion into absolute `(x, y)` coordinates on `displayId: 0` at `<= 125 Hz`, sends `svc 26 cmd 4 {"show":true|false}` on edge enter/leave, rescales on `cmd 7` rotation, and releases all held keys/buttons before returning control (`12 px` outward push or `Ctrl+Alt+Esc`).

#### 6. Phone-as-Display (`Service 202` "R4SS" — Mirror & Extend PC Screen to Phone)
- **Signalling (`svc 202`):** PC sends `cmd 0` (`shareType: "mirror"|"extend"`, `setAudio`, `setScale: 100`, `key_pc_ss_version: "pc_ss_ver_2Dot5"`, `sessionId`). Phone replies `cmd 7` (`setOrientation`: `1` landscape, `2` portrait) and `cmd 5` (`sdpOffer`, `resolution` JSON string). If phone initiates via `cmd 50` while helper is disabled, PC immediately replies `cmd 26` (stop) so the phone doesn't hang.
- **Reverse WebRTC Media:** Phone offers `recvonly` video + `recvonly` Opus audio + SCTP `touchDataChannel`. PC sets sink pad caps before offer application (preventing `webrtcbin` `a=inactive` bug), trims video answer to single `H.264` payload type (`packetization-mode=1`, keyframe every 30 frames), and streams captured desktop (`xdg-desktop-portal` / PipeWire `ScreenCast` for `mirror`; `vkms` / Mutter `RecordVirtual` / KWin `zkde_screencast` / headless output for `extend`).
- **Binary Little-Endian Input over `touchDataChannel`:** Reads raw SCTP payload bytes (never string mode, because `type 0` Touch starts with `u32 0x00000000` NUL bytes!):
  - `type = 0` (`128 B` Touch): `x, y, resX, resY, flags, pointerId, timeMs, count<=7` → mapped to `x * outW / resX` via Wayland virtual-pointer / `LinuxInputInjectionBridge`.
  - `type = 1` (`32 B` Keyboard): Android keycode at offset `8`, flags at `16` (`0` down, `2` up, `32768` CapsLock) → translated to Linux evdev keycodes.
  - `type = 2` (`48 B` Mouse): `f32 dx, dy` relative motion, `flags` button/wheel mask, `f32 wheel`.
  - `type = 3` (`56 B` Pen): `x, y, resX, resY, flags, f32 pressure, tilt, orientation`.

---

### Slice `S-E` — Telephony SMS/MMS Relay (`svc 204/100`) & Instant Hotspot Auto-Join (`svc 13`)
**Goal:** Complete 100% feature parity with SMS conversation sync/send and 1-click Hotspot handoff.

1. **Services 100 & 204 — Messages / SMS Relay (`SmartConnectSmsService`):**
   - **Status (`svc 100 cmd 1018`, sent from `srcServiceId: 204`):** Flat JSON `{ver:"5.0", cmd:1018, granted, enabled, triggered, unreadCnt, unreadList}`.
   - **First-Time PC Enablement (`svc 204 cmd 18`):** All `svc 204` frames MUST carry `"ver": "5.0"` and **`"pId": 110`** (frames without `pId: 110` are ignored by the phone `[OBSERVED]`). When `enabled == false` and `triggered == false`, PC sends `cmd 18` ("PC messages ready") once, which adds this PC to the phone's enabled list and triggers a fresh `cmd 1018` with `enabled: true`.
   - **Sync, Threading & Send:** Queries `cmd 12` (own SIM numbers → `cmd 13`), `cmd 1` (contacts → `cmd 2`), and `cmd 28` (`{type:1}` → `cmd 29` in batches of 50 messages over the 10-day window). Groups flat messages into threads by normalized counterpart address (last 10 digits for phone numbers, lowercase for alphanumeric senders). Sends SMS via `cmd 4` (`messageType: 2, subId: -1, toAddress: [...], body: "...", medias: []` — **`medias: []` is mandatory** because the phone iterates it unconditionally `[OBSERVED]`), tracks `cmd 5` (accepted) / `cmd 6` (result), applies `cmd 7` (added/updated) / `cmd 14` (deleted) live pushes, and marks read via `cmd 8`.
2. **Service 13 — Instant Hotspot Auto-Join (`SmartConnectHotspotService`):**
   - When the user taps the phone's Hotspot tile (`svc 13 cmd 0`) or triggers Hotspot from `ec0` Desktop (`HotspotAndTelephonyService`), PC generates a fresh ephemeral `ssid = "SmartConnect-<4hex>"` and 16-char random alphanumeric `pwd`, and sends `svc 13 cmd 1`:
     `{"ver":"1.0.0","cmd":1,"authType":1,"ssid":ssid,"pwd":pwd,"token":"","wsPort":0,"fp":pcFpHex}`.
   - Phone starts its SoftAP with those exact credentials and sends `svc 13 cmd 2`.
   - PC joins via `nmcli dev wifi connect <ssid> password <pwd>` (retrying up to 5 times over 15 s as the scan populates, redacting `pwd` from all logs/stderr). On `cmd 4` / `cmd 6` stop or daemon shutdown, PC deletes the temporary `SmartConnect-XXXX` NetworkManager profile so normal Wi-Fi resumes cleanly.

---

## 4. `ec0-engine` Module Layout, Daemon+Helpers Pattern, Shared `:ui` Wiring & Platform Roles

### 4.1 Where Each Component Lives in `ec0-engine` (`:core`, `:ui`, `:desktop`, `:android-app`)

All new engine code lands inside our reserved namespace (`core/**/ec0/core/engine/**`), with clean seams into `:ui`, `:desktop`, and `:android-app`:

```text
core/src/commonMain/kotlin/ec0/core/engine/
├── smartconnect/
│   ├── SmartConnectConstants.kt          # Service IDs, version gates, cap-map bitmasks, baseline maps (S-A)
│   ├── SmartConnectEnvelope.kt           # Outer envelope + per-service payload codecs + JSON-string unwrapper (S-A)
│   ├── SmartConnectIdentity.kt           # realDeviceId, salted-hash nonceId codec, sn UUID guard, QR wsData (S-A)
│   └── protocol/
│       ├── SmartConnectHandshakeModels.kt # svc 0 Hello, VerifyReply, ExchangeKey, Disconnect, DeviceBean (S-A)
│       ├── SmartConnectStatusModels.kt    # svc 25 Status, CapMap, Wallpaper, DndState (S-A)
│       ├── SmartConnectClipNotifModels.kt # svc 9 NEGO/CONFIRM/UPDATE_NOTIFY/PULL; svc 5/37 NtfEvent/Reply; svc 22 (S-B)
│       ├── SmartConnectFileModels.kt      # svc 2 FileExSendData, fex frame parser; svc 168 StationData cmd 1001 (S-C)
│       ├── SmartConnectMediaModels.kt     # svc 1/15/16 Mirror/AppStream/Input; svc 3 Webcam; svc 26; svc 202 + SCTP input codec (S-D)
│       └── SmartConnectSmsHotspotModels.kt # svc 100 cmd 1018; svc 204 pId=110 SMS/Contacts; svc 13 Hotspot (S-E)

core/src/jvmMain/kotlin/ec0/core/engine/
├── transport/
│   ├── PinnedTlsWebSocketTransport.kt    # Shared trait (Smart Connect + O+ Synergy), LeafDerPinningTrustManager (S-A)
│   └── SealedDiscoveryFrameCodec.kt      # AES-256-GCM 17B-AAD binary discovery frame codec + golden vector (S-A)
├── smartconnect/
│   ├── SmartConnectNodeCoordinator.kt    # Session loop, Relay (fromPhone/toPhone/service -1), CapRegistry (S-A)
│   ├── SmartConnectDiscoveryAndBle.kt    # _readyformdns._tcp + /readyfor/mdns + BlueZ 0x5246 GATT wake (S-A)
│   ├── SmartConnectControlServices.kt    # Inline handlers: svc 9 Clipboard, svc 5/37 Notifications, svc 22 PC Lock (S-B)
│   ├── SmartConnectFexService.kt         # TLS-pinned fex HTTP/1.1 + /websocket pull/push + svc 17 icon cache (S-C)
│   ├── SmartConnectFileStationNfs.kt     # svc 168 cmd 1001 SOCKET_SSL mTLS NFSv3 client & 64B-FH server (S-C)
│   ├── SmartConnectHelperSupervisor.kt   # Daemon+Supervised-Helpers manager (flock, backoff, watch/send IPC) (S-D)
│   ├── SmartConnectWebRtcHelpers.kt      # Mirror/AppStream host, Webcam sink, CrossControl edge, Display sender (S-D)
│   └── SmartConnectSmsAndHotspot.kt      # svc 204/100 thread store & sender; svc 13 nmcli hotspot auto-join (S-E)
└── Ec0EngineNode.kt                      # Unified facade hosting both native ec0-engine and SmartConnectNodeCoordinator
```

### 4.2 Mirroring `smartconnect-linux`'s Daemon + Supervised Helpers Architecture in Kotlin

`smartconnect-linux`'s strongest architectural decision is keeping the control-plane daemon (`smartconnectd`) lightweight and resilient by isolating GStreamer/WebRTC pipelines and Wayland layer-shell/portal grabs into **supervised helper processes** that subscribe to raw service frames (`watch`) and emit replies (`send`) over a local control socket (`docs/re/vendor/smartconnect-linux/architecture.md`).

In `ec0-engine`, `SmartConnectNodeCoordinator` + `SmartConnectHelperSupervisor` replicate this exact pattern in Kotlin:

```text
                        ┌──────────────────────────────────────────────────────┐
                        │               Ec0EngineNode (Daemon)                 │
  Phone (A5x Stock      │  ┌────────────────────────────────────────────────┐  │
  Smart Connect OR  ◄───┼──┤ PinnedTlsWebSocketTransport (:52020)           │  │
  :android-app)         │  │  • /readyfor/ (TEXT JSON control spine)        │  │
                        │  │  • /readyfor/mdns (BINARY AES-256-GCM invites) │  │
                        │  └──────────────────────┬─────────────────────────┘  │
                        │                         ▼                            │
                        │  ┌────────────────────────────────────────────────┐  │
                        │  │ SmartConnectSessionRelay & CapRegistry         │  │
                        │  │  • Inline: svc 0, 25, 9 (clip), 5/37 (notif),  │  │
                        │  │    22 (lock), 13 (hotspot), 100/204 (SMS)      │  │
                        │  │  • Atomic svc 25 `cap` map merge (base + ovr)  │  │
                        │  │  • LocalDaemonIpcServer (`watch` / `send`)     │  │
                        │  └──────┬───────────────┬──────────────┬──────────┘  │
                        └─────────┼───────────────┼──────────────┼─────────────┘
                                  │               │              │
            ┌─────────────────────┘               │              └──────────────────────┐
            ▼                                     ▼                                     ▼
┌───────────────────────────┐       ┌───────────────────────────┐       ┌───────────────────────────┐
│ Mirror & AppStream Helper │       │ Webcam Helper             │       │ CrossControl / Display    │
│ • Always supervised after │       │ • Spawned on svc 3 cmd 0  │       │ • Spawned on svc 26 cmd 0 │
│   first verified session  │       │   (isReply:0) or PC start │       │   or svc 202 cmd 50       │
│ • Watches [1, 15, 17, -1] │       │ • Watches [3]             │       │ • Watches [26] / [202]    │
│ • Single instanceId file  │       │ • Feeds v4l2loopback or   │       │ • Holds flock in          │
│ • Runs headless action    │       │   PipeWire Video/Source   │       │   $XDG_RUNTIME_DIR/*.lock │
│   streams for svc -1      │       │ • Sends cmd 13 every 10s  │       │ • Updates cap["26"] = 9   │
└───────────────────────────┘       └───────────────────────────┘       └───────────────────────────┘
```

- **Why This Fits `ec0` Seamlessly:**
  - `Ec0EngineNode` already has `LocalDaemonIpcServer` (`Slice 11`, authenticated loopback IPC in `Slice11ContinuityServices.kt`) and `ExternalProcessLauncher` (`SystemProcessLauncher`).
  - Extending `LocalDaemonIpcServer` with the line-delimited JSON `watch` (`{"cmd":"watch","services":[1,15,17,-1]}`) and `send` (`{"cmd":"send","service":15,"data":{...}}`) verbs allows media helpers (whether JVM sub-processes or native GStreamer/Wayland bridges) to crash, restart with exponential backoff (`2s → 60s`), or be toggled without ever dropping the phone's `/readyfor/` TLS WebSocket session!
  - Furthermore, `SmartConnectCapRegistry` intercepts any `send` for `service = 25` containing a `cap` object and merges those keys into its override map before emitting the full `svc 25 cmd 1` map to the phone — guaranteeing a helper can never wipe out other tiles.

### 4.3 Shared `:ui` (`Capabilities` & `UiDevice`) Becoming Wire-True

In `ui/src/commonMain/kotlin/ec0/ui/models/UiBridge.kt`, `Capabilities` and `ProtocolFamily` (`LOCALSEND`, `SYNERGY`, `KDECONNECT`, plus `SMARTCONNECT` / `EC0_ENGINE` when lead's `:ui` enum is ready or mapped via `SYNERGY`) currently render static booleans. With `T16`, `Capabilities` becomes **100% wire-true** from the connected peer's live capability maps:

| `Capabilities` Field (`:ui`) | Wire-True Source in `SmartConnectNodeCoordinator` | Wire-True Source in `Ec0EngineNode` (Native) |
|---|---|---|
| `canReceive` | `true` when `LocalsendReceiverServer` / Ktor CIO receiver is up OR `svc 2` (`fex`) / `svc 168` (`FileStation`) is active | `true` when `Ec0Server` + `FileTransferService` is active |
| `clipboardSync` | `sessionVerified && peerServiceVersions["9"] >= 2 && (phoneCap["9"] ?: 0) and 16 == 0 && clipboardConfirmed` | `activeCaps.contains("clip")` |
| `notifications` | `sessionVerified && (phoneCap["5"] ?: 0) and 16 == 0 && dndState == 0` | `activeCaps.contains("notif")` |
| `photoSync` | `sessionVerified && ("168" in peerCaps || "2" in peerServiceVersions)` (File Station / `fex` photo pull) | `activeCaps.contains("file")` |
| `remoteInput` | `sessionVerified && ("15" in peerServiceVersions || "26" in peerServiceVersions)` | `activeCaps.contains("input") || activeCaps.contains("mirror")` |

### 4.4 Dual-Platform Role Split: `:desktop` vs `:android-app` (ColorOS-Resilient)

| Layer / Feature | `:desktop` (Linux JVM + Compose Desktop) | `:android-app` (Android 14+ KMP + Compose M3) |
|---|---|---|
| **Primary Role** | **Smart Connect PC Server (`os=6, type=4, scType=2`)**: hosts `:52020` TLS WS `/readyfor/` & `/readyfor/mdns`, shows pairing QR, pairs with **both** stock Motorola Smart Connect on Aryan's A5x and `:android-app`. | **Smart Connect Phone Peer (`os=1, type=1, scType=1`)**: scans PC QR (or accepts `/readyfor/mdns` / BLE `0x5246` wake), dials `wss://pc:wsPort/readyfor/` pinning `fp`. |
| **Background Survival (`coloros-resilience.md`)** | `systemd --user` daemon + system tray (`ContinuityTrayController`), 5 s resume/IP watcher, 30 s TCP `ECONNREFUSED` presence probe, BlueZ `0x5246` GATT Central wake. | **`Ec0ForegroundService` (`foregroundServiceType="connectedDevice|mediaProjection|camera|microphone"`)**, persistent `WifiManager.MulticastLock`, BLE `0x5246` 20B service-data advertiser + GATT Server (`0x0722`/`0x0726`) so PC can wake the phone even under ColorOS `HansManager` / `OplusQualityProtect`. |
| **Clipboard & Notifs (`S-B`)** | `wl-paste --watch` / `wl-copy` (`LinuxClipboardBridge`), `org.freedesktop.Notifications` D-Bus (`LinuxNotificationBridge`) + inline reply. | `ClipboardManager` (while foregrounded / IME active + overlay toast pull), `NotificationListenerService` (`svc 5/37` emitter + `RemoteInput` PendingIntent executor). |
| **Files (`S-C`)** | `SmartConnectFexClient` (pulls app icons & clips), `SmartConnectFileStationNfs` client (mounts phone `/sdcard` + UI browser) & `serve` (exports `~/PhoneShare` with 64B FHs). | `SmartConnectFexServer` (zips app icons / shared files on `50000..59999`), `SmartConnectFileStationNfs` server (exposes `/storage/emulated/0` over `SOCKET_SSL` mTLS). |
| **Media & Input (`S-D`)** | Supervised helpers (`mirror-host`, `webcam` → `v4l2loopback`/PipeWire, `crosscontrol` → Wayland layer-shell/InputCapture portal, `display` → ScreenCast/vkms). | WebRTC Offerer (`libwebrtc` / Android `MediaCodec` hardware H.264): `MediaProjection` / `VirtualDeviceManager` (API 34+ per-app virtual displays), `Camera2` 720p30 webcam, `AccessibilityService` / VDM input injector, `svc 202` full-screen decoder + `touchDataChannel` sender. |
| **SMS & Hotspot (`S-E`)** | Desktop Messages UI (`sms_threads`, `sms_send`, `sms_mark_read`) + `nmcli` Wi-Fi auto-join & cleanup. | `Telephony.Sms` / `ContactsContract` reader + `SmsManager` sender (`svc 204/100`), `WifiManager.startLocalOnlyHotspot` / tethering bridge (`svc 13`). |

---

## 5. Verification & Gate Plan for `S-A` … `S-E`

1. **Deterministic Unit Tests in Same Commit (`:core:jvmTest` & `:core:commonTest`):**
   - Every slice lands with its unit tests in the **exact same commit**:
     - `S-A`: `SmartConnectIdentityAndDiscoveryTest` (pins the 16-byte salted-hash `nonceId` vector `EBESExQVFhcdZK3SpjiDZw==`, the 54-byte `AES-256-GCM` `SealedDiscoveryFrame` golden vector `3c20b576a1c7f82af0...`, `sn` non-empty enforcement, QR `motorolardpconnection` roundtrip, `LeafDerPinningTrustManager` X.509 v1 acceptance + wrong-fp rejection, 6-step `svc 0` handshake + 2s unprompted `EXCHANGE_KEY`, and `SmartConnectCapRegistry` replace-whole merge).
     - `S-B`: `SmartConnectControlServicesTest` (`svc 9` `NEGO`→`CONFIRM`→`UPDATE_NOTIFY` + echo suppression + `msgId 13..17` firewall; `svc 5/37` 1 Hz coalescing + `person_icon` WhatsApp vs Dialer rule + quick reply + `svc -1` action routing; `svc 22` lock state machine).
     - `S-C`: `SmartConnectFexAndNfsTest` (loopback TLS-pinned `fex` `/websocket` + `/download` zip icon extraction + `svc 168 cmd 1001` `SOCKET_SSL` mTLS handshake + 64-byte NFSv3 file handle assertion + `cmd 2006 -> 2007` recovery).
     - `S-D`: `SmartConnectMediaSignallingTest` (SDP H.264 payload-type trimming, `svc 1/15/16` boolean serialization `supportCustomDisplay: false` & 64-bit `sessionId`, `svc 3` `isReply` key-absence invariant, `svc 26` `cap["26"]=9` reconnect bit, `svc 202` 128B/48B/32B/56B binary LE input packet parser).
     - `S-E`: `SmartConnectSmsAndHotspotTest` (`svc 100 cmd 1018` → `svc 204 cmd 18` `pId:110` auto-enable, 50-msg batch pagination, last-10-digit thread normalization, `cmd 4` mandatory `medias: []`, `svc 13` passphrase redaction & `nmcli` cleanup).
2. **Live Hardware Gate on Aryan's A5x (`CPH2733IN`):**
   - Immediately after `S-A` merges, scan `ec0` Desktop's Smart Connect QR from the stock Smart Connect app on the A5x → verify PC appears online in the phone's device list with App Streaming, Cross Control, Webcam, PC Lock, Hotspot, and Screen Share tiles rendered.
