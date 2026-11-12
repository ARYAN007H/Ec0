# Synergy Message Catalog — human-readable reference

Companion to [`synergy-catalog.yaml`](./synergy-catalog.yaml) (machine-readable,
for codegen). Extracted 2026-11-12 from decompiled **OPSynergy 17.7.35**
(`com.oplus.linker`), sources under `~/Documents/projects/rea-work/src-OPSynergy/`
(read-only). Static analysis only — runtime-verified values land via T4 capture.

## 1. Wire envelope — `SynergyMessage`

Gson-serialized JSON object. Every frame on the Synergy WebSocket is one of these.

| JSON key | Java field | Type | Notes |
|---|---|---|---|
| `msg_type` | mMsgType | string | `"cmd"` or `"info"` (`TYPE_CMD`/`TYPE_INFO`) |
| `msg_body` | mMsgBody | object | `SynergyCmd` when cmd, `SynergyInfo` when info |
| `src_role` | mSrcRole | string | observed: `"synergy"` (`Config.SYNERGY_NOTIFICATION_CHANNEL_NAME`) |
| `category` | mCategory | string | key from `ParserTag.TAG_CATEGORY` |
| `timestamp` | mTimestamp | long | `System.currentTimeMillis()` |
| `noncestr` | mNonceStr | **long** | numeric, not a string |
| `signature` | mSignature | **long** | numeric, not a string |

**Auth reality check (evidence `base/socket/WebSocketConnectManager.java:558`):**
the base send path builds
`noncestr = new Random(timestamp).nextLong()` and
`signature = 268435455L` (constant `0x0FFFFFFF`).
So at least the notification-channel path carries a **constant signature** and a
timestamp-seeded nonce. Whether richer message classes (transfer/cast paths) use
a real MAC is an explicit open unknown for T4 capture. Design implication for
ec0: treat envelope auth as advisory; channel security rests on the TLS layer.

## 2. Command payload — `SynergyCmd` (msg_type=cmd)

| JSON key | Type | Default |
|---|---|---|
| `cmd_type` | string | `""` |
| `cmd_body` | object | null |
| `cmd_value` | int | `-1` |
| `reason` | string | `""` |

## 3. Info payload — `SynergyInfo` (msg_type=info)

| JSON key | Type |
|---|---|
| `info_type` | string |
| `info_body` | object |
| `info_value` | int |

## 4. Command catalog (complete, 65 strings + 11 flags)

Every constant from `protocol/SynergyCmd.java` is in the YAML. Grouped by theme:

- **Clipboard**: `clipboardstatus` (+ `FLAG_SHARED_CLIPBOARD_OPEN/CLOSE`)
- **File ops**: `file_transfer`, `file_transfer_mode`, `file_open`, `file_pc_backup`, `startdragfile`, `enddragfile`, `sendfiletopcresult`, `send_file_to_pc_error`, `file_manager_removed` (reason)
- **Mirage (app windows on PC)**: `mirageWindow`, `open_mirage_window`, `open_mirage_window_by_phone`, `open_mirage_window_status`, `close_mirage_window`, `minimize_window`, `window_state_changed`, keys `icon`/`app_name`/`packagename`
- **Screen mirror/cast**: `start_mirror`, `start_mirror_result` (`FLAG_START_MIRROR_*`), `stream_init/prepared/start/stop`, `codec_info`, `max_resolution`, `support_resolution`, `screen_status`, `pad_rotation`, `fold_mode_change` (info)
- **Input (KMS/KSC)**: `enable_key_event`, `key_events`, `synergy_pc_input_mode_start/stop`, `synergy_pc_update_cursor_position`, `rspksc`
- **Calls/SMS continuity**: `synergy_accept_phone`, `synergy_end_phone`, `synergy_ignore_phone`, `synergy_respond_via_message`, `synergy_interrupt_ring_phone`, `synergy_end_phone_result` (info), `is_pc_tel_support`, `FLAG_SINGLE_PHONE_CALL*`
- **Session/lifecycle**: `synergy_disconnect`, `synergy_disconnect_bt`, `check_web_socket`, `api_level`, `synergy_os_config`, `sync_lock_status`, `split_screen_state`, `top_package_name`, `task_package_names`, `synergy_need_privacy_toast`, `synergy_show_privacy_toast` (info)
- **VDC audio routing**: `vdc_audio_enable/disable`, `vdc_phone_init`, `vdc_audio_device_info*`, `vdc_audio_enable/disable_result*` (infos)

Flag value reference (ints for `cmd_value`): see YAML `flags:` block.

## 5. Transport constants

- **Default port list** (`WebSocketConnectManager.java:267`, verbatim):
  `["8080", "39888", "8080", "49888", "59888"]` → effective fallback
  **8080 → 39888 → 49888 → 59888**, enforced by `RetryAndChangePortInterceptor`
  (3 retries, swaps the port inside the URL, cycles back to the first port).
- **Scene server port**: `8792` (`WebSocketScene.java`, 3 sites — `synergy.mServerPort`).
- **Runtime override**: `setWebSocketPortList(String)` (called from `WebSocketScene`).
- **TLS**: OkHttp + custom `X509TrustManager` built via
  `trustManagerForCertificates(InputStream)` + `newEmptyKeyStore()` — certs
  originate from pairing (exact stream source = T4 unknown). Distinct OkHttp
  clients per peer profile: `buildWebClientForComputer` / `buildWebClientForPad`.
- **SoftAP gateways**: `192.168.137.1` (hotspot), `192.168.49.*` (Wi-Fi Direct group).
- **Not ports** (avoid false positives): `30409`/`3007701` = BI telemetry ids;
  `3834` = legacy candidate in `wiseconnect/connectold`+`senseless`, unconfirmed.

## 6. KSC control channel

- Success code `RESPONSE_KSC_CODE = "00000000000000000000000000000000"`
  (**not** a crypto key — a fixed response code in `PCSynergyConnection:63`).
- `REQUEST_KSC_WAIT_TIME = 2000` ms.
- State key `pc_connect_state` (Settings.Secure).

## 7. Engine services & identifiers (`com/oplus/synergy/utils/Config.java`)

- `CastEngineService`, `PcCastEngineService`, `FileShareEngineService`, `HandOffEngineService` (component names in YAML)
- `HEYSYNERGY_APPID = 503`, engine info file `synergycore_engine_info.xml`
- Notification: channel `com.heytap.synergy.service` / name `"synergy"`, notify id `1001`
- FileProvider authority `com.oplus.linker.devicemanager.fileprovider`
- High-signal intent actions (bind, command, QR display, cast start, NFC relay): YAML `actions:`

## 8. Open unknowns (T4 capture targets)

1. Exact WebSocket URL path (built from `pantaconnect` discovery P2P info at runtime).
2. Signature/nonce derivation for non-notification paths.
3. mDNS/advertising service names + TXT keys.
4. Pairing cert exchange flow + certificate store location.
5. Mapping of gRPC services (agent-1's protos) onto ports 8080/39888/49888/59888/8792.

## Gate evidence

- `SynergyCmd.java` string constants: **65 of 65 captured (100%)**; flag ints
  **11 of 11 (100%)** — file read in full, constants transcribed 1:1 (lines 9–84).
- Envelope/payload schemas transcribed from Gson `@SerializedName` annotations.
- YAML parses (checked with Python `yaml.safe_load`).
