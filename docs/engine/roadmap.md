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
| **Transport Trait** | Pinned-cert TLS (`server.cer`) + RFC 6455 WS over `[8080, 39888, 49888, 59888]` | Pinned-cert TLS (`fp` leaf DER SHA-256 from QR/invite) + RFC 6455 WS `wss://<ip>:<wsPort>/readyfor/` (`>= 16 MiB` frames) | Shared **`PinnedTlsWebSocketTransport`** hosting Smart Connect, O+ Synergy, and `Ec0Session` |
| **Identity & Pairing** | Static OEM cert in APK | Self-signed RSA-2048 cert (`cert.der`), persistent `realDeviceId` (16B) + salted-hash `nonceId` (`salt8 \|\| SHA256(realId\|\|salt8)[0..8)`), non-empty UUID `sn`, 32B `perPairKey` exchanged in `svc 0 cmd 0/1` | Per-device **Ed25519** identity keypair + ephemeral **X25519 ECDH** → **HKDF-SHA256** session keys + **TOFU** peer key store |
| **Discovery & Wake** | Proprietary `pantaconnect` UDP probes | `_readyformdns._tcp.local.` mDNS + `/readyfor/mdns` **AES-256-GCM sealed binary WS frames** (`[16B nonceId][1B type=1][12B IV][ct\|\|16B tag]`, 17B AAD, `key=perPairKey`) + **BLE GATT wake (`0x5246` → `0x0722`)** | `_ec0._tcp.local.` + `_readyformdns._tcp.local.` + `SealedDiscoveryFrameCodec` + BLE `0x5246` + SoftAP/USB-ADB Tier-0 fallback |
| **Control Envelope** | `signature = 0x0FFFFFFF`, `noncestr = Random(timestamp)` | `{deviceId, srcServiceId, serviceId, msgId, data, ver:"3.0.0"}` (`TEXT` frames) | **`Ec0Envelope`** (`ec0-sig-v1` Ed25519 signature + `±300s` / 64-seq `ReplayWindow`) + **`SmartConnectEnvelope`** |
| **Feature Gating** | `pc_connect_support=1` system prop | Peer **Capability Map (`svc 0 serviceVersions` + `svc 25 cmd 1 cap`)** with `os=6, type=4, scType=2, verCode=903013000` is the **sole gate** (`BoardUtils.isMotoDevice()` gates only LenovoID/NearbyDisplays) | Wire-true `Capabilities` flags driven directly by peer `svc 25` / `Ec0Envelope` capabilities |
| **File Exchange** | gRPC `FileService` | **File Station (`svc 168 cmd 1001`)** tunnelled **NFSv3 over Mutual TLS (`dt2type=2` `SOCKET_SSL`, 64B file handles)** + **`fex` (`svc 2/17`)** TLS-pinned `/websocket` & `/download` | **Chunked + Resumable** `.ec0part` transfer (`256 KiB` chunks, per-chunk SHA-256) + `SmartConnectFileStationNfs` & `SmartConnectFexService` |
| **Media & Input** | Proprietary Mirage stream | **WebRTC (Phone Offerer / PC Answerer)**: Mirror (`svc 1/15/16`), Webcam (`svc 3`), App Stream (`svc 15/17` + Android VDM API 34+), Cross Control (`svc 26 + svc 16`), Phone-as-Display (`svc 202` + binary LE SCTP input) | Daemon + supervised-helper architecture (`SmartConnectHelperSupervisor` over `LocalDaemonIpcServer` `watch`/`send`) |
| **OEM Survival** | `/product/priv-app` exemption | Stock APK foreground service + BLE `0x5246` background wake | `ColorOsResilience` state machine, bounded offline outbox (`N=64`), `MulticastLock`, BLE `0x5246` GATT wake |

---

## 3. Dependency & Build Constraint Analysis

Per `agentchat.md` rules, root `build.gradle.kts` and `gradle/libs.versions.toml` are owned by `agent-0` (all plugin declarations live strictly in root `build.gradle.kts`).
- **Slices `S-A`, `S-B`, `S-C`, and `S-E` require ZERO new Gradle dependencies:**
  - `TLSv1.2`/`TLSv1.3` with custom `LeafDerPinningTrustManager` (`SHA-256(chain[0].encoded)`, accepting minimal X.509 v1 phone certificates and bypassing PKIX/SAN checks) is native in JDK 17 / Android API 34+ (`javax.net.ssl`).
  - `AES/GCM/NoPadding` (`256-bit` `perPairKey`, `96-bit` IV, `128-bit` tag, `17-byte` AAD), `RSA-2048`, `Ed25519`, `X25519`, `ChaCha20-Poly1305`, `HmacSHA256`, and `SHA-256` ship natively in `java.security` / `javax.crypto`.
  - XDR (RFC 4506), ONC RPC v2 record marking (RFC 5531), MOUNT v3, and NFS v3 (RFC 1813) with 64-byte file handles and Lenovo procedures `22`/`23`/`27` for File Station (`svc 168`) are deterministic big-endian binary codecs implemented in pure Kotlin in `commonMain`/`jvmMain`.
- **Slice `S-D` (WebRTC Media Plane & Supervised Helpers):** Control-plane signalling (`svc 1/3/15/16/17/26/202`), SDP H.264 answer trimming, and `svc 202` binary little-endian SCTP input parsing require zero new Gradle dependencies; media decoding/capture runs in supervised helper processes (`gstreamer1.0` / `v4l2loopback` / PipeWire on Linux desktop; standard Android `MediaCodec` + `VirtualDeviceManager` / `MediaProjection` on `:android-app`).

---

## 4. Target Package & File Layout

All files live strictly inside our reserved paths (matching `docs/engine/smart-connect-design.md` §4.1):

```text
docs/engine/
├── roadmap.md                            # Master architecture & slice roadmap (Slices 0–11 + T16 S-A..S-E)
├── design.md                             # Slice 0: Native ec0-engine wire format, crypto spec, threat model
├── coloros-resilience.md                 # Slice 7: Reconnect state machine, offline queue, Android service contract
├── system-integration-and-camdroid.md    # Slice 8: CamDroid v1/v2 wire spec + Linux/Android bridges
└── smart-connect-design.md               # T16: Normative Motorola Smart Connect parity design (S-A..S-E)

core/src/commonMain/kotlin/ec0/core/engine/
├── EngineConstants.kt                    # Protocol version, service names, port walks
├── EngineIdentity.kt                     # DeviceId, PeerInfo, Capabilities, PairingQrPayload
├── CanonicalJson.kt                      # Deterministic RFC 8785 subset JSON canonicalizer
├── EnvelopeModel.kt                      # Ec0Envelope, Ec0Cmd, Ec0Info, ReplayWindow
├── HkdfSha256.kt                         # Pure-Kotlin RFC 5869 HKDF-SHA256 + rekey/bulk subkey derivation
├── MdnsCodec.kt                          # Pure-Kotlin RFC 6762/6763 DNS codec (_ec0._tcp, _camdroid._tcp, _readyformdns._tcp)
├── QrCodeMatrix.kt                       # Pure-Kotlin ISO/IEC 18004 QR Code Version 1–10 encoder
├── protocol/
│   ├── ClipboardModels.kt                # ClipboardPayload, dedupe hash, privacy flags
│   ├── FileTransferModels.kt             # FileOffer, FileAccept, FileChunkHeader, ResumeManifest, FileCancel
│   ├── InputModels.kt                    # KeyEventFrame, PointerEventFrame, TouchpadDeltaFrame
│   ├── NotificationModels.kt             # NotificationEvent (POST, DISMISS), NotificationReply
│   ├── PresenceModels.kt                 # PresenceBeacon (batteryPct, charging, screenOn, capabilities)
│   ├── CameraModels.kt                   # CamDroid v1/v2 codecs & 14 studio control commands
│   ├── UniversalControlModels.kt         # Screen-edge barrier & cursor handoff models
│   ├── HotspotAndTelephonyModels.kt      # Instant hotspot & call state/action models
│   ├── MediaControlModels.kt             # MPRIS2 <-> Android MediaSession models
│   └── HandoffAndProximityModels.kt      # App/URL handoff, 2FA OTP extractor, proximity lock, Continuity sketch
└── smartconnect/                         # [T16 S-A..S-E] Smart Connect wire models & codecs (commonMain)
    ├── SmartConnectConstants.kt          # Service IDs, version gates, cap-map bitmasks, baseline maps (S-A)
    ├── SmartConnectEnvelope.kt           # Outer envelope + per-service payload codecs + JSON-string unwrapper (S-A)
    ├── SmartConnectIdentity.kt           # realDeviceId, salted-hash nonceId codec, sn UUID guard, QR wsData (S-A)
    └── protocol/
        ├── SmartConnectHandshakeModels.kt # svc 0 Hello, VerifyReply, ExchangeKey, Disconnect, DeviceBean (S-A)
        ├── SmartConnectStatusModels.kt    # svc 25 Status, CapMap, Wallpaper, DndState (S-A)
        ├── SmartConnectClipNotifModels.kt # svc 9 NEGO/CONFIRM/UPDATE_NOTIFY/PULL; svc 5/37 NtfEvent/Reply; svc 22 (S-B)
        ├── SmartConnectFileModels.kt      # svc 2 FileExSendData, fex frame parser; svc 168 StationData cmd 1001 (S-C)
        ├── SmartConnectMediaModels.kt     # svc 1/15/16 Mirror/AppStream/Input; svc 3 Webcam; svc 26; svc 202 + SCTP input codec (S-D)
        └── SmartConnectSmsHotspotModels.kt # svc 100 cmd 1018; svc 204 pId=110 SMS/Contacts; svc 13 Hotspot (S-E)

core/src/jvmMain/kotlin/ec0/core/engine/
├── crypto/
│   ├── Ed25519Identity.kt                # Ed25519 keygen, SPKI/PKCS8 conversion, sign/verify
│   ├── X25519Handshake.kt                # Ephemeral X25519 ECDH + 4-message mutual auth transcript
│   ├── AeadFrameCodec.kt                 # ChaCha20-Poly1305 AEAD framing + epoch key rotation (FRAME_KEY_UPDATE)
│   ├── PairingCoordinator.kt             # QR URI pairing, HMAC pairing_proof, TOFU PinStore
│   ├── EnvelopeSigner.kt                 # Canonical JSON signing & ReplayWindow enforcement
│   └── PersistentEngineStore.kt          # EncryptedIdentityStore + PersistentPinStore
├── discovery/
│   ├── Ec0MdnsDiscovery.kt               # MulticastSocket 224.0.0.251:5353 (_ec0._tcp, _camdroid._tcp, _readyformdns._tcp)
│   └── EndpointResolver.kt               # Tier-0 USB ADB -> QR -> mDNS -> cached IP -> SoftAP/P2P gateway fallback
├── transport/
│   ├── PinnedTlsWebSocketTransport.kt    # [T16 S-A] Shared trait (Smart Connect + O+ Synergy), LeafDerPinningTrustManager
│   ├── SealedDiscoveryFrameCodec.kt      # [T16 S-A] AES-256-GCM 17B-AAD binary discovery frame codec + golden vector
│   ├── Ec0WsCodec.kt                     # Client + Server RFC 6455 HTTP upgrade & frame codec
│   ├── Ec0Session.kt                     # Authenticated session mux
│   ├── Ec0Client.kt                      # Outbound connector with exponential backoff + jitter
│   └── Ec0Server.kt                      # Inbound listener
├── services/
│   ├── ClipboardService.kt               # Native + Smart Connect svc 9 bidirectional clipboard sync
│   ├── FileTransferService.kt            # Native .ec0part resumable transfer + Smart Connect fex/NFS integration
│   ├── InputShareService.kt              # Native input share + Smart Connect svc 16/26 integration
│   ├── NotificationRelayService.kt       # Native + Smart Connect svc 5/37 notification relay & inline reply
│   ├── PresenceService.kt                # Native presence + Smart Connect svc 25 status integration
│   ├── ScrcpyMirrorCoordinator.kt        # External scrcpy coordinator
│   ├── ContinuityCameraCoordinator.kt    # CamDroid v1/v2 + Smart Connect svc 3 webcam integration
│   ├── ContinuityExpansionServices.kt    # Universal Control, Hotspot/Telephony, MPRIS2 MediaSession
│   └── Slice11ContinuityServices.kt      # USB ADB tunnel, PipeWire VirtualMic, Handoff, Proximity Lock, Loopback CLI IPC
├── smartconnect/                         # [T16 S-A..S-E] Smart Connect runtime coordinators & helpers (jvmMain)
│   ├── SmartConnectNodeCoordinator.kt    # Session loop, Relay (fromPhone/toPhone/service -1), CapRegistry (S-A)
│   ├── SmartConnectDiscoveryAndBle.kt    # _readyformdns._tcp + /readyfor/mdns + BlueZ 0x5246 GATT wake (S-A)
│   ├── SmartConnectControlServices.kt    # Inline handlers: svc 9 Clipboard, svc 5/37 Notifications, svc 22 PC Lock (S-B)
│   ├── SmartConnectFexService.kt         # TLS-pinned fex HTTP/1.1 + /websocket pull/push + svc 17 icon cache (S-C)
│   ├── SmartConnectFileStationNfs.kt     # svc 168 cmd 1001 SOCKET_SSL mTLS NFSv3 client & 64B-FH server (S-C)
│   ├── SmartConnectHelperSupervisor.kt   # Daemon+Supervised-Helpers manager (flock, backoff, watch/send IPC) (S-D)
│   ├── SmartConnectWebRtcHelpers.kt      # Mirror/AppStream host, Webcam sink, CrossControl edge, Display sender (S-D)
│   └── SmartConnectSmsAndHotspot.kt      # svc 204/100 thread store & sender; svc 13 nmcli hotspot auto-join (S-E)
├── linux/
│   ├── LinuxSystemBridge.kt              # Wayland/X11 clipboard, D-Bus notifications, pkexec v4l2 setup, CLI commands
│   ├── LinuxDesktopIntegrationGenerator.kt # Dolphin/Nautilus AirDrop menus & systemd user service
│   └── LinuxInputInjectionBridge.kt      # Wayland ydotool & X11 xdotool discrete-argv input injection
├── android/
│   └── AndroidCompanionBridge.kt         # KMP foreground service runtime contract for :android-app
└── Ec0EngineNode.kt                      # Unified facade + wire-true Capabilities projection for :desktop and :android-app
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

#### Slice `S-A` — Shared `PinnedTlsWebSocketTransport`, Pairing/Discovery, `svc 0` Handshake & `svc 25` Status/Cap-Map (`First Live Pairing with Aryan's A5x`)
**Goal:** Establish the shared cert-pinned TLS WebSocket transport trait, QR/mDNS/BLE/sealed-WS discovery, 6-step `svc 0` pairing handshake, `svc 25` capability map registry, and device status telemetry—enabling **immediate live pairing between `:desktop` and Aryan's OPPO A5x (`CPH2733IN`) running stock Motorola Smart Connect (`09.0.4.005.0`)**.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/{SmartConnectConstants,SmartConnectEnvelope,SmartConnectIdentity}.kt`
  - `commonMain/.../smartconnect/protocol/{SmartConnectHandshakeModels,SmartConnectStatusModels}.kt`
  - `jvmMain/.../transport/{PinnedTlsWebSocketTransport,SealedDiscoveryFrameCodec}.kt`
  - `jvmMain/.../smartconnect/{SmartConnectNodeCoordinator,SmartConnectDiscoveryAndBle}.kt`
  - Updates to `MdnsCodec.kt` (`_readyformdns._tcp.local.` PTR/SRV/TXT/A extraction) and `Ec0EngineNode.kt` (wire-true `Capabilities` projection into `:ui`).
- **Key Features:**
  - **`PinnedTlsWebSocketTransport` & `LeafDerPinningTrustManager`:** Shared across Smart Connect (`/readyfor/`, `/readyfor/mdns`, `maxFrameBytes = 20 MiB`) and O+ Synergy (`/`). Pins `SHA-256(leafCert.encoded)` via constant-time `MessageDigest.isEqual` while bypassing PKIX v3 / SAN validation so minimal X.509 v1 phone certs work seamlessly.
  - **Identity & Privacy-Preserving `nonceId`:** Separates persistent 16-byte `realDeviceId` (`paired.json` key) from rotating 16-byte `nonceId = salt8 || SHA-256(realDeviceId || salt8)[0..8)` (`matchNonceIdToPairedPeer()`), and enforces a stable non-empty UUID `sn` so the phone's SQLite pairing table never hits an empty-`sn` primary-key collision.
  - **`SealedDiscoveryFrameCodec` (`/readyfor/mdns` & BLE `0x0722`):** Encodes/decodes `[16B senderNonceId][1B frameType = 0x01][12B IV][AES-256-GCM ct || 16B tag]` with `17`-byte AAD (`bytes[0..17)`) under the 32-byte `perPairKey` (no KDF; strictly never emits `0x02` or `0x03`, which cause the phone to delete its pairing).
  - **6-Step `svc 0` Handshake & `SmartConnectCapRegistry` (`svc 25`):**
    - Generates `motorolardpconnection{"wsData":{cmd:1,...},"version":"3.0.0"}` QR code.
    - Answers phone Hello (`svc 0 cmd 0/3`) with PC Verify Reply (`os=6, type=4, scType=2, verCode=903013000, verName="09.0.3.013.000"`, `key=perPairKeyB64`, `serviceVersions` without `rfaServiceVersions`), immediately emits `svc 25 cmd 1` tile `cap` map (`SmartConnectCapRegistry` replace-whole semantics), completes `svc 0 cmd 1` `EXCHANGE_KEY` with both `deviceId` and `realDeviceId` set to `<pcRealId>` (+ 2s unprompted fallback timer), and runs the 5s post-pair discovery refresh.
  - **Discovery & BLE `0x5246` Wake:** Combines `_readyformdns._tcp.local.` mDNS, 30s TCP `ECONNREFUSED` background-presence probing, and BlueZ `0x5246` 20-byte `ServiceData` GATT Central wake (`0x0722` write → phone dials back in `~0.3 s`).
- **Verification Gate:**
  - `SmartConnectIdentityAndDiscoveryTest` (pins salted-hash `nonceId` vector `EBESExQVFhcdZK3SpjiDZw==`, 54-byte `AES-256-GCM` `SealedDiscoveryFrame` golden vector `3c20b576a1c7f82af0...`, `sn` guard, 6-step `svc 0` handshake, and `SmartConnectCapRegistry` merge) green on CI.
  - **Live Hardware Milestone:** `ec0` Desktop pairs with Aryan's A5x (`CPH2733IN`) running stock Smart Connect and lights up all feature tiles on the phone's device page.

---

#### Slice `S-B` — Control-WS Services: Clipboard (`svc 9`), Notifications (`svc 5/37` + Quick Reply + Action Routing) & PC Lock (`svc 22`)
**Goal:** Deliver all zero-extra-socket Smart Connect productivity features over the established `/readyfor/` control WebSocket.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/protocol/SmartConnectClipNotifModels.kt`
  - `jvmMain/.../smartconnect/SmartConnectControlServices.kt`
- **Key Features:**
  - **Clipboard (`svc 9`):** Dispatches on `data.msgId`: `1` (`NEGO`) → `2` (`CONFIRM` within `50 ms`), `3` (`UPDATE_NOTIFY` text sync with SHA-256 loopback echo suppression and sensitive-clip filtering), `4` (`PULL` for file/image clips via `S-C`), and enforces a strict outbound firewall blocking `msgId 13..17` and `101`.
  - **Notifications (`svc 5` & `svc 37`):** Handles `event = 0/1/2` across styles `0..6`, coalesces updates at `1 Hz` by `(key, visibleTextHash)`, disambiguates `person_icon` vs `apk_icon` (`person_name == title` check to avoid WhatsApp owner-photo bug), sends inline quick replies (`cmd: 0, event: 0`), and emits local `service = -1` (`notification_action`) events prior to `cmd: 0, event: 1/2` so `S-D` catches the phone's resulting app-stream invite.
  - **PC Lock (`svc 22`):** Answers `cmd 1` by executing the lock cascade (`loginctl lock-session`), polling `LockedHint`, and reporting `cmd 2` (`state: 1` locked → `state: 0` unlocked).
- **Verification Gate:**
  - `SmartConnectControlServicesTest` green on CI + live clipboard sync, notification quick reply, and phone-triggered PC lock verified against Aryan's A5x.

---

#### Slice `S-C` — File Exchange: `fex` (`svc 2` & `svc 17` App Icons) + File Station NFSv3 over Mutual TLS (`svc 168`)
**Goal:** Enable app-icon harvesting (`svc 17`, unlocking `recentApps`), clipboard image/file pulls (`svc 9` + `svc 2`), and bidirectional zero-copy file browsing (`svc 168` NFSv3 over mutual TLS).

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/protocol/SmartConnectFileModels.kt`
  - `jvmMain/.../smartconnect/{SmartConnectFexService,SmartConnectFileStationNfs}.kt`
- **Key Features:**
  - **`fex` Client & Server (`svc 2` & `svc 17`):** Connects TLS 1.3 (`ALPN http/1.1` only) pinned to lowercase hex `SHA-256(DER) == fingerprint`, runs `/websocket` (`Server1001` version negotiation → `Server1002` `sendRequest`), downloads zip/files via `/download` or `/downloadFile` into `~/.cache/ec0/app-icons/` (sanitized, atomic rename), and sends `Client1001:status` (`type: 1`) so the phone pushes `svc 17 cmd 10` (`recentApps`).
  - **File Station Tunnelled NFSv3 over mTLS (`svc 168 cmd 1001`):**
    - Negotiates `dt2type = 2` (`SOCKET_SSL` mutual TLS over TCP, avoiding proprietary `dt2socket` QUIC `dt2type: 3`) using a dedicated `files-identity` X.509 cert (`0600`).
    - **Browse Phone Storage (`type: 0 -> 1`):** Connects directly to `ip:nfsport`, runs RFC 1813 `MOUNT` v3 `MNT("/sdcard")` + `NFS` v3 (`GETATTR`, `LOOKUP`, `READDIRPLUS`, `READ`) to power `:ui` file browsing and optional `~/Phone` FUSE mount.
    - **Share PC Folder (`serve`, `"206": 1, "20": 1`):** Exports `~/PhoneShare` over single-port mTLS (`port == nfsport`) with mandatory **64-byte file handles**, `cmd 2006 -> 2007` recovery, and custom procedures `22` (`READTHUMBNAIL`), `23` (`READCATEGORYDIR`), and `27` (`REMOVEBATCH`).
- **Verification Gate:**
  - `SmartConnectFexAndNfsTest` green on CI + live app-icon pull (`recentApps` populated), phone `/sdcard` browse, and PC folder share verified with Aryan's A5x.

---

#### Slice `S-D` — WebRTC Media Plane & Supervised Helpers: Mirroring (`1/15/16`), Webcam (`3`), App Streaming (`15/17` + VDM), Cross Control (`26`) & Phone-as-Display (`202`)
**Goal:** Deliver wireless, ADB-free Screen Mirroring, Ready For Desktop, Per-App Streaming Windows, Continuity Webcam, Cross Control, and Phone-as-Display using `smartconnect-linux`'s **daemon + supervised-helpers** pattern (`SmartConnectHelperSupervisor` over `LocalDaemonIpcServer` `watch`/`send`).

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/protocol/SmartConnectMediaModels.kt`
  - `jvmMain/.../smartconnect/{SmartConnectHelperSupervisor,SmartConnectWebRtcHelpers}.kt`
- **Key Features:**
  - **Shared WebRTC Answerer Rules:** Phone is always SDP Offerer; PC is SDP Answerer offering host UDP candidates on `pcAddress` only, stripping non-`H.264` payload types and `urn:3gpp:video-orientation`.
  - **Screen Mirroring, Mobile Desktop & Virtual Phone (`svc 1, 15, 16`):** Handles PC- and phone-initiated views (`1` mirror, `2` desktop, `3` virtual phone), `svc 15 cmd 1` (`supportCustomDisplay: false` boolean, persistent `instanceId`), `cmd 9` 30s watchdog heartbeat, and `svc 16` mouse/keyboard/text input.
  - **Continuity Webcam (`svc 3`):** Enforces the `isReply` key-absence invariant on PC-initiated `cmd 0`, `cmd 13` 10s heartbeat, lens/torch/zoom controls (`cmd 4/7/9`), and outputs 1280×720 YUY2 to `v4l2loopback` or PipeWire `Video/Source`.
  - **Per-App Android Streaming (`svc 15, 17` + VDM):** Streams up to 5 concurrent Android apps in individual desktop windows (`cmd 12` merge, `cmd 13` focus, `cmd 17/26` debounced resize, `cmd 24` visible list), includes the headless notification-action runner (`svc -1`), and uses Android 14+ `VirtualDeviceManager` / `createVirtualDisplay` on `:android-app`.
  - **Cross Control (`svc 26` + `svc 16 displayId: 0`):** Sets `cap["26"] = 9` (`CAP_AVAILABLE or CAP_IN_USE`) within `1 s` of reconnect, captures screen edges via Wayland `wlr-layer-shell` or `xdg-desktop-portal` `InputCapture`, and streams `<= 125 Hz` motion/clicks/keys.
  - **Phone-as-Display (`svc 202` "R4SS"):** Streams PC mirror (`ScreenCast`) or virtual second monitor (`vkms`/`RecordVirtual`) to the phone and parses binary little-endian SCTP `touchDataChannel` frames (`type 0` 128B Touch, `type 1` 32B Key, `type 2` 48B Mouse, `type 3` 56B Pen).
- **Verification Gate:**
  - `SmartConnectMediaSignallingTest` green on CI + live Mirroring, Webcam, Per-App Streaming, Cross Control, and Phone-as-Display verified against Aryan's A5x.

---

#### Slice `S-E` — Telephony SMS/MMS Relay (`svc 204/100`) & Instant Hotspot Auto-Join (`svc 13`)
**Goal:** Complete 100% Smart Connect feature parity with SMS conversation sync/send and 1-click Hotspot handoff.

- **Deliverables (`core/**/ec0/core/engine/**`):**
  - `commonMain/.../smartconnect/protocol/SmartConnectSmsHotspotModels.kt`
  - `jvmMain/.../smartconnect/SmartConnectSmsAndHotspot.kt`
- **Key Features:**
  - **SMS Relay (`svc 100` & `svc 204`):** Parses `svc 100 cmd 1018` status, sends `svc 204 cmd 18` (`ver: "5.0", pId: 110`) for first-time PC enablement, syncs SIMs (`cmd 12/13`), contacts (`cmd 1/2`), and 50-message batches (`cmd 28/29`), normalizes counterpart threads (last 10 digits), and sends SMS via `cmd 4` (with mandatory `medias: []`).
  - **Hotspot Auto-Join (`svc 13`):** Sends `cmd 1` with ephemeral `SmartConnect-<4hex>` SSID and 16-char password, joins via `nmcli dev wifi connect` on `cmd 2` (with password redaction), and removes the temporary NetworkManager profile on `cmd 4/6` stop.
- **Verification Gate:**
  - `SmartConnectSmsAndHotspotTest` green on CI + live SMS sync/send and 1-click Hotspot auto-join verified on Aryan's A5x.

---

## 6. Verification & Coordination Protocol

1. **Hub Discipline & PR Workflow (`agentchat.md`):**
   - Push feature branches (`feat/gemini-argon-*`) and open Pull Requests via GitHub API / `gh pr create`; never force-push (`git push --force` forbidden); never touch `main-2`, `.github/workflows/build.yml`, `android/`, `localsend-upstream/`, or other agents' files (`android-app/`, `desktop/`, `core/localsend/`).
2. **Design Gate First (`T16`):**
   - Stop after `docs/engine/smart-connect-design.md` and `docs/engine/roadmap.md` are submitted for review; wait for `agent-0` approval before writing `S-A` implementation code.
3. **CI is the Compiler & Same-Commit Tests:**
   - Every code slice updates production code, unit tests, and any affected signatures in the **same commit**, pushes to `origin`, and verifies `.github/workflows/build.yml` finishes green (inspecting the uploaded `test-reports` artifact if any test fails, as Gradle console output truncates stack traces).
4. **Cryptographic & Wire Vector Verification Rule:**
   - Every test constant (SHA-256, HKDF-SHA256, AES-256-GCM `SealedDiscoveryFrame`, salted-hash `nonceId`, Ed25519, X25519, ChaCha20-Poly1305, RFC 6455 accept key, DNS wire bytes, XDR/NFSv3 frames) is computed/verified via `python3` before being pinned in `commonTest` / `jvmTest`.

---

## 7. Execution Sequence Summary

| Slice | Scope | Primary Artifacts | Gate |
|---|---|---|---|
| **Slices 0–7** | Core `ec0-engine` (Crypto, mDNS, WS+AEAD, Clipboard, Resumable Files, Input, Notifs, Presence, ColorOS Resilience) | `docs/engine/{design,coloros-resilience}.md`, `core/**/ec0/core/engine/**` | **COMPLETE** (CI green, Slice 1 crypto gate & rekey review cleared by `agent-0`) |
| **Slices 8–11** | CamDroid v1/v2, Linux/Android OS Bridges, QR, Persistent Store, Universal Control, USB-C Tunnel, VirtualMic, Handoff, OTP Scrub | `CameraModels`, `ContinuityCameraCoordinator`, `LinuxSystemBridge`, `PersistentEngineStore`, `Slice11ContinuityServices` | **COMPLETE** (CI green, all 5 runtime tests fixed & verified in `d5b82af` / `980aa05`) |
| **T16 Design** | Motorola Smart Connect Full-Parity Architecture & Slice Plan | `docs/engine/smart-connect-design.md`, `docs/engine/roadmap.md` | **READY FOR LEAD REVIEW** (PR open, STOP before `S-A` implementation) |
| **Slice `S-A`** | `PinnedTlsWebSocketTransport`, `SealedDiscoveryFrameCodec`, QR/mDNS/BLE `0x5246` Wake, `svc 0` Handshake & `svc 25` Cap Map | `PinnedTlsWebSocketTransport`, `SealedDiscoveryFrameCodec`, `SmartConnectNodeCoordinator`, `SmartConnectDiscoveryAndBle` | CI green + **first live pairing with Aryan's A5x (`CPH2733IN`)** |
| **Slice `S-B`** | Control-WS Productivity: Clipboard (`svc 9`), Notifications (`svc 5/37` + Reply + Action Routing), PC Lock (`svc 22`) | `SmartConnectClipNotifModels`, `SmartConnectControlServices` | CI green + live clipboard, notification reply, and PC lock with A5x |
| **Slice `S-C`** | File Exchange: `fex` (`svc 2/17` App Icons & Clips) + File Station NFSv3 over mTLS (`svc 168` `SOCKET_SSL`, 64B FHs) | `SmartConnectFileModels`, `SmartConnectFexService`, `SmartConnectFileStationNfs` | CI green + live `recentApps` icons, `/sdcard` browse & `~/PhoneShare` export |
| **Slice `S-D`** | WebRTC Media & Supervised Helpers: Mirroring (`1/15/16`), Webcam (`3`), App Stream (`15/17` + VDM), Cross Control (`26`), Display (`202`) | `SmartConnectMediaModels`, `SmartConnectHelperSupervisor`, `SmartConnectWebRtcHelpers` | CI green + live Mirroring, Webcam, VDM App Streaming, Cross Control & Display |
| **Slice `S-E`** | SMS Relay (`svc 204/100`, `pId=110`) & Instant Hotspot Auto-Join (`svc 13`) | `SmartConnectSmsHotspotModels`, `SmartConnectSmsAndHotspot` | CI green + live SMS sync/send & 1-click Hotspot auto-join |


