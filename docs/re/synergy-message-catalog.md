# Synergy Message Catalog — human-readable reference

Companion to [`synergy-catalog.yaml`](./synergy-catalog.yaml) (machine-readable,
for codegen). Extracted 2026-11-12/13 from decompiled **OPSynergy 17.7.35**
(`com.oplus.linker`), sources under `~/Documents/projects/rea-work/src-OPSynergy/`
(read-only). Static analysis only — runtime-verified values land via T4 capture.

**Gloss rule**: every `meaning` gloss is inferred from constant names unless
explicitly marked `meaning_source: code-evidence` (usage site quoted in the YAML).
This revision (r2) incorporates PR #1 review findings; see §4.1 for the
classification recheck and the one point where the full-tree scan disagrees
with the review count.

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
- **Mirage (app windows on PC)**: `mirageWindow`, `open_mirage_window`, `open_mirage_window_by_phone`, `open_mirage_window_status`, `close_mirage_window`, `minimize_window`, `window_state_changed`
- **Screen mirror/cast**: `start_mirror`, `start_mirror_result` (`FLAG_START_MIRROR_*`), `stream_init/prepared/start/stop`, `codec_info`, `max_resolution`, `support_resolution`, `screen_status`, `pad_rotation`, `fold_mode_change` (info)
- **Input (KMS/KSC)**: `enable_key_event`, `key_events`, `synergy_pc_input_mode_start/stop`, `synergy_pc_update_cursor_position`, `rspksc`
- **Calls/SMS continuity**: `synergy_accept_phone`, `synergy_end_phone`, `synergy_ignore_phone`, `synergy_respond_via_message`, `synergy_interrupt_ring_phone`, `synergy_end_phone_result` (info), `is_pc_tel_support`, `FLAG_SINGLE_PHONE_CALL*`
- **Session/lifecycle**: `synergy_disconnect`, `synergy_disconnect_bt`, `check_web_socket`, `api_level`, `synergy_os_config`, `sync_lock_status`, `split_screen_state`, `top_package_name`, `task_package_names`, `synergy_need_privacy_toast`, `synergy_show_privacy_toast` (info)
- **VDC audio routing**: `vdc_audio_enable/disable`, `vdc_phone_init`, `vdc_audio_device_info*`, `vdc_audio_enable/disable_result*` (infos)

Flag value reference (ints for `cmd_value`): see YAML `flags:` block.

### 4.1 KEY_CMD_* classification (review-driven recheck, full-tree scan)

All 14 `KEY_CMD_*` constants re-classified by scanning **every usage site** in
the decompiled tree (not name-based):

- **11 are type values** — sent via `setType(...)` or compared with
  `.equals(synergyCmd.mCmdType)` (two also used as `info_type` via
  `SynergyInfo.Builder().setType(...)` in `CastActionScene.java`):
  `check_web_socket`, `synergy_interrupt_ring_phone`, `synergy_need_privacy_toast`,
  `synergy_accept_phone`, `synergy_end_phone`, `synergy_ignore_phone`,
  `synergy_respond_via_message`, `split_screen_state`, `synergy_os_config`,
  `top_package_name`, `task_package_names`.
- **2 are payload body keys** — appear as JSON keys inside bodies:
  `icon` (`jSONObject.optInt(KEY_CMD_MIRAGE_APP_ICON)`, `hc3.java`),
  `packagename` (`jsonObject.addProperty(...)`, `wisecast/MirageScene.java`).
- **1 unreferenced** in decompiled app code: `KEY_CMD_MIRAGE_APP_NAME` (`app_name`).

*Disclosure:* the review claimed 7/14 as type values; the full-tree scan above
finds **11/14** with evidence per entry in the YAML (`cmd_type_values:` +
`payload_body_keys:` + `unreferenced_constants:`). Classification is what the
code shows, not the review count.

**Flag transport slot**: no `FLAG_*` constant has any send site in the
decompiled app namespace (full-tree scan: 0 uses) — the PC peer is their likely
consumer. The review asserts they ride `cmd_body` (not `cmd_value`); adopted in
the YAML as `transport_slot: cmd_body` **marked unverified** — confirm in T4.

## 5. Transport constants

- **Default port list** (`WebSocketConnectManager.java:267`, verbatim, used by
  the base and **computer** profiles):
  `["8080", "39888", "8080", "49888", "59888"]` → effective fallback
  **8080 → 39888 → 49888 → 59888**.
- **Pad profile port list** (`buildWebClientForPad`, WSCM ≈:481, verbatim):
  `["8792", "39888", "8792", "49888", "59888"]` → effective fallback
  **8792 → 39888 → 49888 → 59888** (8792 replaces 8080; also the scene server
  port used by `WebSocketScene` at :190/:212/:237).
- **Fallback mechanism**: `RetryAndChangePortInterceptor` swaps the port inside
  the URL across the list, then cycles back to the first port. **Retry count =
  `list.size()` at the real call sites** (`new RetryAndChangePortInterceptor(str,
  list, list.size())` → **5** for the 5-entry lists); the 2-arg constructor
  default of 3 (`RetryAndChangePortInterceptor.java:20`) is not what call sites
  use. Timeouts: write/read 50 s, connect 3 s, ping 5 s.
- **Runtime override**: `setWebSocketPortList(String)` (called from `WebSocketScene`).
- **TLS — trust model corrected per review**: the custom `X509TrustManager`
  (`trustManagerForCertificates(InputStream)` + `newEmptyKeyStore()`) pins
  **certificates bundled in the APK assets**, not pairing-exchanged certs:
  computer profile loads `context.getAssets().open("server.cer")` (TLSv1.2,
  WSCM:447); pad profile loads `server_pad.cer` (TLS, WSCM:477). Custom
  hostname verifier (obfuscated `oq3`); sockets bind to the local P2P IP
  (`SynergyLocalInfo.getLocalP2PIpByAsync()`) with default-factory fallback.
  How a peer gets *authorized* beyond this channel pinning (QR contents /
  pairing keys) remains a T4 unknown.
- **SoftAP gateways**: `192.168.137.1` (hotspot), `192.168.49.*` (Wi-Fi Direct group).
- **Not ports** (avoid false positives): `30409`/`3007701` = BI telemetry ids.
  (Earlier `3834` entry removed — decompiler artifact per review.)

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
4. Device-level pairing/trust model — bundled-CA TLS pins the *channel*
   (`server.cer` / `server_pad.cer` from APK assets); how a peer is *authorized*
   (QR contents, pairing keys/pin) is separate and unknown.
5. Flag transport slot — review asserts `cmd_body`, app-side send sites absent; confirm.
6. Mapping of gRPC services (agent-1's protos) onto ports 8080/39888/49888/59888/8792.

## Gate evidence

- `SynergyCmd.java` string constants: **65 of 65 captured (100%)**; flag ints
  **11 of 11 (100%)** — file read in full, constants transcribed 1:1 (lines 9–84).
- Envelope/payload schemas transcribed from Gson `@SerializedName` annotations.
- YAML parses (checked with Python `yaml.safe_load`).
- r2 re-gate: KEY_CMD classification from full-tree usage scan (python rglob,
  all 14 constants, every site listed in YAML evidence fields); pad port list +
  cert assets + retry count verified at WSCM:447/:477/:481/constructor;
  `RetryCount` default-vs-call-site distinction documented.
