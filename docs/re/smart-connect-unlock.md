# Smart Connect on non-Moto phones — gating analysis & ec0 unlock plan

Task: T15 addendum · agent-0 · 2026-11-14
Sources: vendored protocol docs (`vendor/smartconnect-linux/`, MIT — verified
against a real device by that project) + static analysis of the official
`com.motorola.mobiledesktop` APK pulled from aryan's OPPO A5x (build from
2026-11, `apks/MotoSmartConnect-base.apk`). Facts from the repo keep their
OBSERVED/INFERRED tags; APK findings are tagged [APK-STATIC].

## 1. What the Moto lock actually is

Two independent gates decide what a phone shows/permits:

1. **Peer capability gate (VERIFIED by smartconnect-linux on a real
   device):** the phone draws a feature tile only if the service id appears
   BOTH in the PC's verify-reply `serviceVersions` AND in the PC's service-25
   `cmd 1` `cap` map (service id → int flags). There is no separate feature
   list. A PC that announces the full map (e.g. `cap: {"1":1,"3":1,"13":1,
   "15":1,"22":1,"26":1}` + webcam) gets every tile — on any phone the app
   runs on.
2. **Phone-side device gate ([APK-STATIC] — verify in this pass):** whether
   the app checks its own `Build.MANUFACTURER`/`Build.MODEL` (or ColorOS
   ro.product overrides) before offering Smart-Connect-class features, and
   whether any entitlement/cloud check gates features beyond the peer cap
   map. smartconnect-linux reports pairing + tiles work on non-Moto phones
   with the stock app, which suggests gate 1 is the only one that matters
   for PC-attached features; the APK pass confirms or refutes.

## 2. What ec0 imports from each source

### From smartconnect-linux (working architecture — import patterns, MIT rules apply: describe, don't transplant)

| Pattern | Why ec0 wants it | ec0 landing zone |
|---|---|---|
| One daemon, supervised helpers (mirror host / webcam / display / files / fex), single local control socket | Media-heavy features isolated from control-plane crashes; helpers start/stop per session | ec0-engine process model: `Ec0EngineNode` stays control plane; media helpers launch per session (desktop: v4l2/PipeWire/portal) |
| Feature tiles via svc-25 cap map sent after verify | The unlock mechanism itself — and ec0's own device page should use the same "capabilities → tiles" model | `Capabilities` in the shared UI already mirrors this concept — wire it to real wire-announced caps |
| WebRTC media planes for mirror/webcam/display, control stays JSON | ADB-less mirror/webcam — ec0's scrcpy plan needs ADB; WebRTC removes it | Phase-2 media slice (after control-plane parity) |
| Phone storage as read-only FUSE mount (NFSv3 over mTLS) | Better than per-file sends for browsing | File-service slice 5 extension |
| Notification reply windows + action routing | Full notification parity | Notification relay slice |
| Reconnect discipline (mDNS invite + BLE GATT wake 0x5246) | Backgrounded-phone reachability — O+ Connect's known weakness | ColorOS-resilience slice |

### From the official Moto app (unlock + parity target)

- Full cap-map advertisement = all feature tiles on aryan's A5x.
- ec0's PC side speaks Smart Connect honestly (no vendor impersonation
  needed — the app pairs with any PC whose cert is pinned and whose cap map
  covers the feature).

## 3. ec0-engine design consequence (for argon's design doc)

One `PinnedTlsWebSocketTransport` + capability-map model serves all three
families:
- O+ Synergy: envelope codec A, per-profile pinned certs
- Smart Connect: envelope codec B (service ids), self-signed cert pinned via QR `fp`
- LocalSend: already its own HTTP stack (out of scope for this trait)

Each family declares its capability map → the shared UI `Capabilities` card
state becomes wire-true instead of hardcoded.

## 4. APK static-analysis results ([APK-STATIC], jadx on MotoSmartConnect-base.apk)

**`BoardUtils.isMotoDevice()` exists but does NOT gate the ecosystem features.**

- Implementation: `"motorola".equalsIgnoreCase(SystemProperties.get("ro.product.manufacturer"))`
  (`com/zui/continuity/board/util/BoardUtils.java:225`) — a runtime property read, not a
  signed entitlement.
- Call sites in the app: **LenovoID SSO / account flows** (`AccountActivity`, `WebAuth`),
  **NearbyDisplaysTile** (Moto hardware marketplace), and a CN-region branch. None of these are
  the PC-attached feature set.
- The core feature packages (`readyfor/{dashboard,appstream,webcam,files,display,pairing,controller}`,
  `smartconnect/core`) contain **no manufacturer gate** — the phone's feature tiles are driven by
  the peer capability map (svc 25), matching the vendored docs' device-verified finding.
- App streaming uses the **standard Android `VirtualDeviceManager`** API 34+
  (`com.motorola.smartconnect.core.companion.virtual.ScVirtualDeviceManager`, 13 VDM
  references, no vendor lock). Runtime caveat: VDM availability on ColorOS 15 / the A5x is
  unverified — needs a live check; if VDM is restricted, app streaming degrades to the
  smartconnect-linux fallback (svc 15 stream per app window via WebRTC, which does not need VDM
  on the PC side but the phone-side surface choice might).

## 5. Verdict

1. **The Moto lock on PC-attached features is the peer cap map, not the phone's manufacturer.**
   ec0's PC side announcing the full svc-25 `cap` map (plus `serviceVersions` in the verify
   reply) unlocks App streaming, Cross Control, PC lock, Hotspot, Webcam, Screen share and
   Mobile desktop tiles on aryan's A5x with the stock, unmodified Smart Connect app — exactly
   what smartconnect-linux observes on their devices.
2. The only genuinely Moto-gated features (LenovoID account sync, Nearby Displays hardware
   marketplace) are out of ec0's scope.
3. ec0 therefore ships: PC side = Smart Connect protocol client with honest full cap
   advertisement; phone side = stock Moto app (zero phone-side work needed for the unlock path)
   — while ec0's OWN phone app (KMP) implements the same protocol for the ec0-native path.

## 6. Immediate next steps (argon's design doc covers these)

1. Cap-map constants + verify-reply `serviceVersions` shape (from `device-status-and-tiles.md`
   + `pairing-and-discovery.md`) as wire-truth in ec0-engine.
2. `PinnedTlsWebSocketTransport` trait shared across O+ Synergy / Smart Connect.
3. Live VDM check on the A5x for the app-streaming surface decision.
