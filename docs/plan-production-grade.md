# ec0 — Production-Grade Ecosystem Plan (v2)

> Supersedes the phase plan. This is the architecture for an OS-integrated ecosystem:
> every feature is a **capability-negotiated surface** over one authenticated engine,
> not a hardcoded tile. KDE Connect parity is a milestone, not the ceiling.

## 0. The one architectural principle

**The presence beacon's `capabilities` set is the product.** Every feature (mirror,
remote input, media, VFS, camera, find-my-phone) declares an `EngineCapability`,
negotiates it in the presence beacon, and *only then* renders its tile and wires its
transport. A phone without camera access shows no camera tile; a desktop without
playerctl shows no media tile. The UI is a **dynamic tile registry driven by the
negotiated capability set** — this is what makes ec0 an ecosystem instead of a
fixed app, and it is what makes multi-device correct (two paired phones negotiate
independent capability sets; every action targets a device, never "the phone").

## 1. Close the proven-but-unwired loops (stubs already in the protocol)

The wire layer already defines these; the executors are honest no-ops. Each row is
a feature with nearly zero new protocol work:

| Feature | Wire | Android executor | Linux executor | Gap |
|---|---|---|---|---|
| Remote input (phone = touchpad/keyboard for PC) | `InputKeyEvent`/`InputPointerEvent` frames ✓ | `onInjectKeyEvent/onInjectPointerEvent` → **false** | `LinuxInputInjectionBridge` exists (uinput/ydotool) | Android sender UI + uinput wiring |
| Media control (phone controls PC playback) | `MediaControlCommand` ✓ | `onRemoteMediaControl` → **null** | `MediaSessionBridgeService` exists (MPris) | Both sides unwired |
| Find my phone | `find_my_ring` ✓ | none | hub quick command exists | Android ring executor (AudioManager max-vol + wake screen) |
| Lock PC | `loginctl lock-session` ✓ | none | hub quick command ✓ | Phone-side tile + wire |
| Camera as webcam | scrcpy camera path ✓ | CameraX permission | pre-flight merged (v4l2/scrcpy ≥2.2) | `sudo modprobe v4l2loopback` live path + audio |

## 2. The four production pillars

### P1 — Dynamic capability tiles (the registry)
- `UiBridge.tiles` becomes a `StateFlow<List<CapabilityTile>>` built from
  `peerPresence.value[deviceId].capabilities` ∩ local support.
- Every action takes a `deviceId` — no `firstOrNull()` targeting anywhere.
- Per-device header (avatar, battery, transport) with a device switcher.

### P2 — Remote input & media (the KDE-killers)
- Android: a **touchpad surface** (pointer deltas + two-finger scroll + tap=left,
  two-finger=tap-right), a **keyboard bar**, **volume rocker for the PC**, and a
  **media tile** (MPris metadata from the desktop rendered on the phone).
- Desktop: uinput virtual device creation (rootless via the user's seat when
  possible, ydotool fallback with an honest setup hint), MPris bridge → phone.
- Latency budget: pointer events batched ≤ 8 ms, coalesced deltas, no per-event
  envelope signature cost (sign once per burst — the AEAD session already
  authenticates the stream).

### P3 — Virtual filesystem + reverse notifications
- **VFS**: `dir_list` envelope (scoped roots the phone grants: Downloads/DCIM
  explicitly, revocable per-root), bulk-chunk downloads (0x03 frames exist),
  desktop file-browser panel + Nautilus/Dolphin "Send to phone" via the
  existing `LocalDaemonIpcServer` (sub-5 ms handoff).
- **Reverse notifications**: D-Bus desktop notification feed → envelope → the
  phone renders them as its own notifications; per-app forwarding rules, quiet
  hours, and a privacy tier (title-only vs full body).

### P4 — OS integration surfaces
- Android: Quick Settings tiles (clipboard sync, mirror toggle, find-my-PC),
  Share-target completion (Share sheet → ec0 → PC), device-management screen
  (unpair, fingerprint verification, per-device capability toggles), OEM
  battery-guidance registry (ColorOS helper generalized to Samsung/One UI, etc.).
- Linux: uinput, MPris, D-Bus notify, PipeWire virtual mic (camera audio),
  Nautilus/Dolphin extension, GNOME/KDE shell extension (later).

## 3. Production hardening (the difference between demo and product)

- **Watchdog**: engine self-heal loop inside the foreground service (restart with
  backoff, alert when a crash-loop exceeds N/minute) — START_STICKY alone isn't enough.
- **Device management UI**: unpair (both ends), fingerprint human-verification
  (display SHA-256 prefix on BOTH screens to compare), per-device revocation.
- **Session metrics**: uptime, reconnect counts, transfer throughput (EMA exists),
  error rates — surfaced in an honest Settings → Diagnostics panel.
- **Compat gating**: `PROTOCOL_VERSION` handshake already exists — every new
  envelope type is additive and feature-flagged per capability, never a hard break.
- **Test-latch hygiene**: SmartConnectSliceATest + Ec0EngineResilienceTest 5 s
  latches are CI-flaky — 10 s latches or condition-polling.

## 4. Slice ownership (two rolling agents)

- **agent-1 (Android companion):** remote-input senders + executors, media/find-my/
  volume executors, QS tiles, Share-target completion, device management screen,
  capability flags in the presence beacon, OEM registry.
- **agent-2 (Desktop experience):** mirror v2 polish (live serial landed — resolution/
  fps pickers, device picker), uinput remote-input receiver, MPris media bridge,
  desktop→phone notification relay, dynamic tile registry, VFS desktop panel,
  Nautilus/Dolphin integration.
- **agent-0 (integration):** cross-slice seams (capability negotiation contract),
  CI, live-device verification, merges.

Dependency order: P1 registry (agent-2) ∥ capability flags (agent-1) → then the
feature tiles light up. Remote input is the flagship: A1 sender + B3 receiver meet
in the middle.
