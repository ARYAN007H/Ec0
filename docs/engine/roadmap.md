# `ec0-engine` — Architecture & Implementation Roadmap

**Workstream Owner:** `gemini-argon`  
**Status:** Slices 0–11 COMPLETE; **T16 Smart Connect Full-Parity Design (`S-A` … `S-E`) READY FOR LEAD REVIEW** (`docs/engine/smart-connect-design.md`)  
**License:** GPL-3.0-only (clean-room first-party specification & implementation; Smart Connect wire constants documented from MIT-licensed `docs/re/vendor/smartconnect-linux/` without code transplantation)  
**Reserved Namespace:**
- `core/src/commonMain/kotlin/ec0/core/engine/**`
- `core/src/jvmMain/kotlin/ec0/core/engine/**`
- `core/src/commonTest/kotlin/ec0/core/engine/**`
- `core/src/jvmTest/kotlin/ec0/core/engine/**`
- `docs/engine/**`

---

## 1. Executive Summary & Mission

`ec0-engine` is `ec0`'s self-sufficient, first-party cross-device engine for Android ↔ Desktop continuity.

While `ec0.core.synergy` speaks O+ Connect (`com.oplus.linker`) to interoperate with stock Oppo/OnePlus phones, T4 dynamic capture (`docs/re/t4-capture-plan.md`), static RE (`docs/re/synergy-protocol.md`), and T15/T16 Motorola Smart Connect (`com.motorola.mobiledesktop`) analysis (`docs/re/smart-connect-findings.md`, `docs/re/smart-connect-ec0-delta.md`, `docs/re/smart-connect-unlock.md`) exposed both the limitations of stock OEM stacks and a **shared transport convergence opportunity**:
1. **Cloud & OEM Coupling:** The official O+ PC daemon probes Oppo cloud (`218.67.91.82:443`) on boot and relies on `com.oplus.linker` system privileges (`pc_connect_support=1`), while Motorola Smart Connect artificially hides App Streaming (`appstream: "1"`) when paired with its own Linux client (`clientType: 5`, `osType: "linux"`).
2. **Weak Base-Path Authentication in O+ Synergy:** `WebSocketConnectManager:558` hardcodes `signature = 0x0FFFFFFF` (`268435455L`) and derives `noncestr = new Random(timestamp).nextLong()` (deterministic from wall-clock timestamp).
3. **Static APK-Bundled Certs in O+ Synergy:** Both peers trust static private keys embedded in APK assets (`server.cer` / `server_pad.cer` with fixed CNs `192.168.137.1` and `192.168.49.1`), meaning any device on the LAN can impersonate a peer.
4. **Protocol Gaps in Fallback Stacks:** LocalSend v2 lacks mid-chunk resumability and sender-side stream cancel enforcement prior to T13/T14; KDE Connect lacks unified streaming RPCs.
5. **T16 Strategic Convergence (Smart Connect + O+ Synergy + Native `ec0-engine`):** Both Motorola Smart Connect (`wss://<ip>:<wsPort>/readyfor/`, `_readyformdns._tcp.local.`, BLE `0x5246`) and O+ Connect share the same foundational transport pattern—**QR payload (`ips`, `wsPort`, `fp`/cert pin) → TLS 1.2/1.3 with SHA-256 certificate pinning + hostname verification bypass → RFC 6455 WebSocket TEXT JSON envelopes + AES-256-GCM sealed discovery frames**. Furthermore, Aryan's OPPO A5x (`CPH2733IN`) already runs stock Motorola Smart Connect (`09.0.4.005.0`), giving `ec0` an immediate live hardware test peer on day one of Slice `S-A`.

`ec0-engine` preserves the **proven architectural strengths** of both ecosystems—a lightweight JSON control-plane envelope over RFC 6455 WebSocket + multiplexed streaming bulk/media channels + deterministic port-walk fallback—while fixing every cryptographic, discovery, and reliability weakness using **zero external cloud dependencies** and **zero unauthorized Gradle edits**.

---

## 2. How `ec0-engine` Unifies & Improves on O+ Synergy and Motorola Smart Connect

| Dimension | O+ Synergy (`ec0.core.synergy`) | Motorola Smart Connect (`ec0.core.engine.smartconnect`) | Native `ec0-engine` (`ec0.core.engine`) |
|---|---|---|---|
| **Transport Trait** | Pinned-cert TLS (`server.cer`) + RFC 6455 WS over `[8080, 39888, 49888, 59888]` | Pinned-cert TLS (`fp` SHA-256 from QR) + RFC 6455 WS `wss://<ip>:<wsPort>/readyfor/` | Shared **`PinnedTlsWebSocketTransport`** hosting Smart Connect, O+ Synergy, and `Ec0Session` |
| **Identity & Pairing** | Static OEM cert in APK | Self-signed RSA-2048 X.509 cert (`CN=SmartConnect`), QR `fp` SHA-256 pin + `svc 0` `initConnect` deriving 32-byte `secretKey` (`HKDF-SHA256`, `info="readyfor-tls-export-v1"`) | Per-device **Ed25519** identity keypair + ephemeral **X25519 ECDH** → **HKDF-SHA256** session keys + **TOFU** peer key store |
| **Discovery & Wake** | Proprietary `pantaconnect` UDP probes | `_readyformdns._tcp.local.` mDNS + UDP `:53320` **AES-256-GCM sealed frames** (`aad = ver\|\|msgType\|\|deviceId15`) + BLE GATT wake (`0x5246` → `0x0722`) | `_ec0._tcp.local.` + `_readyformdns._tcp.local.` + `SealedDiscoveryFrameCodec` + SoftAP/P2P/USB-ADB Tier-0 fallback |
| **Control Envelope** | `signature = 0x0FFFFFFF`, `noncestr = Random(timestamp)` | `{id, type, service, data}` (`data` = stringified JSON inner payload, `type` in `0..3`) | **`Ec0Envelope`** (`ec0-sig-v1` Ed25519 signature + `±300s` / 64-seq `ReplayWindow`) + **`SmartConnectEnvelopeCodec`** |
| **Feature Gating** | `pc_connect_support=1` system prop | Peer **Capability Map (`svc 25`)** is the **sole gate** (`appstream: "1"` unlocked via `WIN_UNLOCKED` profile `clientType=1, osType="windows"`) | Wire-true `Capabilities` flags driven directly by peer `svc 25` / `Ec0Envelope` capabilities |
| **File Exchange** | gRPC `FileService` | **FileStation (`svc 168`)** embedded **NFSv3-over-mTLS** (`MOUNT` + `NFS` v3 over single TLS stream) + **`fex` (`svc 2/17`)** HTTPS `:9886` | **Chunked + Resumable** `.ec0part` transfer (`256 KiB` chunks, per-chunk SHA-256) + Smart Connect `NfsV3TlsServer` & `FexHttpServer` |
| **Media & Input** | Proprietary Mirage stream | **WebRTC (`svc 17`)** DTLS-SRTP H.264/Opus (`mirror` `1/15/16`, `webcam` `3`, `appstream` `15/17` + VDM, `extend` `202`) + **Cross Control (`svc 26`)** TCP protobuf | Daemon + supervised-helper architecture (`MirrorSessionCoordinator`, `WebcamSessionCoordinator`, `AppStreamCoordinator`, `CrossControlService`) |
| **OEM Survival** | `/product/priv-app` exemption | Stock APK foreground service | `ColorOsResilience` state machine, bounded offline outbox (`N=64`), `MulticastLock`, SoftAP/USB-C fallback |

---

## 3. Dependency & Build Constraint Analysis

Per `agentchat.md` rules, root `build.gradle.kts` and `gradle/libs.versions.toml` are owned by `agent-0` (all plugin declarations live strictly in root `build.gradle.kts`).
- **Slices `S-A`, `S-B`, `S-C`, and `S-E` require ZERO new Gradle dependencies:**
  - `TLSv1.2`/`TLSv1.3` with custom `X509ExtendedTrustManager` SHA-256 SPKI/cert pinning and hostname bypass (`SSLParameters.endpointIdentificationAlgorithm = ""`) is native in JDK 17 / Android API 34+ (`javax.net.ssl`).
  - `AES/GCM/NoPadding` (`256-bit` key, `96-bit` IV, `128-bit` tag, `17-byte` AAD), `Ed25519`, `X25519`, `ChaCha20-Poly1305`, `HmacSHA256`, and `SHA-256` ship natively in `java.security` / `javax.crypto`.
  - XDR (RFC 4506), ONC RPC v2 record marking (RFC 5531), MOUNT v3, and NFS v3 (RFC 1813) for FileStation (`svc 168`) are deterministic big-endian binary codecs implemented in pure Kotlin in `commonMain`/`jvmMain`.
  - Protobuf wire format for Cross Control (`svc 26`) uses `:core`'s existing `protobuf-java` runtime (already on classpath via `:synergy-proto`).
- **Slice `S-D` (WebRTC Media Plane):** Signaling (`svc 17` SDP/ICE JSON), `svc 1/15/16/3/202` orchestration, and `svc 26` Cross Control TCP protobuf require zero new Gradle dependencies; actual DTLS-SRTP H.264/Opus RTP media transport uses supervised external helpers (`gstreamer1.0` / `ffmpeg` / `v4l2loopback` on Linux desktop, or a lead-approved WebRTC KMP dependency requested via `agentchat.md` before `S-D` code).

---

## 4. Target Package & File Layout

All files live strictly inside our reserved paths:

```text
docs/engine/
├── roadmap.md                         # Master architecture & slice roadmap (Slices 0–11 + T16 S-A..S-E)
├── design.md                          # Slice 0: Native ec0-engine wire format, crypto spec, threat model
├── coloros-resilience.md              # Slice 7: Reconnect state machine, offline queue, Android service contract
├── system-integration-and-camdroid.md # Slice 8: CamDroid v1/v2 wire spec + Linux/Android bridges
└── smart-connect-design.md            # T16: Normative Motorola Smart Connect parity design (S-A..S-E)

core/src/commonMain/kotlin/ec0/core/engine/
├── EngineConstants.kt                 # Protocol version, service names, port walks
├── EngineIdentity.kt                  # DeviceId, PeerInfo, Capabilities, PairingQrPayload
├── CanonicalJson.kt                   # Deterministic RFC 8785 subset JSON canonicalizer
├── EnvelopeModel.kt                   # Ec0Envelope, Ec0Cmd, Ec0Info, ReplayWindow
├── HkdfSha256.kt                      # Pure-Kotlin RFC 5869 HKDF-SHA256 + rekey/bulk/readyfor derivation
├── MdnsCodec.kt                       # Pure-Kotlin RFC 6762/6763 DNS codec (_ec0._tcp, _camdroid._tcp, _readyformdns._tcp)
├── QrCodeMatrix.kt                    # Pure-Kotlin ISO/IEC 18004 QR Code Version 1–10 encoder
├── protocol/
│   ├── ClipboardModels.kt             # ClipboardPayload, dedupe hash, privacy flags
│   ├── FileTransferModels.kt          # FileOffer, FileAccept, FileChunkHeader, ResumeManifest, FileCancel
│   ├── InputModels.kt                 # KeyEventFrame, PointerEventFrame, TouchpadDeltaFrame
│   ├── NotificationModels.kt          # NotificationEvent (POST, DISMISS), NotificationReply
│   ├── PresenceModels.kt              # PresenceBeacon (batteryPct, charging, screenOn, capabilities)
│   ├── CameraModels.kt                # CamDroid v1/v2 codecs & 14 studio control commands
│   ├── UniversalControlModels.kt      # Screen-edge barrier & cursor handoff models
│   ├── HotspotAndTelephonyModels.kt   # Instant hotspot & call state/action models
│   ├── MediaControlModels.kt          # MPRIS2 <-> Android MediaSession models
│   └── HandoffAndProximityModels.kt   # App/URL handoff, 2FA OTP extractor, proximity lock, Continuity sketch
└── smartconnect/                      # [T16 S-A..S-E] Smart Connect wire models & codecs (commonMain)
    ├── SmartConnectConstants.kt       # Service IDs (0,1,2,3,5,9,13,15,16,17,22,25,26,37,100,168,202,204), ports, BLE UUIDs
    ├── SmartConnectEnvelope.kt        # {id, type, service, data} double-encoded JSON envelope codec
    ├── SmartConnectQrModels.kt        # Pairing QR JSON (ips, wsPort, fp, sn, net, ble, p2p, v, f) & BLE wake payload
    ├── SealedDiscoveryFrameCodec.kt   # UDP :53320 AES-256-GCM sealed frame header/AAD/payload codec
    ├── CapabilityMapModels.kt         # svc 25 CapabilityMap wire truth, ClientProfile (LINUX_HONEST vs WIN_UNLOCKED)
    ├── DeviceStatusModels.kt          # svc 1/25 battery, charging, thermal, storage, tiles, remote launch
    ├── SmartConnectClipboardModels.kt # svc 9 actions 0..2, 13..17
    ├── SmartConnectNotifModels.kt     # svc 5/37 notifications, inline reply, WebP icon framing, svc 22 PC lock
    ├── NfsV3XdrCodec.kt               # svc 168 RFC 5531 ONC RPC + MOUNT v3 + NFS v3 XDR codec
    ├── FexProtocolModels.kt           # svc 2/17 fex file notification & HTTP :9886 range-pull models
    ├── WebRtcSignalingModels.kt       # svc 17 SDP offer/answer/ICE, svc 1/15/16 mirror/appstream, svc 3 webcam, svc 202 extend
    ├── CrossControlProtoCodec.kt      # svc 26 TCP protobuf pointer/scroll/key/edge/drag-drop codec
    └── SmsAndHotspotModels.kt         # svc 204/100 SMS sync/send & svc 13 AutoHotspot credentials

core/src/jvmMain/kotlin/ec0/core/engine/
├── crypto/
│   ├── Ed25519Identity.kt             # Ed25519 keygen, SPKI/PKCS8 conversion, sign/verify
│   ├── X25519Handshake.kt             # Ephemeral X25519 ECDH + 4-message mutual auth transcript
│   ├── AeadFrameCodec.kt              # ChaCha20-Poly1305 AEAD framing + epoch key rotation (FRAME_KEY_UPDATE)
│   ├── PairingCoordinator.kt          # QR URI pairing, HMAC pairing_proof, TOFU PinStore
│   ├── EnvelopeSigner.kt              # Canonical JSON signing & ReplayWindow enforcement
│   └── PersistentEngineStore.kt       # EncryptedIdentityStore + PersistentPinStore (pinned_peers.json v2)
├── discovery/
│   ├── Ec0MdnsDiscovery.kt            # MulticastSocket 224.0.0.251:5353 (_ec0._tcp, _camdroid._tcp, _readyformdns._tcp)
│   └── EndpointResolver.kt            # Tier-0 USB ADB -> QR -> mDNS -> cached IP -> SoftAP/P2P gateway fallback
├── transport/
│   ├── PinnedTlsWebSocketTransport.kt # [T16 S-A] Shared cert-pinned TLS + RFC 6455 WS trait (Smart Connect & O+ Synergy)
│   ├── Ec0WsCodec.kt                  # Client + Server RFC 6455 HTTP upgrade & frame codec
│   ├── Ec0Session.kt                  # Authenticated session mux
│   ├── Ec0Client.kt                   # Outbound connector with exponential backoff + jitter
│   └── Ec0Server.kt                   # Inbound listener
├── services/
│   ├── ClipboardService.kt            # Native + Smart Connect svc 9 bidirectional clipboard sync
│   ├── FileTransferService.kt         # Native .ec0part resumable transfer + Smart Connect fex/NFS integration
│   ├── InputShareService.kt           # Native input share + Smart Connect svc 26 CrossControlService integration
│   ├── NotificationRelayService.kt    # Native + Smart Connect svc 5/37 notification relay & inline reply
│   ├── PresenceService.kt             # Native presence + Smart Connect svc 1/25 DeviceStatusService
│   ├── ScrcpyMirrorCoordinator.kt     # External scrcpy coordinator
│   ├── ContinuityCameraCoordinator.kt # CamDroid v1/v2 + Smart Connect svc 3 WebcamSessionCoordinator
│   ├── ContinuityExpansionServices.kt # Universal Control, Hotspot/Telephony, MPRIS2 MediaSession
│   └── Slice11ContinuityServices.kt   # USB ADB tunnel, PipeWire VirtualMic, Handoff, Proximity Lock, Loopback CLI IPC
├── smartconnect/                      # [T16 S-A..S-E] Smart Connect runtime services & helpers (jvmMain)
│   ├── SmartConnectPairingService.kt  # QR generator/scanner, svc 0 initConnect, HKDF secretKey export, UDP :53320 discovery
│   ├── SmartConnectCapabilityService.kt # svc 25 exchange, peer cap-map gate evaluation, UI Capabilities projection
│   ├── SmartConnectLockService.kt     # svc 22 PC lock -> Linux loginctl lock-session
│   ├── NfsV3TlsServer.kt              # svc 168 mTLS listener + user-space VirtualFsProvider + optional FUSE mount helper
│   ├── FexHttpServer.kt               # svc 2/17 HTTPS :9886 range-enabled file staging server & pull client
│   ├── MirrorSessionCoordinator.kt    # svc 1/15/16/17 RTP/WebRTC mirror session + supervised helper lifecycle
│   ├── WebcamSessionCoordinator.kt    # svc 3/17 WebRTC webcam sink -> /dev/video10 v4l2loopback
│   ├── AppStreamCoordinator.kt        # svc 15/17 per-app virtual display stream + Android VDM VirtualDisplay host
│   ├── CrossControlService.kt         # svc 26 TCP protobuf input server/client + screen-edge cursor transition
│   ├── VirtualDisplayExtendCoordinator.kt # svc 202 virtual monitor -> Wayland/X11 headless output + WebRTC sink
│   ├── SmsRelayService.kt             # svc 204/100 SMS thread sync, outbound send, MMS notice
│   └── SmartConnectHotspotService.kt  # svc 13 AutoHotspot request -> Linux nmcli Wi-Fi join
├── linux/
│   ├── LinuxSystemBridge.kt           # Wayland/X11 clipboard, D-Bus notifications, pkexec v4l2 setup, CLI commands
│   ├── LinuxDesktopIntegrationGenerator.kt # Dolphin/Nautilus AirDrop menus & systemd user service
│   └── LinuxInputInjectionBridge.kt   # Wayland ydotool & X11 xdotool discrete-argv input injection
├── android/
│   └── AndroidCompanionBridge.kt      # KMP foreground service runtime contract for :android-app
└── Ec0EngineNode.kt                   # Unified facade + wire-true Capabilities projection for :desktop and :android-app
```

---

## 5. Phased Implementation Slices

Every slice lands with **100% deterministic unit tests** (RFC/NIST test vectors verified via `python3` before commit), **same-commit production + test + UI updates**, **CI green on GitHub Actions**, and an updated **`agentchat.md`** log entry.

---

### Slices 0–7 — Core `ec0-engine` Foundation (`COMPLETE`)
- **Slice 0 (`docs/engine/design.md`):** Normative v1 wire specification, 4-message mutual auth transcript (`CLIENT_HELLO → SERVER_HELLO → CLIENT_AUTH → SERVER_AUTH_CONFIRM`), `CanonicalJson` (RFC 8785 subset), 8-line `ec0-sig-v1` Ed25519 signing preimage, and Golden Test Vectors.
- **Slice 1 (`ec0.core.engine.crypto`):** `Ed25519Identity`, `X25519Handshake`, `HkdfSha256`, `AeadFrameCodec`, `PairingCoordinator` (ephemeral-bound `pairing_proof`), `EnvelopeSigner`, and 64-entry `ReplayWindow`.
- **Slice 2 (`ec0.core.engine.discovery`):** Pure-Kotlin `MdnsCodec` (`_ec0._tcp.local.`), `Ec0MdnsDiscovery`, and multi-tier `EndpointResolver`.
- **Slice 3 (`ec0.core.engine.transport`):** Client + server RFC 6455 `Ec0WsCodec`, `Ec0Session`, `Ec0Client`, and `Ec0Server`.
- **Slice 4 (`ClipboardService`):** Bidirectional text/image sync (`<=64 KiB` inline, `>64 KiB` bulk stream), SHA-256 loopback echo suppression, and sensitive clip filtering.
- **Slice 5 (`FileTransferService`):** 256 KiB chunked resumable file/folder transfer (`.ec0part` + `.ec0part.meta`), per-chunk & whole-file SHA-256 verification, instant mid-stream cancel, and path traversal defense.
- **Slice 6 (`InputShareService`, `NotificationRelayService`, `PresenceService`, `ScrcpyMirrorCoordinator`):** Bidirectional input sharing, notification mirroring with inline reply, signed presence beacons, and `adb connect`-verified external `scrcpy` coordinator.
- **Slice 7 (`Ec0EngineNode`, `docs/engine/coloros-resilience.md`):** Reconnect state machine, bounded `OfflineOutboxQueue` (`N=64`), cross-session `OfflineFlushDeduplicator`, and unified node facade.

---

### Slices 8–11 — CamDroid Continuity Camera, Linux/Android System Bridges & Ecosystem Hardening (`COMPLETE`)
- **Slice 8 (`CameraModels`, `ContinuityCameraCoordinator`, `LinuxSystemBridge`, `AndroidCompanionBridge`):** Byte-exact `CamDroid` v1 (`[1B kind][4B len LE]`) and v2 (`"CMD2"` 36-byte LE header) codecs, 14 studio camera control commands, Wayland/X11/Freedesktop/AirDrop OS bridges, and Android companion runtime.
- **Slice 9 (`PersistentEngineStore`, `QrCodeMatrix`, `ContinuityExpansionServices`):** ChaCha20-Poly1305 encrypted identity/pin store (`0600` permissions), pure-Kotlin ISO/IEC 18004 QR generator, Screen-Edge Universal Control barrier, `_camdroid._tcp.local.` discovery, 2-lane bulk subkey, Instant Hotspot/Telephony handoff, and MPRIS2 media bridge.
- **Slice 10 (`LinuxInputInjectionBridge`, `ContinuityTheme`, `ContinuityDashboardPanel`):** Discrete-argv `ydotool`/`xdotool` input injection and Continuity Studio UI panels.
- **Slice 11 (`Slice11ContinuityServices`, `HandoffAndProximityModels`, `FRAME_KEY_UPDATE`):** Tier-0 USB-C `adb forward` tunnel, PipeWire `CamDroidMic` virtual microphone, `http`/`https`-validated URL handoff, 2FA OTP auto-extractor with 30s SHA-256-guarded clipboard scrub, proximity workstation lock, authenticated loopback CLI IPC, EMA throughput/ETA tracker, and 8-byte BE epoch-verified `FRAME_KEY_UPDATE` session re-keying (`openRekeyNotice`).

---

### Mission T16 — Motorola Smart Connect Full Parity (`Slices S-A … S-E`)
**Normative Specification:** `docs/engine/smart-connect-design.md`  
**Design Gate:** Design doc + roadmap update first (`STOP` for `agent-0` review before writing `S-A` implementation code).

---

#### Slice `S-A` — Shared `PinnedTlsWebSocketTransport`, Pairing/Discovery, `svc 25` Cap Map & Device Status (`First Live Pairing with Aryan's A5x`)
**Goal:** Establish the shared cert-pinned TLS WebSocket transport trait, QR/mDNS/BLE/sealed-UDP discovery, `svc 0` pairing handshake, `svc 25` capability map negotiation, and `svc 1/25` device telemetry—enabling **immediate live pairing between `:desktop` and Aryan's OPPO A5x running stock Motorola Smart Connect (`09.0.4.005.0`)**.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/{SmartConnectConstants,SmartConnectEnvelope,SmartConnectQrModels,SealedDiscoveryFrameCodec,CapabilityMapModels,DeviceStatusModels}.kt`
  - `jvmMain/.../transport/PinnedTlsWebSocketTransport.kt`
  - `jvmMain/.../smartconnect/{SmartConnectPairingService,SmartConnectCapabilityService}.kt`
  - Updates to `MdnsCodec.kt` (`_readyformdns._tcp.local.` PTR/SRV/TXT/A extraction) and `Ec0EngineNode.kt` (wire-true `Capabilities` projection into `:ui`).
- **Key Features:**
  - **`PinnedTlsWebSocketTransport`:** Shared across Smart Connect (`wss://<ip>:<wsPort>/readyfor/`, subprotocol `"readyfor"`) and O+ Synergy (`wss://<ip>:<port>/`). Enforces constant-time SHA-256 leaf certificate / SPKI pinning against the QR `fp` digest (`PinVerificationMode.EXACT_CERT_SHA256`), disables DNS/IP SAN hostname verification (`endpointIdentificationAlgorithm = ""`), and supports optional mTLS client cert presentation for `svc 168`.
  - **Discovery & Wake:**
    - QR pairing payload generator & parser (`ips`, `wsPort`, `fp`, `sn`, `net`, `ble`, `p2p`, `v`, `f`).
    - `_readyformdns._tcp.local.` mDNS discovery alongside `_ec0._tcp.local.`.
    - `SealedDiscoveryFrameCodec` on UDP `:53320`: `[version=0x01][msgType: 1B][deviceId15: 15B ASCII][iv: 12B][ciphertext + 16B GCM tag]` encrypted with the per-pair 32-byte `secretKey` (`AAD = header[0..17]`).
    - BLE GATT wake payload encoder (`0x5246` → characteristic `0x0722`, `[0x01, ip0, ip1, ip2, ip3, portHi, portLo]`).
  - **`svc 0` Handshake & Key Export:** Exchanges `initConnect` / `connectRsp` (`protocolVersion: 3`, `verCode: 1500000`), exports 32-byte per-pair `secretKey` via `HKDF-SHA256(ikm, salt = clientId || serverId, info = "readyfor-tls-export-v1", len = 32)`, and persists the peer in `PersistentPinStore` v2.
  - **`svc 25` Capability Map (Wire Truth) & Client Profile:** Parses peer capability map as the sole feature gate; supports `ClientProfile.LINUX_HONEST` (`clientType=5, osType="linux"`) and `ClientProfile.WIN_UNLOCKED` (`clientType=1, osType="windows"`, unlocking `appstream: "1"` on stock Moto APK) and projects wire-true `Capabilities` into `:ui`.
  - **`svc 1` & `svc 25` Device Status & Tiles:** Battery %, charging state, thermal level, storage bytes, quick-action tiles, and `launchModule`.
- **Verification Gate:**
  - Unit tests (`SmartConnectEnvelopeTest`, `SealedDiscoveryFrameCodecTest`, `PinnedTlsWebSocketTransportTest`, `CapabilityMapModelsTest`) green on CI.
  - **Live Hardware Milestone:** `ec0` desktop pairs with Aryan's A5x (`CPH2733IN`) running stock Smart Connect over hotspot LAN and renders live battery/storage/cap-map telemetry in the shared `:ui` Sync screen.

---

#### Slice `S-B` — Control-WS Services: Clipboard (`svc 9`), Notifications (`svc 5/37` + Inline Reply) & PC Lock (`svc 22`)
**Goal:** Deliver all zero-extra-socket Smart Connect productivity features over the established `PinnedTlsWebSocketTransport` control plane.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/{SmartConnectClipboardModels,SmartConnectNotifModels}.kt`
  - `jvmMain/.../smartconnect/SmartConnectLockService.kt`
  - Extensions to `ClipboardService.kt` and `NotificationRelayService.kt`.
- **Key Features:**
  - **Clipboard (`svc 9`):** Bidirectional sync for `action: 0` (push), `action: 1` (ack), `action: 2` (pull), plus extended actions `13..17` (rich HTML/URI/image clipboard metadata with graceful fallback), integrated with `ClipboardService`'s SHA-256 loopback echo deduplicator and 30s OTP auto-scrub.
  - **Notifications (`svc 5` & `svc 37`):** Full snapshot (`action: 0`), live post (`action: 1`), dismiss (`action: 2` / `action: 10`), inline direct reply (`action: 11` with `key`, `actionKey`, `replyText`), and `svc 37` binary WebP app-icon framing (`[4B keyLen BE][key UTF-8][WebP bytes]`) with bounded LRU caching (`128` icons).
  - **PC Lock (`svc 22`):** Handles `{"action": 1, "reason": "manual" | "proximity"}` by invoking `loginctl lock-session` on Linux desktop (with 5s cooldown rate limit) and sending `{"action": 2, "locked": true}` ack.
- **Verification Gate:**
  - Unit tests for `svc 9` loopback suppression, `svc 5/37` post/dismiss/reply + WebP icon framing, and `svc 22` lock dispatch.
  - Live copy/paste, notification toast + inline reply, and phone-triggered PC lock verified against Aryan's A5x.

---

#### Slice `S-C` — File Exchange: FileStation NFSv3-over-mTLS (`svc 168`) + `fex` HTTP (`svc 2` / `svc 17`)
**Goal:** Enable both push/pull file transfers (`fex`) and live zero-copy phone storage browsing (`FileStation` NFSv3-over-mTLS) across `:desktop` and `:android-app`.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/{NfsV3XdrCodec,FexProtocolModels}.kt`
  - `jvmMain/.../smartconnect/{NfsV3TlsServer,FexHttpServer}.kt`
- **Key Features:**
  - **FileStation (`svc 168`) Embedded NFSv3-over-mTLS:**
    - Pure-Kotlin RFC 4506 XDR + RFC 5531 ONC RPC v2 record-marking codec (`lastFragment = bit 31`).
    - Implements `MOUNT` (`100005` v3: `NULL`, `MNT`, `UMNT`, `EXPORT`) and `NFS` (`100003` v3: `GETATTR`, `SETATTR`, `LOOKUP`, `ACCESS`, `READ`, `WRITE`, `CREATE`, `MKDIR`, `REMOVE`, `RMDIR`, `RENAME`, `READDIR`, `READDIRPLUS`, `FSSTAT`, `FSINFO`, `PATHCONF`, `COMMIT`) over a single mTLS socket authenticated by the pair's pinned certificates.
    - Enforces canonical root sandboxing (`File.canonicalPath.startsWith(exportRoot.canonicalPath)`), rejecting `..` and symlink escapes with `NFS3ERR_ACCES (13)`.
    - Exposes a direct `VirtualFsProvider` to `:ui` (works out-of-the-box with zero root/FUSE privileges) plus an optional supervised `ec0-fuse-mount` helper for `~/.local/share/ec0/mounts/<peerId>/`.
  - **`fex` (`svc 2` & `svc 17`):**
    - HTTPS server on `:9886` (pinned pair cert) serving staged files via `/fex/<transferId>/<fileId>` with `Range: bytes=<start>-<end>` resumable support + HTTP pull client writing into `FileTransferService`'s sanitized download pipeline.
- **Verification Gate:**
  - Byte-exact XDR/RPC/MOUNTv3/NFSv3 roundtrip + path-traversal rejection tests and `fex` HTTP range-resume tests green on CI.
  - Live directory listing and file push/pull verified with Aryan's A5x.

---

#### Slice `S-D` — WebRTC & RTP Media Plane: Mirroring (`1/15/16`), Webcam (`3`), App Streaming (`15/17` + VDM), Cross Control (`26`) & Phone-as-Display (`202`)
**Goal:** Deliver low-latency hardware-accelerated screen mirroring, virtual webcam, per-app virtual display streaming, seamless mouse/keyboard cross-control, and desktop display extension using `smartconnect-linux`'s proven **daemon + supervised-helper** architecture adapted to Kotlin.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/{WebRtcSignalingModels,CrossControlProtoCodec}.kt`
  - `jvmMain/.../smartconnect/{MirrorSessionCoordinator,WebcamSessionCoordinator,AppStreamCoordinator,CrossControlService,VirtualDisplayExtendCoordinator}.kt`
- **Key Features:**
  - **Step `S-D.1` — Screen Mirroring (`svc 1`, `15`, `16`, `17`):** Supports both Direct RTP (`svc 16` H.264 Annex-B NAL framing + TCP touch/key return channel) and WebRTC (`svc 17` SDP offer/answer + host ICE candidates on LAN/SoftAP/USB) routed to a supervised media helper window.
  - **Step `S-D.2` — Webcam (`svc 3` + `svc 17`):** Phone camera (`front`/`back`, `720p`/`1080p`, `30`/`60` fps) streamed over WebRTC (`svc 17`, `sessionType = "webcam"`) into `/dev/video10` (`v4l2loopback`) via `ContinuityCameraCoordinator`.
  - **Step `S-D.3` — App Streaming (`svc 15` + `svc 17` + Android VDM):**
    - On `:desktop`: negotiates `WIN_UNLOCKED` profile (`appstream: "1"` in `svc 25`), syncs installed app list (`svc 15` `action: 0`) + WebP icons (`svc 37`), and launches individual Android apps in dedicated desktop windows (`action: 10`, `displayMode: "virtual"`).
    - On `:android-app` (Phone Host): creates an unprivileged VirtualDisplay (`MediaProjectionManager` + `DisplayManager.createVirtualDisplay("ec0-appstream-<pkg>", width, height, dpi, encoderSurface, VIRTUAL_DISPLAY_FLAG_PUBLIC or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)`), encodes via hardware `MediaCodec` (`video/avc`), and injects per-window touch/key events.
  - **Step `S-D.4` — Cross Control (`svc 26`):** Low-latency TCP protobuf stream (`PointerMove`, `PointerButton`, `Scroll`, `Key`, `EdgeTransition`, `DragDropOffer`, `Heartbeat`) paired with `UniversalControlService` screen-edge hit detection (`0..1000` permille entry coordinate) and `LinuxInputInjectionBridge` (`ydotool`/`xdotool`).
  - **Step `S-D.5` — Phone-as-Display (`svc 202`, Stretch):** Creates a virtual second monitor on Linux desktop (`swaymsg create_output` / `gnome-monitor-config` / `xrandr`) and streams it to `:android-app`'s full-screen `SurfaceView` decoder, routing phone multi-touch gestures back to desktop coordinates.
- **Verification Gate:**
  - Unit tests for `svc 17` SDP/ICE state machine, `svc 15` app catalog/launch, `svc 26` protobuf frame codec, and supervised-helper crash/restart isolation.
  - Live Mirroring, Webcam (`/dev/video10`), App Streaming, and Cross Control verified against Aryan's A5x.

---

#### Slice `S-E` — SMS Relay (`svc 204` / `svc 100`) & Hotspot Auto-Join (`svc 13`)
**Goal:** Complete the Smart Connect feature surface with desktop SMS conversation sync/send and 1-click Instant Hotspot auto-connect.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/SmsAndHotspotModels.kt`
  - `jvmMain/.../smartconnect/{SmsRelayService,SmartConnectHotspotService}.kt`
- **Key Features:**
  - **SMS Relay (`svc 204` & `svc 100`):** Thread list (`action: 0`), message history (`action: 1`), live incoming SMS push (`action: 2`), outbound SMS send (`action: 3` via `SmsManager.sendMultipartTextMessage` on `:android-app`), and read-receipt sync (`action: 4`).
  - **Hotspot Auto-Join (`svc 13`):** Requests phone Wi-Fi tether (`action: 1` → `action: 2` `{ssid, passphrase, bandGhz, gatewayIp}`) and joins automatically on Linux desktop via `LinuxSystemBridge` (`nmcli device wifi connect <ssid> password <passphrase>`).
- **Verification Gate:**
  - Unit tests for `svc 204/100` SMS pagination/send/delivery state machine and `svc 13` hotspot credential parsing + discrete-argv `nmcli` command construction.
  - Live SMS send/receive and Hotspot auto-join verified on Aryan's A5x.

---

## 6. Verification & Coordination Protocol

1. **Hub Discipline (`agentchat.md`):**
   - Log `CLAIMED` / `WORKING` / `DONE` at each slice boundary, maintaining the 25-entry cap in `agentchat.md`.
   - Never force-push (`git push --force` forbidden); never touch `main-2`, `.github/workflows/build.yml`, `android/`, `localsend-upstream/`, or other agents' files (`android-app/`, `desktop/`, `core/localsend/`).
2. **Design Gate First (`T16`):**
   - Stop after committing `docs/engine/smart-connect-design.md` and `docs/engine/roadmap.md`; wait for `agent-0` review before writing `S-A` implementation code.
3. **CI is the Compiler & Same-Commit Tests:**
   - Every code slice updates production code, unit tests, and any affected signatures in the **same commit**, pushes to `origin`, and verifies `.github/workflows/build.yml` finishes green (inspecting the uploaded `test-reports` artifact if any test fails, as Gradle console output truncates stack traces).
4. **Cryptographic & Wire Vector Verification Rule:**
   - Every test constant (SHA-256, HKDF-SHA256, AES-256-GCM sealed discovery frame, Ed25519, X25519, ChaCha20-Poly1305, RFC 6455 accept key, DNS wire bytes, XDR/NFSv3 frames) is computed/verified via `python3` before being pinned in `commonTest` / `jvmTest`.

---

## 7. Execution Sequence Summary

| Slice | Scope | Primary Artifacts | Gate |
|---|---|---|---|
| **Slices 0–7** | Core `ec0-engine` (Crypto, mDNS, WS+AEAD, Clipboard, Resumable Files, Input, Notifs, Presence, ColorOS Resilience) | `docs/engine/{design,coloros-resilience}.md`, `core/**/ec0/core/engine/**` | **COMPLETE** (CI green, Slice 1 crypto gate & rekey review cleared by `agent-0`) |
| **Slices 8–11** | CamDroid v1/v2, Linux/Android OS Bridges, QR, Persistent Store, Universal Control, USB-C Tunnel, VirtualMic, Handoff, OTP Scrub | `CameraModels`, `ContinuityCameraCoordinator`, `LinuxSystemBridge`, `PersistentEngineStore`, `Slice11ContinuityServices` | **COMPLETE** (CI green, all 5 runtime tests fixed & verified in `d5b82af` / `980aa05`) |
| **T16 Design** | Motorola Smart Connect Full-Parity Architecture & Slice Plan | `docs/engine/smart-connect-design.md`, `docs/engine/roadmap.md` | **READY FOR LEAD REVIEW** (STOP before `S-A` implementation) |
| **Slice `S-A`** | `PinnedTlsWebSocketTransport`, QR/mDNS/BLE/Sealed-UDP Discovery, `svc 0` Pairing, `svc 25` Cap Map & `svc 1/25` Status | `PinnedTlsWebSocketTransport`, `SealedDiscoveryFrameCodec`, `SmartConnectPairingService`, `SmartConnectCapabilityService` | CI green + **first live pairing with Aryan's A5x (`CPH2733IN`)** |
| **Slice `S-B`** | Control-WS Productivity: Clipboard (`svc 9`), Notifications (`svc 5/37` + Reply + Icons), PC Lock (`svc 22`) | `SmartConnectClipboardModels`, `SmartConnectNotifModels`, `SmartConnectLockService`, `ClipboardService`, `NotificationRelayService` | CI green + live clipboard, notification reply, and PC lock with A5x |
| **Slice `S-C`** | File Exchange: FileStation NFSv3-over-mTLS (`svc 168`) + `fex` HTTPS (`svc 2/17`) | `NfsV3XdrCodec`, `NfsV3TlsServer`, `FexProtocolModels`, `FexHttpServer` | CI green + path-traversal unit tests + live phone storage browse & `fex` transfer |
| **Slice `S-D`** | WebRTC Media & Input: Mirroring (`1/15/16`), Webcam (`3`), App Streaming (`15/17` + VDM), Cross Control (`26`), Display (`202`) | `WebRtcSignalingModels`, `MirrorSessionCoordinator`, `WebcamSessionCoordinator`, `AppStreamCoordinator`, `CrossControlService`, `VirtualDisplayExtendCoordinator` | CI green + live Mirroring, `/dev/video10` Webcam, VDM App Streaming & Cross Control |
| **Slice `S-E`** | SMS Relay (`svc 204/100`) & Hotspot Auto-Join (`svc 13`) | `SmsAndHotspotModels`, `SmsRelayService`, `SmartConnectHotspotService` | CI green + live SMS sync/send & 1-click Hotspot auto-join |

