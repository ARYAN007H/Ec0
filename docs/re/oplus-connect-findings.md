# O+ Connect (Oplus Synergy) — RE Findings

Date: 2026-11-12 · Device: OPPO A5x 5G (CPH2733IN, ColorOS 15/16-era, Android 15+) · Sources decompiled with jadx 1.5.6 into `~/Documents/projects/rea-work/src-*/`

## 1. Packages on device (all pulled to `rea-work/apks/`)

| Package | App | Version | Path |
|---|---|---|---|
| `com.oplus.linker` | **OPSynergy — O+ Connect core** (UI + engine, 14,721 classes) | 17.7.35 | `/product/priv-app/OPSynergy/OPSynergy.apk` |
| `com.coloros.oshare` | OPPO Share (AirDrop-like; Wi-Fi P2P 5GHz ch160 + SoftAP; iPhone tap-share UI) | 17.2.36 | `/product/priv-app/OShare/OShare.apk` |
| `com.oplus.ndsf` | Nearby Device Service Framework (account-linked cross-device backbone, 4151 classes) | 17.3.0 | `/product/priv-app/DSF/DSF.apk` |

`com.oplus.synergy` (standalone engine app on flagship devices) is **NOT installed** — but the entire engine is embedded inside linker (see §3).

## 2. Feature gating (client-side, flag-based — NOT cloud/VIP)

- **PC Connect**: `Settings.Secure.pc_connect_support == 1` (was `0` on A5x). **Flipped to 1 via adb** (reversible: `settings put secure pc_connect_support 0`).
  - Enforces visibility of `ComputerLinkActivity` in `MetaDataScanner.shouldSkipByLocalCapability` (linker/entrance/scan/MetaDataScanner.java:262).
  - `ComputerLinkActivity` is `exported=true`, guarded by signature permission `com.oplus.permission.safe.SECURITY` (cannot start from adb shell; normal UI path works).
- **Pad Connect**: `OplusFeatureConfigManager.hasFeature("oplus.software.padconnect.support")` (framework feature, build-level) AND NOT `oplus.software.padconnect.limited` (read from OEM `app_feature` content provider — root-only writes).
- Other known keys: `tv_connect_support=1` (already on; TVTileService in QS tiles), `pc_connect_state`, `oshare_p2p_state`, `key_settings_pc_switch_panel`.
- Entrance model: O Connect home = `EntranceHomeActivity` scanning manifest meta-data (`mix.entrance.title/group/order/...`) of installed apps.

## 3. Engine services RUNNING on device (process `com.oplus.linker`)

- `com.oplus.synergy.service.SynergyCoreService` ← the PC-link engine
- `com.oplus.deepconnect.core.service.DeepConnectCoreService`
- `com.oplus.linker/.mac.client.entry.MacEngineService` (+ `SynergyTrustAgentService`, `AudioRecordingService`)
- `com.oplus.linker/.devicemanager.DeviceManagerService`
- Manifest also carries: `pc.cast.entry.CastCoreService`, `pc.cast.inputservice.CastInputService`, `pc.expand.entry.ExpandCoreService` (Sidecar-like), `keymouselink` KMS, `DragCoreService`, CameraLink activities (continuity camera), iPhone-link activities.

## 4. Protocol stack (from decompiled linker APK)

**Wire envelope** (`protocol/SynergyMessage.java`): JSON with fields `cmd`, `info`, `msg_body`, `msg_type`, `noncestr`, `signature`, `src_role`, `timestamp` → message-level auth (signature+nonce; key material from pairing).

**Command catalog**: `protocol/SynergyCmd.java` (187 cmd keys incl. clipboard status, mirroring, drag-file, key events, codec info…). `protocol/SynergyInfo.java`: `info_body/info_type/info_value`.

**gRPC services** (proto3, package `com.oplus.linker.synergy`, generated classes in APK):
`FileService`, `ClipBoardService`, `InputService`, `ClipboardFileService` (+ engines: `castengine`, `fileshareengine`, `heycast`, `wisecast`, `wisetransfer`, `wiseconnect`, `wifidata`).

**Transport**: WebSocket (`bus/websocket/WebSocketScene.java`, `wiseconnect/WiseConnectScene.createWebsocket`) over P2P info from `com.oplus.pantaconnect.sdk.connectionservice` (DisplayDevice / GlobalDeviceConnectionInfo). Legacy port candidate **3834** in `wiseconnect/connectold` + `senseless`. Cast relay scene key: `APPLICATION_CAST_RELAY`.

**Discovery**: PC daemon does mDNS on **5353**; phone side discovery via wiseconnect/deepconnect (exact service-type strings still to extract).

## 5. Windows client (O+ Connect 17.100.10, `OplusConnectSetup_...exe`, 375 MB)

- **Electron 26.6.10 + Vue** UI only (`resources/app.asar`, extracted to `rea-work/exe-extract/resources/app-asar`). Renderer views: `remotecontrol/`, `crossFile/`, `aiSearch/`, `login/`... Source maps shipped.
- **Real engine**: `daemon/devicespace.exe` — hybrid **Rust (tokio) + Go** binary:
  - TCP listeners **0.0.0.0:10150 / 10155**, mDNS 5353
  - `libpgysdwanclient.dll` = PGY SD-WAN (WAN relay/VPN transport for away-from-home)
  - `ClientSdkCPP.dll` (C++, protobuf — `dsf_account_message.proto` referenced), `datatrans.dll`
- **UI↔daemon bridge**: Windows named pipes `\\.\pipe\dspipc` (main), `castipc`, `dsprcipc`, `dsprcuiipc`, `exipc`; local HTTP servers `localhost:45213` (file provider), `49889` (childprocess), `bin/dsp.exe`, `childprocess/ChildProcess.exe` (.NET, log4net).
- Account endpoints seen: `owork-api-cn.oppo.com`, `preowork-api-cn.oppo.com` (account/WAN services — local LAN pairing expected not to require them).

## 6. Strategy (locked with user)

For aryan's A5x specifically: build **ec0-phone (Kotlin native)** speaking O+ Synergy protocol directly as the *PC-side peer* (phone engine already runs; no UI gating needed), plus **ec0-desktop (Rust core + UI framework TBD, likely Tauri)** replicating the official daemon's role: mDNS discovery → WebSocket (SynergyMessage auth) → gRPC services (files/clipboard/input/cast). KDE Connect + LocalSend protocols included as **fallback layer** for other Androids/non-Oplus peers.

De-risk order: (1) static protocol extraction from decompiled APK (websocket paths, auth, discovery, protos reconstructed from generated Java), (2) dynamic capture — Windows client under Wine + tcpdump/Wireshark on the LAN, Frida hooks on phone via REA runtime tools, (3) incremental Rust implementation: discovery → pair → clipboard → files → input → cast.

## 7. Next extraction targets

- ~~WebSocket transport~~ → mapped (§7.1)
- Generated protobuf Java (`Fileservice.java`, `Clipboard.java`, `Inputservice.java`) → reconstruct `.proto`
- `castengine/`, `heycast/`, `wisecast/` — casting protocol

## 7.1 Transport details (static pass 2, 2026-11-12)

- **WebSocket client**: OkHttp (`base/socket/WebSocketConnectManager.java`). Separate clients via `buildWebClientForComputer` / `buildWebClientForPad`; `RetryAndChangePortInterceptor` = **port fallback walk** (RetryCount = list.size(); duplicates in source lists are intentional).
- **Ports** (reviewer-verified): computer profile list `8080, 39888, 8080, 49888, 59888` (WSCM:267); pad profile list `8792, 39888, 8792, 49888, 59888` (WSCM:481); scene override port **8792** (WebSocketScene:190). SoftAP gateways `192.168.137.1` (hotspot) / `192.168.49.*` (Wi-Fi Direct group).
- **TLS** (reviewer-verified): `trustManagerForCertificates` loads **APK-bundled pinned assets** — `assets/server.cer` (computer profile, WSCM:447) and `assets/server_pad.cer` (pad profile, WSCM:477). NOT pairing-exchanged at the WS layer; extract both assets for the protocol spec.
- **Envelope** (confirmed, reviewer-verified): JSON `{cmd, info, msg_body, msg_type, noncestr, signature, src_role, timestamp}`; `noncestr`/`signature` are **longs**; base send path (WebSocketConnectManager:558) uses constant `signature=0x0FFFFFFF (268435455)` + `nonce = Random(timestamp).nextLong()` — strong signing reserved for specific scenes. `RESPONSE_KSC_CODE` (32 zeros, PCSynergyConnection:63) is a response **code**, not key material. Category key `"category"`; `src_role` value `" synergy"` (Config.java:293).
- **Discovery**: `com.oplus.pantaconnect.sdk.internal.discovery` (`ServiceDiscoverySdkExtension`, `ServiceDiscoveryOptions`) — abstracted multi-transport advertising (`advertiseType`, `advertiseParams`); intent actions `com.oplus.pantaconnect.device.discovery` / `com.oplus.pantaconnect.service.discovery`. Concrete mDNS service names NOT statically evident — extract dynamically.
- Cast relay scene key: `APPLICATION_CAST_RELAY`.

## 8. Dynamic capture plan (next phase, no phone modding needed)

Preferred: run official Windows client under **Wine on Arch** (user-space, full control) →
1. `tcpdump`/Wireshark on the LAN interface during discovery+pairing (mDNS service names, ports, TLS handshake shape)
2. **Frida attach to `devicespace.exe` under Wine** (pre-TLS dump of WS frames + signature derivation)
3. Compare phone-side constants (§4, §7.1) → write the protocol spec into `docs/re/synergy-protocol.md`
Fallback (phone-side): Frida hook on `SynergyCoreService` requires root or frida-gadget repack — avoid for now.

