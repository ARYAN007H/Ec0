# Smart Connect ↔ ec0 — feature delta & port ranking

Task: T15 · Agent: agent-1 · 2026-11-14
Companion to `smart-connect-findings.md` (protocol facts + tags live there).

## 1. Where ec0 stands vs the Smart Connect feature surface

| Smart Connect feature (svc) | ec0 today | ec0 slice that would host it | Transport implication |
|---|---|---|---|
| Clipboard sync (9) | KDE Connect clipboard model only (T6, fallback family) | `ec0-engine` clipboard service over the unified control-WS trait; desktop panel already exists (T11) | **Control-WS JSON only** — no media plane. Cheapest full-parity feature. |
| Notifications + actions/quick-reply (5/37) | Notification *listener* exists in the KDE fork (phone side); desktop feed not wired | Phone-app notification service + desktop notification feed; reply windows later | Control-WS JSON. Quick reply is a plain frame; **action/open on LAN ride a mini app-stream invite** (svc 15 cmd 2→1→4→13→7) — needs the stream machinery or degraded UX. |
| SMS/MMS relay (204/100) | Nothing (KDE fork has SMS plugin code as reference) | Phone SMS service + desktop Messages panel | Control-WS JSON; flat lists (PC builds conversations), 10-day query window, `pId:110` discipline, svc 100 status. No media plane. |
| PC lock (22) | Nothing | Phone tile → desktop lock (logind `LockedHint`, screenlocker quirks) | Two commands. Version gate `"22">=2` + svc 25 cap key. |
| Hotspot auto-join (13) | Nothing (ColorOS guide documents manual hotspot) | Phone hotspot helper + desktop joiner | 7 flat commands; PC picks SSID/pass. Upstream marks it unverified-on-device. |
| Webcam (3) | Planned via scrcpy camera + v4l2loopback (M4 plan) | Keep scrcpy path as primary; WebRTC webcam only if going ADB-less | **WebRTC media plane** (phone offers, 720p30 H.264, heartbeat ≥30 s) — parity with our scrcpy plan but wireless-native, no ADB. |
| Mirroring (1/15/16) | scrcpy mirror+control+audio (M4 plan) | Same value, different mechanism; WebRTC engine would replace ADB dependency | WebRTC + svc 16 JSON input (absolute pixels). Heartbeat watchdogs. |
| **App streaming** (15/17) | **Nothing** — and scrcpy 3.x virtual displays only partially cover it | Biggest true gap: per-app Android windows on the desktop, max 5, fex-delivered icons | WebRTC (one session per app) + svc 17 app list + fex icon server + instanceId discipline. |
| **Cross Control** (26 + 16) | scrcpy control (ADB) / KDE remote input fallback | ADB-less PC-input-on-phone; pairs with app streaming | Control-WS session (svc 26) + svc 16 input w/ `displayId:0`; phone creates virtual devices; needs companion association on some phones. |
| **Phone-as-display** (202) | **Nothing** (Sunshine/Moonlight as external fallback) | PC-screen-to-phone mirror/extend | WebRTC reverse (PC sends, phone receives) + binary LE input packets over an SCTP data channel — most exotic media plane. |
| File exchange (168/30/2) | LocalSend transfers (T5/T13, merged) — this is ec0's AirDrop layer | No work needed; optionally speak fex for Smart-Connect-native peers later | NFSv3-over-mTLS + fex server; duplicative for us. |
| Device status/tiles (25) | Battery/status exist in KDE models; no tile system | Tile model for the desktop shell (cap-map replace-whole semantics!) | Control-WS JSON. |

## 2. Transport convergence with O+ Connect (T4 overlap)

The two vendor stacks are **structurally closer than expected** — ec0's engine can share one
transport abstraction:

| Dimension | Smart Connect | O+ Connect (our T2/T10/T4 findings) |
|---|---|---|
| Bootstrap | **QR → `wsPort` → phone dials `wss://…/readyfor/` pinning cert SHA-256 from the QR `fp`** | QR/invite → `wsPort` → pinned TLS to **bundled per-profile assets** (computer/pad certs differ) |
| Frame | Text-frame JSON envelope (`ver/deviceId/srcServiceId/serviceId/senderId/msgId/data`) | Text-frame JSON (`SynergyMessage`: cmd/info/msg_body/msg_type/noncestr/signature/timestamp/src_role) |
| Message auth | TLS only — no per-message signature | `noncestr`/`signature` per envelope (base path = 0x0FFFFFFF + Random(timestamp)) |
| Discovery | `_readyformdns._tcp` mDNS + **binary sealed frames: nonce-id prefix + type + IV + AES-256-GCM(per-pair key), AAD = prefix+type** | Wiseconnect sealed frames — **also AES-256-GCM under a per-pair key** (T2 catalog) |
| Reconnect | mDNS invite + **BLE GATT wake (0x5246, backgrounded phone reachable!)** | Foreground-only mDNS server on the phone (documented limitation) + Wi-Fi dial-back |
| Handshake | hello(token) → verify(token:"OK") → EXCHANGE_KEY commit | hello/token/verify semantics (T4 capture refining) |
| Identity | `realDeviceId` stable + **salted-hash nonce ids for privacy** (rotating ~30 min) | `realDeviceId` concept, no nonce-id privacy layer found |
| Cloud | Lenovo account channels (0/9/10) | Oppo account (owork-api endpoints) |

**Takeaway for ec0-engine**: define one `PinnedTlsWebSocketTransport` trait + one
`SealedDiscoveryFrame` codec (both parameterized by envelope codec + cert source). Three protocol
families (LocalSend's HTTP, Synergy, Smart Connect) then hang off two shared shapes. The BLE wake
pattern is worth stealing for **all** families — it defeats the ColorOS background-kill problem
our T8 guide fights with settings alone.

## 3. Top-5 ports into ec0-engine (value ÷ effort)

1. **Clipboard sync, Smart Connect wire (svc 9)** — LOW effort (inline text JSON on the control
   WS, no acks, gates: `"9">=2` + phone switch), HIGH value (third-family Universal Clipboard;
   pitfalls known: msgId-in-data, echo suppression, never send 13–17). Reuses T10 transport +
   T6 clipboard models.
2. **PC lock (svc 22)** — TRIVIAL effort (two commands + cap-map key), MED value (phone tile
   locks the desktop; completes the tile story). Pair with svc 25 tile model.
3. **Notification mirroring + quick reply (svc 5/37)** — LOW-MED effort (frames are plain JSON;
   the phone-side listener exists in the KDE fork), HIGH value. Caveat: action buttons/open ride
   app-stream invites on LAN — ship quick-reply first, actions behind the stream machinery.
4. **SMS relay (svc 204/100)** — MED effort (flat lists, 10-day window, `pId:110`, batch
   pagination), HIGH value (Messages-on-desktop parity, works on any LAN peer).
5. **Device status + tile system (svc 25)** — LOW effort, MED-HIGH value: the cap-map model is
   the universal feature switch for the whole family (tiles exist only for advertised services
   that are also cap-map keys — replace-whole semantics), and ec0's desktop shell (T11) needs a
   tile model anyway.

Phase-2 (needs a WebRTC media plane in ec0-engine): webcam, mirroring, app streaming, phone-as-
display, cross-control — the four features with the highest wow-factor, gated on the one big
transport investment. scrcpy (M4) covers the user value of mirroring/control/webcam in the
meantime. Explicitly **not** worth porting: file exchange (LocalSend already covers it), cloud
channels (account-gated), QUIC dt2socket, Share Hub (dead upstream).

## 4. Risks / honesty notes

- Clean-room docs are device-verified against 09.0.3; our A5x runs 09.0.4 — spot-checks (§10 of
  findings) found no wire breaks, but `cloudPush`/transfer-progress fields exist on the phone's
  DeviceBean that older peers don't send (parsers must tolerate).
- The clean-room project is MIT: ec0 takes facts only, no code copied (see findings §11).
- SMS docs are the least-verified layer of the source repo ("mostly static-analysis") — treat
  svc 204 shapes as provisional until a capture (their svc 100 status message is the verified part).
