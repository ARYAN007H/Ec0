# `ec0-engine` Deep System Integration & `CamDroid` Continuity Camera Specification

> **Part of `ec0` — SPDX-License-Identifier: `GPL-3.0-only`**  
> **Scope:** Native Linux OS integration (`Wayland` / `X11` / `Freedesktop` / `V4L2`), `ARYAN007H/CamDroid` Continuity Camera wire & control bridge, AirDrop CLI & file-manager context menus, and Android Companion runtime (`ec0.core.engine.android`).

---

## 1. Continuity Camera & `ARYAN007H/CamDroid` Integration

`ec0-engine` integrates directly with [`github.com/ARYAN007H/CamDroid`](https://github.com/ARYAN007H/CamDroid) as its primary Continuity Camera engine (`CameraBackend.CAMDROID_NATIVE`), with `scrcpy --video-source=camera --v4l2-sink=/dev/video10` (`CameraBackend.SCRCPY_V4L2_FALLBACK`) as a zero-install fallback.

### 1.1 Discovery & Transport Constants (`CamDroidConstants`)
| Constant | Value | Reference in `ARYAN007H/CamDroid` |
|---|---|---|
| `MDNS_SERVICE_TYPE` | `"_camdroid._tcp.local."` | `android/.../network/NsdHelper.kt` (`SERVICE_TYPE = "_camdroid._tcp."`) |
| `DEFAULT_TCP_PORT` | `4747` | `android/.../network/StreamServer.kt` (`port: Int = 4747`) |
| `DEFAULT_V4L2_DEVICE` | `"/dev/video10"` | `desktop/src/video/v4l2_output.rs` (`video_nr=10`) |
| `DEFAULT_V4L2_CARD_LABEL` | `"CamDroid"` | `desktop/src/video/v4l2_output.rs` (`card_label="CamDroid"`) |
| `MAX_PAYLOAD_BYTES` | `16 * 1024 * 1024` (`16 MiB`) | `ProtocolHandler.MAX_PAYLOAD_SIZE` & `ProtocolV2.MAX_PAYLOAD_SIZE` |

### 1.2 Binary Wire Framing (`CamDroidV1Codec` & `CamDroidV2Codec`)
1. **Protocol v1 (`ProtocolHandler.kt` / `desktop/src/protocol/wire.rs`):**
   - Header (`5` bytes Little-Endian): `[kind: 1B] [payloadLength: 4B LE] [payload: N bytes]`
   - `CamDroidMediaKind` discriminators:
     - `0x01`: `VIDEO`
     - `0x02`: `AUDIO`
     - `0x03`: `CONTROL` (UTF-8 JSON `ControlCommand`)
     - `0x04`: `METADATA` (UTF-8 JSON `ClientHandshake` / `ServerCapabilities`)
     - `0x05`: `HEARTBEAT` (empty payload, 2000 ms interval)
     - `0x06`: `VIDEO_CONFIG` (SPS/PPS codec config)
     - `0x07`: `AUDIO_CONFIG` (AAC config)
2. **Protocol v2 (`ProtocolV2.kt` / `desktop/src/protocol/v2.rs`):**
   - Header (`36` bytes Little-Endian):
     - `[0..3]`: ASCII magic `"CMD2"` (`0x43, 0x4D, 0x44, 0x32`)
     - `[4]`: Version `2` (`0x02`)
     - `[5]`: `kind` (`CamDroidMediaKind.wireValue`)
     - `[6..7]`: `flags` (`u16` LE: `KEY_FRAME = 1 << 0`, `CONFIGURATION = 1 << 1`, `DISCONTINUITY = 1 << 2`)
     - `[8..15]`: `sessionId` (`i64` LE)
     - `[16..23]`: `sequence` (`i64` LE)
     - `[24..31]`: `captureTimestampNs` (`i64` LE)
     - `[32..35]`: `payloadLength` (`i32` LE, `0..16 MiB`)
     - `[36..36+N]`: `payload`

### 1.3 Dual-Representation Camera Control Commands (`CamDroidControlCommand`)
- Over direct TCP `:4747` (`CamDroidV1Codec.controlCommandPacket`), `toCamDroidJsonString()` emits the exact `serde_json` snake_case tagged JSON expected by `CamDroid`'s `StreamServer.kt` (e.g., `{"cmd":"set_zoom","value":2.5}`).
- Over `ec0-engine` authenticated WebSocket sessions (`cmd_type = "cam_control"`, `category = "cam"`), `toJsonObject()` encodes scaled integer fields (`zoom_x100 = 250`, `distance_x100 = 175`, `compensation = -2`) so every envelope passes strict integer-only `CanonicalJson` signing without floating-point ambiguity.

---

## 2. Linux System Integration Bridges (`ec0.core.engine.linux`)

1. **Wayland & X11 Universal Clipboard (`LinuxClipboardBridge`):**
   - Auto-detects `WAYLAND_DISPLAY` / `XDG_SESSION_TYPE=wayland` (`wl-copy` / `wl-paste`) vs `DISPLAY` / `X11` (`xclip -selection clipboard`).
   - Supports `text/plain` and `image/png`.
   - Enforces SHA-256 loopback echo suppression: applying an incoming Android clipboard item records its SHA-256 so the next local clipboard poll never bounces the remote clip back to the phone.
   - Honors `ClipboardItem.isSensitive` (`ClipDescription.EXTRA_IS_SENSITIVE`): sensitive clips are never written or broadcast.
2. **Freedesktop Desktop Notifications (`LinuxNotificationBridge`):**
   - Dispatches incoming `NotificationPostInfo` items to `notify-send` using discrete `ProcessBuilder` argv elements and a `--` option terminator before `<title>` and `<body>`, eliminating shell/flag injection surfaces.
3. **AirDrop CLI & File-Manager Context Menus (`LinuxDesktopIntegrationGenerator` & `Ec0CliCommand`):**
   - CLI modes:
     - `ec0 --send <file-or-dir...> [--peer <deviceId>]`
     - `ec0 --mirror --host <phone-ip> [--port 5555] [--fps 60] [--bitrate-kbps 8000]`
     - `ec0 --camera --host <phone-ip> [--port 4747] [--backend camdroid|scrcpy] [--res 1080p|1440p|4k] [--fps 30|60] [--front]`
     - `ec0 --install-desktop-integration`
     - `ec0 --doctor`
   - Installs:
     - `~/.local/share/applications/ec0-airdrop.desktop`
     - `~/.local/share/kio/servicemenus/ec0-airdrop.desktop` (KDE Dolphin right-click menu)
     - `~/.local/share/nautilus/scripts/Send via ec0 AirDrop` (GNOME Files / Nautilus executable script)

---

## 3. Android Companion Bridge (`ec0.core.engine.android`)

`AndroidCompanionRuntime` wraps `Ec0EngineNode(role = ROLE_PHONE)` and exposes `AndroidPlatformCallbacks` for:
- `onApplyRemoteClipboard(item: ClipboardItem)` -> `ClipboardManager.setPrimaryClip`
- `onExecuteInlineReply(reply: NotificationReplyCommand)` -> `NotificationListenerService` `RemoteInput` `PendingIntent`
- `onDismissSystemNotification(notifId: String)` -> `NotificationListenerService.cancelNotification`
- `onInjectKeyEvent` / `onInjectPointerEvent` -> `AccessibilityService` / `InputMethodService`
- `onCameraControlCommand(command: CamDroidControlCommand)` -> `CamDroid` `StreamingController`
- `onDeviceWakeOrNetworkValidated()` -> ColorOS Hans unfreeze recovery (broadcasts fresh battery/screen `PresenceBeaconInfo` to all connected desktops)
