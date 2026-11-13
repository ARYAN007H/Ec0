# `ec0-engine` — Architecture & Implementation Roadmap

**Workstream Owner:** `gemini-argon`  
**Status:** COMPLETE (Slices 0–7 implemented, unit-tested, and verified)  
**License:** GPL-3.0-only (clean-room first-party specification & implementation)  
**Reserved Namespace:**
- `core/src/commonMain/kotlin/ec0/core/engine/**`
- `core/src/jvmMain/kotlin/ec0/core/engine/**`
- `core/src/commonTest/kotlin/ec0/core/engine/**`
- `core/src/jvmTest/kotlin/ec0/core/engine/**`
- `docs/engine/**`

---

## 1. Executive Summary & Mission

`ec0-engine` is `ec0`'s self-sufficient, first-party cross-device engine for Android ↔ Desktop continuity.

While `ec0.core.synergy` speaks O+ Connect (`com.oplus.linker`) to interoperate with stock Oppo/OnePlus phones, T4 dynamic capture (`docs/re/t4-capture-plan.md`) and static RE (`docs/re/synergy-protocol.md`) exposed structural limitations in the OEM stack:
1. **Cloud & OEM Coupling:** The official PC daemon probes Oppo cloud (`218.67.91.82:443`) on boot and relies on `com.oplus.linker` system privileges (`pc_connect_support=1`).
2. **Weak Base-Path Authentication:** `WebSocketConnectManager:558` hardcodes `signature = 0x0FFFFFFF` (`268435455L`) and derives `noncestr = new Random(timestamp).nextLong()` (deterministic from wall-clock timestamp).
3. **Static APK-Bundled Certs:** Both peers trust static private keys embedded in APK assets (`server.cer` / `server_pad.cer` with fixed CNs `192.168.137.1` and `192.168.49.1`), meaning any device on the LAN can impersonate a peer.
4. **Protocol Gaps in Fallback Stacks:** LocalSend v2 lacks mid-chunk resumability and sender-side stream cancel enforcement prior to T13/T14; KDE Connect lacks Synergy's unified streaming RPC model.

`ec0-engine` preserves the **proven architectural strengths** of O+ Synergy—a lightweight JSON control-plane envelope over RFC 6455 WebSocket + multiplexed streaming bulk channels + deterministic port-walk fallback—while fixing every cryptographic, discovery, and reliability weakness using **zero external cloud dependencies** and **zero Gradle dependency changes** (all primitives—`Ed25519`, `X25519`, `ChaCha20-Poly1305`, `HmacSHA256`, `SHA-256`—ship natively in JDK 17 `java.security` / `javax.crypto`).

---

## 2. How `ec0-engine` Improves on O+ Synergy (Point-by-Point)

| Dimension | O+ Synergy (`ec0.core.synergy`) | `ec0-engine` (`ec0.core.engine`) |
|---|---|---|
| **Identity Keys** | Static OEM certs bundled in APK (`server.cer`, `server_pad.cer`) | Per-device **Ed25519** identity keypair generated on first boot; device ID = hex SHA-256 fingerprint of Ed25519 SPKI |
| **Pairing & Key Agreement** | Opaque cloud/QR flow; no per-peer TLS binding | **QR / URI Pairing** (`ec0://pair?...`) carrying `{devicePubKey, name, nonce, port}` → ephemeral **X25519 ECDH** → **HKDF-SHA256** session keys + **TOFU** peer key store |
| **Discovery** | Proprietary `pantaconnect` / undocumented UDP probes | Standard **mDNS (`_ec0._tcp.local.`)** on UDP `224.0.0.251:5353` with TXT records (`id`, `name`, `ver`, `caps`, `fp`) + cached endpoint + port-walk fallback |
| **Control Envelope** | `signature = 0x0FFFFFFF`, `noncestr = Random(timestamp)` | **`Ec0Envelope`**: preserves `msg_type`/`msg_body`/`src_role`/`category`/`timestamp` schema, adds monotonic `seq`, random 64-bit `nonce`, and real **Ed25519 signature** + `±300s` replay window |
| **Wire Confidentiality** | TLS pinned to static OEM cert | App-layer **ChaCha20-Poly1305** AEAD session framing (derived from X25519+HKDF handshake bound to both Ed25519 identities) over RFC 6455 WebSocket / TLS |
| **File Transfer** | gRPC `FileService` with undocumented `flag` / status codes | **Chunked + Resumable** transfer engine: 256 KiB chunks, per-chunk SHA-256 + full-file SHA-256, persistent chunk bitset for resume, folder relative paths, instant mid-stream cancel |
| **OEM Survival** | Relies on `/product/priv-app` system exemption | Single-service state machine, exponential backoff + full jitter, mDNS cache + SoftAP/P2P gateway fallback (`192.168.137.1`, `192.168.49.1`), bounded offline queue for clipboard & notifications |

---

## 3. Dependency & Build Constraint Analysis (Zero Gradle Edits Required)

Per `agentchat.md` rules, `build.gradle.kts` and `gradle/libs.versions.toml` are owned by `agent-0`.  
By targeting **JDK 17** (already configured via `jvmToolchain(17)` in `core/build.gradle.kts`), `ec0-engine` requires **zero new Gradle dependencies**:

- **Ed25519 (RFC 8032):** `KeyPairGenerator.getInstance("Ed25519")` & `Signature.getInstance("Ed25519")` (standard in JDK 15+, JEP 339).
- **X25519 ECDH (RFC 7748):** `KeyPairGenerator.getInstance("X25519")` & `KeyAgreement.getInstance("X25519")` (standard in JDK 11+, JEP 324).
- **HKDF-SHA256 (RFC 5869):** 40-line pure-Kotlin RFC 5869 extract-and-expand over `javax.crypto.Mac.getInstance("HmacSHA256")` (or `ec0.core.kdeconnect.Sha256` in `commonMain`), pinned against RFC 5869 Appendix A test vectors.
- **ChaCha20-Poly1305 (RFC 8439):** `Cipher.getInstance("ChaCha20-Poly1305")` (standard in JDK 11+, JEP 329).
- **mDNS (`_ec0._tcp.local.`):** Pure-Kotlin DNS wire-format codec (RFC 1035 / RFC 6762 / RFC 6763: SRV, TXT, A, AAAA, PTR records) in `commonMain` + `java.net.MulticastSocket` (`224.0.0.251:5353`) in `jvmMain`. Avoids pulling 200 KB of `javax.jmdns` while making every DNS packet byte-testable in `commonTest`.
- **WebSocket (RFC 6455):** Both client and server RFC 6455 handshake + frame codec in `ec0.core.engine.transport`, adapting the proven `WsFrames` design from `ec0.core.synergy.Rfc6455` and adding thread-safe write locking (`ReentrantLock`), bidirectional server upgrade acceptance, and `BINARY` frame support for chunked transfers and AEAD frames.

---

## 4. Target Package & File Layout

All files live strictly inside our reserved paths:

```text
docs/engine/
├── roadmap.md                         # Mirror of this roadmap for the docs preview server
├── design.md                          # Slice 0: Wire format, crypto spec, pairing flow, services, threat model
└── coloros-resilience.md              # Slice 7: Reconnect state machine, offline queue, Android service contract

core/src/commonMain/kotlin/ec0/core/engine/
├── EngineConstants.kt                 # Protocol version, service name "_ec0._tcp.local.", default port walk
├── EngineIdentity.kt                  # DeviceId, PeerInfo, Capabilities bitmask/set, PairingQrPayload
├── EnvelopeModel.kt                   # Ec0Envelope, Ec0Cmd, Ec0Info, ReplayWindow (pure Kotlin)
├── HkdfSha256.kt                      # Pure-Kotlin RFC 5869 HKDF-SHA256 (commonMain, uses pure SHA-256 HMAC)
├── MdnsCodec.kt                       # Pure-Kotlin RFC 6762/6763 DNS packet encoder/decoder (PTR/SRV/TXT/A/AAAA)
└── protocol/
    ├── ClipboardModels.kt             # ClipboardPayload (TEXT, IMAGE_PNG, IMAGE_JPEG), dedupe hash, privacy flags
    ├── FileTransferModels.kt          # FileOffer, FileAccept, FileChunkHeader, ResumeManifest, FileCancel
    ├── InputModels.kt                 # KeyEventFrame, PointerEventFrame, TouchpadDeltaFrame
    ├── NotificationModels.kt          # NotificationEvent (POST, DISMISS), NotificationReply
    └── PresenceModels.kt              # PresenceBeacon (batteryPct, charging, screenOn, capabilities)

core/src/jvmMain/kotlin/ec0/core/engine/
├── crypto/
│   ├── Ed25519Identity.kt             # Ed25519 keygen, raw 32-byte <-> SPKI/PKCS8 conversion, sign/verify
│   ├── X25519Handshake.kt             # Ephemeral X25519 ECDH + HKDF-SHA256 -> directional ChaCha20-Poly1305 keys
│   ├── AeadFrameCodec.kt              # ChaCha20-Poly1305 frame encryption/decryption with 64-bit counter nonce
│   ├── PairingCoordinator.kt          # QR payload generation, challenge-response verification, TOFU PinStore
│   └── EnvelopeSigner.kt              # Canonical JSON signing & verification + ReplayWindow enforcement
├── discovery/
│   ├── Ec0MdnsDiscovery.kt            # MulticastSocket 224.0.0.251:5353 announcer + listener + TTL cache
│   └── EndpointResolver.kt            # mDNS cache -> last-known IP -> SoftAP/P2P gateway + port-walk fallback
├── transport/
│   ├── Ec0WsCodec.kt                  # Client + Server RFC 6455 HTTP upgrade & frame codec (TEXT + BINARY)
│   ├── Ec0Session.kt                  # Authenticated session: X25519 handshake, AEAD channel, ping loop, mux
│   ├── Ec0Client.kt                   # Outbound connector (port walk + exponential backoff w/ full jitter)
│   └── Ec0Server.kt                   # Inbound listener for peer connections & pairing handshakes
├── services/
│   ├── ClipboardService.kt            # Bidirectional sync, SHA-256 loopback dedupe, size cap, private mode
│   ├── FileTransferService.kt         # Chunked + resumable sender/receiver, per-chunk SHA-256, mid-stream cancel
│   ├── InputShareService.kt           # Keyboard/mouse/touchpad event router + desktop/phone injector interface
│   ├── NotificationRelayService.kt    # Notification feed, quick-reply dispatcher, bounded offline queue
│   ├── PresenceService.kt             # Periodic signed battery/presence beacon broadcaster & tracker
│   └── ScrcpyMirrorCoordinator.kt     # Stretch: negotiates mirror session & launches external `scrcpy` binary
└── Ec0EngineNode.kt                   # Unified facade + DeviceSource-compatible adapter seam for desktop/android
```

---

## 5. Phased Implementation Slices

Every slice lands with **100% deterministic unit tests** (RFC/NIST test vectors verified via `python3` before commit), **CI green on GitHub Actions**, and an updated **`agentchat.md`** log entry.

---

### Slice 0 — Architecture & Wire Specification (`docs/engine/design.md`)
**Goal:** Lock the complete wire specification, cryptographic transcript, service schemas, and threat model before writing production Kotlin code.

- **Deliverables:**
  - `docs/engine/design.md`
  - Claim entry in `agentchat.md` (`gemini-argon — ec0-engine`)
- **Contents of `design.md`:**
  1. **Cryptographic Spec:**
     - Ed25519 raw 32-byte public/private key extraction vs. X.509 SPKI (`302a300506032b6570032100` prefix) and PKCS#8 (`302e020100300506032b657004220420` prefix) so keys serialize as clean 32-byte hex/base64url in QR codes while interoperating natively with `java.security`.
     - Pairing QR URI format: `ec0://pair?v=1&id=<deviceId>&pk=<base64url-ed25519-pub>&xpk=<base64url-x25519-eph>&name=<url-encoded>&nonce=<hex16>&port=<tcpPort>`.
     - Mutual authentication transcript: Initiator & Responder exchange ephemeral `X25519` public keys signed by their long-term `Ed25519` keys over the pairing nonce. Shared secret $Z = \text{X25519}(esk_I, epk_R)$. Session keys derived via $\text{HKDF-SHA256}(\text{salt} = \text{nonce}_I \parallel \text{nonce}_R, \text{IKM} = Z, \text{info} = \text{"ec0-v1-session"} \parallel pk_I \parallel pk_R)$ yielding $(K_{I\to R}, K_{R\to I}, \text{sessionId})$.
  2. **Envelope Wire Format (`Ec0Envelope`):**
     - Preserves Synergy's JSON ergonomics (`msg_type`: `"cmd"` | `"info"`, `msg_body`: object, `src_role`: `"ec0-engine"`, `category`: service name, `timestamp`: epoch ms) while replacing `noncestr`/`signature` longs with:
       - `seq`: monotonically increasing `Long` per session
       - `nonce`: 64-bit random `Long`
       - `sig`: Base64url `Ed25519` signature over the canonical signing preimage `ec0-sig-v1\n<msg_type>\n<src_role>\n<category>\n<timestamp>\n<seq>\n<nonce>\n<canonical_msg_body_json>`
  3. **Service Catalog & Multiplexing:**
     - Control frames (`TEXT` WebSocket opcode) carry signed `Ec0Envelope` JSON.
     - Bulk data frames (`BINARY` WebSocket opcode) carry `[streamId: 4B][chunkIndex: 4B][flags: 1B][sha256: 32B][payload: <=256KiB]` encrypted under the session's `ChaCha20-Poly1305` key.
  4. **Threat Model:** MITM on hostile LAN, replay attacks, rogue peer impersonation, directory traversal on file transfer (`../` sanitization), memory exhaustion (strict frame/chunk size caps), and TOFU key-change warnings.

---

### Slice 1 — Identity, Pairing & Envelope Crypto (`ec0.core.engine.crypto`)
**Goal:** Implement the cryptographic foundation, QR pairing state machine, TOFU trust store, and signed `Ec0Envelope` codec with replay protection.

- **Deliverables:**
  - `core/src/commonMain/kotlin/ec0/core/engine/EngineConstants.kt`
  - `core/src/commonMain/kotlin/ec0/core/engine/EngineIdentity.kt`
  - `core/src/commonMain/kotlin/ec0/core/engine/HkdfSha256.kt`
  - `core/src/commonMain/kotlin/ec0/core/engine/EnvelopeModel.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/crypto/Ed25519Identity.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/crypto/X25519Handshake.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/crypto/AeadFrameCodec.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/crypto/PairingCoordinator.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/crypto/EnvelopeSigner.kt`
- **Tests (`commonTest` + `jvmTest`):**
  - `HkdfSha256Test`: Pinned against **RFC 5869 Appendix A** (Test Cases 1, 2, and 3).
  - `Ed25519IdentityTest`: Pinned against **RFC 8032 §7.1** TEST 1 (empty message) & TEST 2 (`0x72` message) raw key import, SPKI/PKCS#8 wrapping, sign, and tamper-reject.
  - `X25519HandshakeTest`: Pinned against **RFC 7748 §6.1** scalar multiplication vector + full initiator/responder mutual handshake roundtrip + wrong-nonce/wrong-signature rejection.
  - `AeadFrameCodecTest`: Pinned against **RFC 8439 §2.8.2** ChaCha20-Poly1305 vector + counter-nonce rotation + ciphertext tamper rejection.
  - `EnvelopeSignerTest`: Canonical envelope sign/verify, `ReplayWindow` sliding 64-sequence bitmask + `±300_000 ms` clock-skew rejection, duplicate `seq` rejection.
  - `PairingCoordinatorTest`: QR URI encode/decode roundtrip, TOFU first-seen pin, subsequent match, and `KeyMismatchException` on changed peer public key.

---

### Slice 2 — mDNS Discovery & Endpoint Resolution (`ec0.core.engine.discovery`)
**Goal:** Discover peers via `_ec0._tcp.local.` mDNS with TXT records, maintain a live TTL cache, and resolve endpoints across LAN, SoftAP (`192.168.137.1`), and Wi-Fi Direct (`192.168.49.1`) with port-walk fallback.

- **Deliverables:**
  - `core/src/commonMain/kotlin/ec0/core/engine/MdnsCodec.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/discovery/Ec0MdnsDiscovery.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/discovery/EndpointResolver.kt`
- **Key Features:**
  - Pure-Kotlin DNS wire-format encoder/decoder supporting DNS label compression pointers (`0xC0`), PTR (`_ec0._tcp.local.`), SRV (port + target host), TXT (`id=...`, `name=...`, `ver=1`, `caps=...`, `fp=...`), A (IPv4), and AAAA (IPv6) records.
  - `Ec0MdnsDiscovery`: Binds `224.0.0.251:5353` (with graceful loopback/unicast test injection), sends burst queries/announces (`100ms, 500ms, 2000ms`), and tracks peer TTL expiry + goodbye packets (`TTL=0`).
  - `EndpointResolver`: Combines live mDNS discoveries, persisted last-known peer IPs, and SoftAP/Wi-Fi Direct gateway candidates with the engine port walk (`49880, 39888, 49888, 59888`).
- **Tests (`commonTest` + `jvmTest`):**
  - `MdnsCodecTest`: Byte-exact encoding/decoding of RFC 6762/6763 query and announce packets, label pointer decompression, malformed/cyclic pointer protection, TXT key-value extraction, and `TTL=0` goodbye handling.
  - `EndpointResolverTest`: Priority ordering (fresh mDNS > cached IP > gateway fallback) and port-walk sequence generation.

---

### Slice 3 — Authenticated WebSocket Transport (`ec0.core.engine.transport`)
**Goal:** Full client + server RFC 6455 WebSocket transport upgraded with our X25519 + Ed25519 post-upgrade handshake, session-encrypted framing, 5s heartbeat, and automatic reconnect with exponential backoff + jitter.

- **Deliverables:**
  - `core/src/jvmMain/kotlin/ec0/core/engine/transport/Ec0WsCodec.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/transport/Ec0Session.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/transport/Ec0Client.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/transport/Ec0Server.kt`
- **Key Features:**
  - `Ec0WsCodec`: Extends the proven RFC 6455 mechanics from `ec0.core.synergy.Rfc6455` to support **both** client (`encodeClientFrame` masked, `parseUpgradeResponse`) and server (`parseUpgradeRequest`, `buildUpgradeResponse`, unmasked server frames) roles, plus `BINARY` opcodes and `ReentrantLock`-protected frame writes.
  - `Ec0Session`: Runs the 2-message X25519+Ed25519 mutual authentication handshake immediately upon WebSocket open, derives directional `ChaCha20-Poly1305` keys, and multiplexes signed `Ec0Envelope` control messages alongside encrypted binary bulk chunks.
  - `Ec0Client` & `Ec0Server`: Loopback-testable over real local TCP sockets (`ServerSocket(0)`), with configurable backoff policy (`delay = random(0, min(maxDelayMs, baseDelayMs * 2^attempt))`).
- **Tests (`jvmTest`):**
  - `Ec0WsCodecTest`: RFC 6455 §1.3 `Sec-WebSocket-Accept` vector (`dGhlIHNhbXBsZSBub25jZQ==` → `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`), client→server masked and server→client unmasked framing across `<=125B`, `126..65535B`, and `>65535B` payloads, fragmented `CONTINUATION` reassembly, and `PING`/`PONG`/`CLOSE` echo.
  - `Ec0TransportIntegrationTest`: Real localhost `Ec0Server` ↔ `Ec0Client` connection, mutual Ed25519+X25519 authentication, encrypted envelope exchange, binary stream multiplexing, and rogue-peer rejection.

---

### Slice 4 — Clipboard Sync Service (`ec0.core.engine.services.ClipboardService`)
**Goal:** Bidirectional text and image clipboard sync with SHA-256 loopback deduplication, size caps, and per-device privacy controls.

- **Deliverables:**
  - `core/src/commonMain/kotlin/ec0/core/engine/protocol/ClipboardModels.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/services/ClipboardService.kt`
- **Key Features:**
  - Supports `text/plain`, `image/png`, and `image/jpeg`.
  - Small payloads (`<= 64 KiB`) ride inline in the encrypted `Ec0Envelope`; larger payloads (`> 64 KiB` up to configurable `maxSizeBytes`, default `16 MiB`) stream over an AEAD binary channel.
  - **Loopback Deduplication:** Tracks the `SHA-256` digest of the most recently applied incoming clipboard item so the local OS clipboard watcher does not echo it back to the sender.
  - **Private Mode:** Per-device `privateMode` flag + optional Android `EXTRA_IS_SENSITIVE` flag suppression so passwords copied from password managers are never broadcast.
- **Tests (`jvmTest`):**
  - `ClipboardServiceTest`: Bidirectional text & PNG sync over connected `Ec0Session` pair, loopback echo suppression (A→B does not trigger B→A re-send), oversize payload rejection, and private-mode filtering.

---

### Slice 5 — Chunked, Resumable File & Folder Transfer (`ec0.core.engine.services.FileTransferService`)
**Goal:** High-throughput, resumable file and folder transfer with per-chunk SHA-256 integrity and deterministic mid-stream cancellation (directly solving the LocalSend cancel gap and Synergy's opaque chunk flags).

- **Deliverables:**
  - `core/src/commonMain/kotlin/ec0/core/engine/protocol/FileTransferModels.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/services/FileTransferService.kt`
- **Key Features:**
  - **4-Phase Wire Flow:**
    1. `file_offer`: Batch metadata (`transferId`, list of `FileEntry(fileId, relativePath, sizeBytes, sha256, lastModifiedMs)`, `chunkSize = 262_144`).
    2. `file_accept`: Per-file acceptance + `resumeFromChunk: Map<String, Int>` read from the receiver's `.ec0part.json` manifest on disk.
    3. **Binary Chunk Stream:** Each chunk carries `(streamId, chunkIndex, sha256, data)`. Receiver verifies chunk `SHA-256` before writing at `offset = chunkIndex * chunkSize` via `RandomAccessFile` / `FileChannel` and persists the updated chunk index.
    4. `file_complete` / `file_cancel`: Final whole-file `SHA-256` verification before atomic rename `<name>.ec0part` → `<name>`. Either side can send `file_cancel(transferId, reason)` at any moment to abort immediately between chunks.
  - **Folder Support & Path Sanitization:** Preserves nested folder structure via normalized `relativePath`, strictly rejecting `..`, leading `/`, backslashes, or symlink escapes.
- **Tests (`jvmTest`):**
  - `FileTransferServiceTest`:
    - Multi-file & nested-folder transfer with NIST/hashlib-verified SHA-256 digests.
    - **Simulated Drop & Resume:** Abort transfer at chunk 5 of 12, reconnect, re-offer same `transferId`, verify only chunks `5..11` are transmitted and final file SHA-256 matches 100%.
    - **Mid-Stream Cancel:** Sender or receiver cancels mid-flight → state transitions to `CANCELLED` within 1 chunk, late chunks discarded, `FINISHED_STATES` guard prevents `CANCELLED → DONE` flip.
    - **Path Traversal Defense:** `../../etc/passwd` and `/tmp/evil` rejected at offer validation.

---

### Slice 6 — Input Share, Notifications Relay & Presence/Scrcpy (`ec0.core.engine.services`)
**Goal:** Complete the Apple-Continuity feature set: keyboard/mouse/touchpad sharing, Android notification mirroring with inline quick-reply, battery/presence beacons, and the stretch `scrcpy` screen-mirroring coordinator.

- **Deliverables:**
  - `core/src/commonMain/kotlin/ec0/core/engine/protocol/{InputModels,NotificationModels,PresenceModels}.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/services/InputShareService.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/services/NotificationRelayService.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/services/PresenceService.kt`
  - `core/src/jvmMain/kotlin/ec0/core/engine/services/ScrcpyMirrorCoordinator.kt`
- **Key Features:**
  - `InputShareService`: Streams `KeyEventFrame` (keyCode, modifiers, action, UTF-8 text commit) and `PointerEventFrame` (relative `dx/dy`, scroll `vx/vy`, button mask) in both directions (Desktop → Phone via pluggable `PhoneInputSink` supporting IME / `adb shell input` / HID, and Phone → Desktop touchpad mode via `DesktopInputSink`).
  - `NotificationRelayService`: Mirrors posted/dismissed notifications (`notificationKey`, `packageName`, `appName`, `title`, `text`, `timestamp`, `canReply`, `actions`) and routes desktop inline replies (`notificationKey`, `actionId`, `replyText`) back to the phone.
  - `PresenceService`: Broadcasts periodic signed `presence` info envelopes (`batteryPct`, `isCharging`, `screenOn`, `capabilities`).
  - `ScrcpyMirrorCoordinator` (Stretch): Negotiates mirroring intent over the authenticated control plane (`start_mirror` / `stop_mirror`) and spawns the external `scrcpy` binary (`--serial <ip:port> --window-title <peerName>`) via an injected `ProcessLauncher` (keeping `scrcpy` as an unmodified external binary per GPL-3.0 / `README.md` §License).
- **Tests (`jvmTest`):**
  - `InputShareServiceTest`: Key combination, UTF-8 text injection, and touchpad delta roundtrips with rate/sequence ordering assertions.
  - `NotificationRelayServiceTest`: Post → desktop feed update → quick reply → phone callback execution + dismiss propagation.
  - `PresenceAndMirrorTest`: Presence beacon cadence & signature validation; `ScrcpyMirrorCoordinator` command-line construction and lifecycle stop/cleanup with a fake `ProcessLauncher`.

---

### Slice 7 — ColorOS Hardening, Offline Queue & Desktop Seam Adapter (`Ec0EngineNode`)
**Goal:** Harden the engine against ColorOS background process killers (`OplusQualityProtect`, deep doze, Wi-Fi handoffs) and expose a clean, single-interface `Ec0EngineNode` seam ready for `desktop/` and `android-app/` consumption.

- **Deliverables:**
  - `core/src/jvmMain/kotlin/ec0/core/engine/Ec0EngineNode.kt`
  - `docs/engine/coloros-resilience.md`
- **Key Features:**
  - **Reconnect State Machine:** `DISCONNECTED → DISCOVERING → CONNECTING → AUTHENTICATING → CONNECTED → BACKOFF`, with full-jitter exponential backoff (`500ms .. 30_000ms`) and instant wake on network-interface change or mDNS announce receipt.
  - **Bounded Offline Outbox Queue:** Retains up to `N = 64` non-expired clipboard and notification frames while a paired peer is temporarily unreachable (e.g., phone screen-off / Wi-Fi roam) and flushes them in sequence order upon re-authentication.
  - **Desktop `DeviceSource` Adapter Seam:** `Ec0EngineNode` exposes discovered/paired peers, outgoing file send, incoming transfer events, clipboard state, notifications, and presence through a self-contained Kotlin Flow API that maps 1:1 onto `desktop/`'s `DeviceSource` and `TransferItem` models without modifying `desktop/` files during engine slices.
- **Tests (`jvmTest`):**
  - `Ec0EngineResilienceTest`: Simulated socket drop mid-session → automatic reconnect → offline queue flush without duplicate delivery; expired offline frames dropped cleanly.

---

## 6. Verification & Coordination Protocol

1. **Hub Discipline (`agentchat.md`):**
   - Log `CLAIMED` before Slice 0, `WORKING` / `DONE` at each slice boundary, maintaining the 25-entry cap in `agentchat.md`.
   - Never force-push (`git push --force` forbidden); never touch `main-2`, `.github/workflows/build.yml`, `android/`, `localsend-upstream/`, or other agents' worktrees.
2. **CI is the Compiler:**
   - Every slice is verified by pushing to `origin` and confirming `.github/workflows/build.yml` finishes green via `gh run list` / `gh run watch`.
3. **Cryptographic Vector Verification Rule:**
   - Every test constant (SHA-256, HKDF-SHA256, Ed25519, X25519, ChaCha20-Poly1305, RFC 6455 accept key, DNS wire bytes) is computed/verified via `python3` (`hashlib`, `hmac`, `cryptography`) before being pinned in `commonTest` / `jvmTest`—never written from memory.

---

## 7. Execution Sequence Summary

| Slice | Scope | Primary Artifacts | Gate |
|---|---|---|---|
| **Slice 0** | Wire & Crypto Design Spec | `docs/engine/design.md` + `agentchat.md` CLAIM | Spec completeness & threat model review |
| **Slice 1** | Identity, Pairing & Envelope Crypto | `EngineIdentity`, `HkdfSha256`, `Ed25519Identity`, `X25519Handshake`, `AeadFrameCodec`, `PairingCoordinator`, `EnvelopeSigner` | RFC 8032, RFC 7748, RFC 5869, RFC 8439 test vectors green in CI |
| **Slice 2** | mDNS (`_ec0._tcp.local.`) & Resolver | `MdnsCodec`, `Ec0MdnsDiscovery`, `EndpointResolver` | RFC 6762/6763 wire packet roundtrips + port-walk fallback tests green in CI |
| **Slice 3** | WebSocket + AEAD Session Transport | `Ec0WsCodec`, `Ec0Session`, `Ec0Client`, `Ec0Server` | Loopback client↔server mutual auth + encrypted framing tests green in CI |
| **Slice 4** | Clipboard Sync Service | `ClipboardModels`, `ClipboardService` | Bidirectional text/image sync + SHA-256 loopback dedupe tests green in CI |
| **Slice 5** | Resumable File & Folder Transfer | `FileTransferModels`, `FileTransferService` | Chunk resume, per-chunk SHA-256, mid-stream cancel, & path-traversal tests green in CI |
| **Slice 6** | Input, Notifications, Presence & `scrcpy` | `InputShareService`, `NotificationRelayService`, `PresenceService`, `ScrcpyMirrorCoordinator` | Service roundtrip + quick-reply + `scrcpy` launcher tests green in CI |
| **Slice 7** | Hardening, Offline Queue & Facade | `Ec0EngineNode`, `docs/engine/coloros-resilience.md` | Reconnect + offline outbox flush E2E tests green in CI |
| **Slice 8** | `CamDroid` Continuity Camera, Linux OS Bridges, AirDrop CLI & Android Companion Runtime | `CameraModels`, `ContinuityCameraCoordinator`, `LinuxSystemBridge`, `LinuxDesktopIntegrationGenerator`, `AndroidCompanionBridge`, `ContinuityHubController`, `ContinuityDashboardPanel`, `docs/engine/system-integration-and-camdroid.md` | CamDroid v1/v2 wire vectors + Linux/Android E2E tests green in CI |
