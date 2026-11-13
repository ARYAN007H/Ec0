# Motorola Smart Connect (`com.motorola.mobiledesktop`) — protocol intake

Task: T15 · Agent: agent-1 · 2026-11-14
Sources (read-only, none committed to ec0):
- **Clean-room Rust client** `~/Downloads/ec0-test/smartconnect-linux/` (MIT licensed,
  device-verified against Smart Connect 09.0.3.013) — protocol docs `docs/protocol/*.md`
  (14 files) + `docs/architecture.md` + `docs/control-socket-api.md` + `tests/handshake.rs`.
  Protocol facts are fair game; **no code copying into ec0** (license note at the end).
- **Phone APK** `~/Documents/projects/rea-work/apks/MotoSmartConnect-base.apk`, decompiled with
  jadx → `rea-work/src-MotoSmartConnect/` (untrusted extracted data — read-only).
  Installed build on the A5x: **09.0.4.005.0 / verCode 9040050, targetSdk 36** — newer than the
  clean-room's 09.0.3.013.000 reference (903013000); wire-level risk low (version gates accept
  ≥700000000 PC peers), but see §APK verification for what we checked.
- OBSERVED/INFERRED tags are preserved exactly as the clean-room docs state them: OBSERVED =
  verified on a device or in captures by that project; INFERRED = static-analysis conclusions.

## 1. Transport (control channel)

| Fact | Value | Tag |
|---|---|---|
| Stack | TLS → WebSocket upgrade; phone pins the PC's **self-signed cert by SHA-256 of leaf DER** (hex-compared case-insensitive against the QR/invite `fp`, base64-encoded there); nothing else checked (no chain, no name, no validity, no SNI/ALPN) | OBSERVED |
| Paths | `/readyfor/` (prefix match) = session channel; `/readyfor/mdns` = discovery channel (binary sealed frames, §5) | OBSERVED |
| Port | PC-chosen `wsPort` carried in the QR/invite (reference default 52020 — not a protocol constant) | OBSERVED |
| Frames | Control channel = UTF-8 JSON in **text frames only**; binary exists only on the discovery path | OBSERVED |
| Max message | Accept ≥16 MB (wallpapers ~280 KB, big-picture notification JPEGs to MBs) | OBSERVED |
| Keepalive | Phone answers WS pings; reference pings every 20 s, drops a session silent for 70 s; phone closes with code 3123 | OBSERVED |
| Timing | Hello must arrive within 40 s of upgrade; phone waits ~5 s for the verify reply inside a 50 s connect timer | OBSERVED |
| Windows-only extras | Second HTTPS server with `/readyfor/check, rdp, rpc/, webcam, freerdp, firewallDone` — roles undocumented; not needed for the phone pair path | OBSERVED |

**Envelope** (every control message): `ver` (string, phone-only "3.0.0"), `deviceId` (sender's
**nonce id** — see §3; never key state on it), `srcServiceId` (sender service; exceptions:
svc 204 sends status as 100, tile taps come from 19), `serviceId` (destination), `senderId`
(phone-only, 1 = phone), `msgId` (per-sender counter; **no envelope-level ack layer**), `data`
(service payload; the message-type key is usually `cmd` but svc 5/37 uses `event`, svc 9 nests
its own `data.msgId`). Gson semantics on the phone: exact JSON types (booleans must be
`true`/`false` — a `0/1` is silently swallowed by svc 15 cmd 1 and times out after 15 s), unknown
fields ignored, map keys are strings (`{"9":4}`), some ids are 64-bit beyond 2^53 (svc 15
sessionId — parse as i64), nulls omitted. Tags: OBSERVED (README.md:86-143).

## 2. Trust model (no ECDH on the Wi-Fi path)

- **Pinned certificate** — SHA-256 learned out-of-band (QR or discovery invite).
- **Short-lived invite token** — 32 random bytes, base64 with `=`/`+`/`/` replaced by random
  alphanumerics (~44 chars), 60 s validity in Windows, compared in constant time; wrong/expired
  token → TCP dropped with no WS close frame. Not single-use within its TTL. Every discovery
  invite carries a fresh token which the phone echoes in its hello.
- After the handshake the PC hands the phone a random **per-pair key** (32 bytes, base64, used
  directly as the AES-256-GCM key for discovery frames only — the control channel relies on TLS
  alone). PIN pairing's 32-byte key is literally the PIN as big-endian u32 repeated 8×, no KDF.
- A paired phone may reconnect **without a valid token** (accepted by stored `realDeviceId` —
  noted by the clean-room authors as a weaker rule than Windows' per-invite token re-validation;
  INFERRED risk). OBSERVED (README.md:86-143, pairing doc).

## 3. Identity

| Item | Format | Tag |
|---|---|---|
| `realDeviceId` | base64 of 16 random bytes; stable; phone keys pairing records on the PC's | OBSERVED |
| **Nonce id** | base64 of `salt(8) ‖ SHA-256(realDeviceId ‖ salt)[0..8]`; used as envelope `deviceId`, mDNS TXT `deviceId`, BLE advert bytes, discovery-frame prefix; phone rotates it (~every 30 min); peers who know the stable id can re-derive it, others cannot link nonce ids | OBSERVED |
| `sn` | UUID string, **must be non-empty and stable** — an empty `sn` permanently poisons the pairing record on the phone (see pitfalls §9) | OBSERVED |
| Certificate | Self-signed X.509 (Windows: RSA-2048, ±30 d validity; subject/validity unchecked by phone) | OBSERVED |
| `os` / `type` | ints; `os` 6 = Windows PC, `type` 4 = PC class; phone = `os` 1 | OBSERVED |
| BLE advert | 16-bit UUID `0x5246`, 20 bytes service data: `0x10`(+`0x02` tablet), subtype/OS nibbles, capability bitmask LE u16, 16-byte nonce id. Advert runs continuously (250 ms interval, −7 dBm) even with the app backgrounded — but in background the UUID appears **only in service data**, so UUID-filtered scans miss it | OBSERVED |

## 4. Pairing handshake (service 0)

Sequence (all OBSERVED on device by the clean-room project unless noted):

1. PC shows QR: literal prefix `motorolardpconnection` + JSON `{"wsData":{...},"version":"3.0.0"}`
   with `cmd:1` ("dial now"), `wsPort`, `fp` (base64), `token`, `policies:[{"wifi":[ip,...]}]`
   (also `usb`/`acc`/`wfd`/`usb_proxy`), `os:6, type:4`, `verCode ≥ 700000000` (else the phone
   demands an app update), `bleAddress`, nested `device`. QR hidden after 55 s; token expires 60 s.
2. Phone dials `wss://<each policy address>:<wsPort>/readyfor/`, pins `fp`, sends **hello**
   (`cmd 0`): token, `paired`, `ip`, full `device` (incl. `realDeviceId`, `sn`, `key`, phone-side
   `serviceVersions`), `ips`. (`cmd 3` variant exists — meaning unknown.)
3. PC **verify reply** (`cmd 0`): `token:"OK"`, `connType`, `device` with `realDeviceId`,
   **non-empty `sn` + `verName` (null `verName` crashes the phone app)**, `caps`,
   `serviceVersions`, `key` = per-pair key. The phone caches `serviceVersions` per PC.
4. Immediately after: PC sends **svc 25 `cmd 1`** status (tile `cap` map) — without it the phone
   renders no feature tiles.
5. First pair only: phone sends `cmd 1` with its own key; PC answers **EXCHANGE_KEY** (`cmd 1`)
   whose `device.deviceId` **must be the PC's realDeviceId** (the tile shows online only when a
   live connection's realDeviceId matches this id) — this commit message is what writes the
   pairing record; a verified connection without it never appears (phone gives up ~7 s in).
6. Re-pair refresh: ~5 s after pairing Windows pushes a discovery invite so the phone reconnects
   and the tile flips to "connected".

Service 0 commands: `0` hello/TOKEN_VALIDATION, `1` sendSecretKey/EXCHANGE_KEY, `2` DELETE_KEY
(unpair; payload INFERRED), `3` hello variant (unknown), `6` user disconnect (`{ver:3,cmd:6,device}`;
phone closes ~2 s later — **never auto-reconnect after this**).

## 5. Discovery & reconnect

- **mDNS advert** `_readyformdns._tcp.local` both sides; TXT `deviceId` (nonce id), `deviceName`,
  `type`/`os`, `appType`, `cap`, `timestamp` (PC: seconds, phone: milliseconds), `fp` (64 hex).
  Phone listens only for non-empty `fp`, /24-then-/16 subnet match, non-zero port. **The phone
  advertises and serves `/readyfor/mdns` only while the app is foregrounded** on a secured Wi-Fi —
  backgrounding stops the server (an existing control connection survives). TCP-refused = on-LAN-
  but-backgrounded; timeout = absent. Tag: OBSERVED.
- **Discovery frames** (binary WS on `/readyfor/mdns`, one frame per message, never base64):
  `[16 B sender nonce id][1 B type: 1 sealed / 2 plaintext / 3 cannot-decrypt][12 B IV][AES-256-GCM
  ct ‖ 16 B tag]`, AAD = bytes 0..16, key = per-pair key used directly (no KDF). Payload = plain
  JSON: **invite** (`cmd 1`: fp, token, policies, wsPort, verCode…) or **trigger** (`cmd 0`:
  "send me an invite"). Type 2 and type 3 frames make the phone **unpair** the sender — never send.
  Fallback keys on decrypt failure: PIN keys, account key. Tag: OBSERVED (incl. a published test
  vector: key `00..1f`, nonce `a0..af`, IV `00..0b`, pt `{"cmd":1}` → frame `a0…af 01 000102…0b
  3c20b576a1c7f82af0 f7245b9e966e997f676a43dd38cacf53`).
- **Reconnect flows**: (A) phone taps Connect on an offline tile → mDNS resolve → sealed trigger →
  PC answers sealed invite on the same socket (last step INFERRED); (B) PC dial-back — PC sees the
  phone's advert, opens the phone's `/readyfor/mdns`, pushes one sealed invite (phone dials back in
  ~0.2 s; foreground-only); (C) **Bluetooth LE wake** — GATT service `0x5246`, write sealed invite
  to char `0x0722` (indications on first), notify char `0x0726`, long-message char `0x0728`
  (2-byte fragment header INFERRED), phone dials back ~0.3 s after the write and then drops the LE
  link. LE wake is the **only channel that reaches a backgrounded phone without an account**
  (whole wake 5–6 s in testing). Other channels: 0 cloud broker, 9 cloud push, 10 account P2P
  tunnel (all account-gated), 2/6/8 USB/accessory.
- Reference daemon reconnect policy: Wi-Fi dial-back first, then BLE (2/5/15/30/60 s backoff →
  300 s; capped 60 s while the phone answers on the LAN); single in-flight attempt; 30 s
  presence probe (TCP refusal = present); instant retry on resume/LAN-gain/BT-power.

## 6. Service map

| id | Service | One-line purpose |
|---|---|---|
| 0 | Manager | Handshake, pairing commit, disconnect |
| 1 | Session control | Mirror / Screen share / Mobile desktop views (view 1/2/3) |
| 2 | File exchange (fex) | Direct-TLS sender-built transfer server (app icons, clipboard images, send-files) |
| 3 | Webcam | Phone camera as PC webcam (WebRTC) |
| 5 / 37 | Notifications | Show-list / non-show-list notifications; actions, quick reply, open |
| 9 | Clipboard | Text both ways + image/file clip control plane |
| 10 | Display info | Phone desktop-mode display info (not implemented upstream) |
| 13 | Hotspot | Phone starts hotspot, PC joins |
| 15 | App Stream | WebRTC signalling for mirroring + per-app windows (max 5 displays) |
| 16 | Input | PC→phone mouse/keyboard/touch (JSON, absolute pixels) |
| 17 | App list | Phone app list; icons via fex zip |
| 19 | Pairing helper | QR `serviceId`; source of tile-initiated requests |
| 21 | (Windows) | labelled drag-and-drop in analysis |
| 22 | PC lock | Lock PC from phone (`"22">=2`) |
| 24 | Drag and drop | Bytes ride fex (phone handler; not implemented upstream) |
| 25 | Device status | Battery/signal/wallpaper + **feature-tile cap map** |
| 26 | Cross Control | "Universal Control": PC mouse/keyboard onto phone |
| 30 | Share Hub | `cmd 5001` MOUNT control plane (documented, unused) |
| 100 | Messages status | `cmd 1018` SMS permission/unread counts (from svc 204) |
| 168 | File Station | `cmd=1001` NFS handshake; genuine NFSv3 over mutual-TLS TCP |
| 202 | PC screen (R4SS) | PC screen to phone, mirror/extend, incl. phone-initiated `cmd 50` |
| 204 | Messages | SMS/MMS conversations, send, read state |
| 7/8/18/20/31/32/36/205/206/311/313 | Unknown/Windows-internal | present in caps/Windows' own status (313 = AI plugin) |

## 7. Versioning gates (how the phone decides what to bring up)

- PC peer must send `verCode ≥ 700000000` and a complete verify-reply `device` (crashes on null
  `verName`).
- `serviceVersions` map (top level AND inside `device`) cached per PC realDeviceId at verify —
  changes take effect **next** connection. Per-service minimums (clipboard `"9">=2`, pc-lock
  `"22">=2`). If `rfaServiceVersions` is present the phone uses it **instead** — never send it.
- **Tile gating is the load-bearing rule**: a tile shows only if the service is in
  `serviceVersions` AND is a key in the PC's **svc 25 `cap` map**; each svc 25 `cmd 1` REPLACES
  the whole map (always send complete). Cap bits: 1 available, 2 extra mode (INFERRED), 8
  in-use-elsewhere (cross-control checks it within 1 s of reconnect), 16 user-turned-off.
- Sub-protocol versions ride inside payloads (clipboard NEGO version 1; svc 202
  `key_pc_ss_version:"pc_ss_ver_2Dot5"`; svc 204 `ver:"5.0"`).

## 8. Media planes (exactly three)

1. **WebRTC SRTP** — mirror (svc 1/15/16), per-app streaming (svc 15, one virtual display +
   session per app), webcam (svc 3), PC-screen-to-phone (svc 202, PC sends video/audio, input
   returns as binary LE packets over an SCTP data channel — 128 B touch, 48 B mouse, 32 B keyboard).
   Phone is always the SDP offerer; H.264/H.265; UDP host candidates only; PC must trim the answer
   to a single H.264 payload type.
2. **NFSv3 over mutual-TLS TCP** — File Station (svc 168 `cmd=1001` handshake), both directions
   (browse phone / mount PC), 64-byte file handles exactly, per-session ports, Lenovo procs 22/23/27
   for thumbnails/categories/batch-remove. QUIC variant (`dt2type:3`) exists upstream, optional.
3. **fex** (svc 2) — the sender runs a fresh per-transfer TLS 1.3 + WebSocket + HTTPS server on a
   TCP port in [50000,59999] (ALPN http/1.1, new RSA-2048 cert, fingerprint advertised over svc 2);
   routes `/websocket`, `/download?taskId=`, `/downloadFile`; WS frames `<kind>:<id>:<name>?{json}`.
   Carries app icons (zip), clipboard images, send-files. `token` is NOT checked by `/download`.

Everything else is text JSON on the one control WebSocket.

## 9. Pitfalls the clean-room project hit (worth keeping in ec0's docs)

1. Empty `sn` poisons the pairing record permanently (upsert keyed by realDeviceId, existence
   probe keyed by sn; nothing deletes the row — not even unpair). Always send stable non-empty `sn`.
2. `verCode < 700000000` → "update your PC app" dialog; null `verName` → phone app crash.
3. QR `cmd` must be 1 (`cmd 0` → misleading Bluetooth error).
4. No EXCHANGE_KEY → no device tile (phone logs `handleFailure code:1` after ~7 s).
5. Regenerating the certificate breaks every pairing (pin) — keep the key pair for life of identity.
6. Firewall: control port TCP + mDNS UDP 5353 inbound.
7. Only reachable addresses in `policies` (phone dials every one).
8. mDNS TXT key is `fp` (a "`Cfp`" sighting is the TXT length byte 0x43).
9. BLE scanning: don't filter by service UUID (background advert hides it in service data); don't
   power-cycle the adapter (BlueZ re-connects user audio); cancel pending connects.
10. JSON booleans must be true/false (svc 15 silent timeout); nested `data` is sometimes a JSON
    string (svc 202, webcam, StationData); svc 15 sessionId and svc 202 sessionId are 64-bit.
11. Clipboard: suppress echo; never send msgId 13-17 (13 loops the phone handler, 15 throws);
    phone truncates text >100000 chars; empty-clip `{clipId:0}` broadcasts to other PCs — ignore.
12. Heartbeats: mirror watchdog 35 s (re-arms 65 s), webcam ≥30 s (`heartbeat:1` in cmd 0 arms it —
    without it the phone never times out), svc 15 expiry releases all sessions.

## 10. APK verification (com.motorola.mobiledesktop 09.0.4.005.0, decompiled `rea-work/src-MotoSmartConnect/`)

Spot-checks of the clean-room's INFERRED claims + extractions it did not have (grep-level; the
phone packages `com.motorola.readyforassist` / `com.motorola.readyfor` / `com.motorola.smartconnect`
are largely readable, dispatch helpers partially obfuscated):

| Claim under test | Result on 09.0.4.005.0 |
|---|---|
| `{"announce":true}` wrapper | **Refined**: the phone's own sources contain no `"announce"` string literal — the phone never *constructs* the wrapper and (per Gson unknown-field rules) merely tolerates it. The wrapper is load-bearing for **older peers** to parse our announcements; for a 09.0.4 phone it is inert either way. (Clean-room `AnnouncedMessage` + reviewer's "older v2 peers" framing confirmed.) |
| verCode version gate | `com.motorola.readyfor.view.VersionMatchingDialogActivity` exists — the "update your PC app" dialog is a real, dedicated Activity. Confirmed. |
| `minVerCode` | Present in `readyforassist.beans.DeviceBean` + `connectivity/b.java`; per-key semantics still not established (open question stands). |
| `rfaServiceVersions` | Identifier present in phone beans — the "replaces serviceVersions entirely" rule from the clean-room stays INFERRED-but-plausible (handler-level check not completed). |
| Device record shape | `readyforassist.beans.DeviceBean` fields match the documented `device` object (`certFingerPrint`, `realDeviceId`, `i3`, `port`, `scType`, `serviceId`, `sku`, `verCode`, `verName`, …) **plus extras** the clean-room docs don't list: `cloudPush` (int), `topNetworkType`, `requestId`, `hostIp`, `parentFolder`, `completedSize`/`totalSize` (transfer progress). Worth adding to ec0's parser tolerance. |
| `i3` meaning | Field exists on `DeviceBean` as a plain `int` with no discoverable semantic in 09.0.4 — open question stands. |
| Service map | Helper inventory confirms the family structurally: `R4Clipboard`/`R4RemoteClipboard` (svc 9), `HotspotServiceHelper` (13), `WebcamServiceHelper` (3), `AppStreamHelper` + `AppStreamListServiceHelper` (15/17), `ControlServiceHelper` (26), `DeviceStatusServiceHelper` (25), `BtPairServiceHelper` (BT pairing / svc 19), `CompanionAssociationProxyActivity` (the companion-association gate that cross-control needs on virtual-device phones), `AIServiceHelper` (the AI plugin = svc 313 in Windows' caps). |
| Clipboard msgId 13–17 danger | Not independently verified — handler dispatch is obfuscated (`R4Clipboard$2` etc. carry no readable case table). The clean-room's device-observed claim (13 loops, 15 throws) stands as-is. |
| Feature flags | No remote-config/flag service surfaced at grep level beyond `cloudPush` — gating observed in this stack is the **svc 25 cap map + serviceVersions** mechanism (§7), not vendor feature flags. |

## 11. License

Clean-room repo is **MIT** (© 2026 Rudra and contributors): protocol facts are unencumbered; the
copyright + permission notice must accompany any copied code. ec0 takes **facts only** — zero code
copied (GPL-3 app, MIT-no-notice-debt kept clean by design).
