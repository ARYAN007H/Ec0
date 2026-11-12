# T4 — Dynamic capture plan (Wine + O+ Connect live)

Status: IN PROGRESS · Lead: agent-0 · Needs: Aryan present for pairing step

## Environment
- Wine 11.21, prefix `~/tools/wine-ec0` (win64, dotnet48 installing via winetricks)
- Target: `~/Downloads/ec0/OplusConnectSetup_17_100_10_export_261109093906_f1a7040.exe`
- Phone: OPPO A5x (CPH2733IN) via adb, `pc_connect_support=1` flipped
- SUITE LOCK: adb device + LAN interfaces are single-user during capture

## Phase 1 — install + topology (no root needed)
1. Install setup exe into the prefix, launch client.
2. `ss -tunap` snapshot: listening ports (expect 10150/10155 + mDNS 5353 from
   static findings) and all sockets the daemon opens — full topology map.
3. Note any login/account wall (user wants login-free; find what's gated).
4. `gh`-side: log observed ports/URLs into this file's Phase-1 results.

## Phase 2 — user-space hooks (frida, no root)
- `pip install --user frida-tools` (in flight)
- Attach to `devicespace.exe` (wine process — Linux-native attach works for
  native hooks). Targets in priority order:
  1. `libc` `recv/send`/`read/write` on sockets → raw framing
  2. TLS write/read entry (rustls/go-tls) → pre-encryption plaintext
  3. mDNS send/recv → service names + TXT records
- Hook scripts live in `~/Documents/projects/rea-work/frida/` (NOT in repo —
  RE tooling, and they embed observed session keys).

## Phase 3 — LAN capture (needs Aryan's sudo, one command)
- `sudo tcpdump -i <iface> -w ~/Documents/projects/rea-work/captures/t4.pcap`
  while pairing runs. Analyze with REA `inspect_packet_capture` (tshark
  required) — Wireshark 4.x on PATH.

## Pairing flow (the part that needs Aryan)
- Phone side: check Settings → Connection & sharing → O Connect → look for
  "Connect with PC" (pc_connect_support=1 should surface ComputerLinkActivity
  there). If absent, try the OShare tile / QR-scan path.
- PC side: client is expected to show a QR / pairing code.
- Capture BOTH phases: discovery (idle, 60 s) + pairing handshake (active).

## Results log (append below as phases complete)

### Phase 1 results (2026-11-13, session 1)

**Client runtime — SUCCESS (partially):**
- Electron UI runs under wine 11.21 (prefix `~/tools/wine-ec0`, dotnet48 installed)
- Two patches to `resources/app/background.js` (asar replaced by `resources/app/` dir):
  1. UAC `repairService` branch bypassed → direct `N(t)` daemon spawn
  2. (added earlier) login reads `null` user gracefully ("get fake user info")
- Login window rendered: **"connect with sign in" / "connect manually"** — the
  login-free path EXISTS. Window then auto-hides (login BrowserView removed on
  SERVICE_CONNECTED / focus flapping); app continues in tray (wine tray icons
  invisible on KDE/Wayland).

**Daemon (`devicespace.exe`, Rust+Go) — boots, then self-exits:**
- Spawns adb (tcp:5037) itself — confirmed phone-USB path
- Enumerates WLAN, probes network (10.254.254.254, 8.0.0.8:80)
- Connects cloud **218.67.91.82:443** → closes instantly. Endpoint IS
  reachable (curl TLSv1.3 OK, cert verify fails on IP — normal). Instant close
  ⇒ suspected **wine schannel TLS failure** (client-side), not network.
- Tooling built: `LD_PRELOAD` shim (`rea-work/frida/traffic-shim.c`) hooks
  connect/close/send/recv/sendto/recvfrom — 160k frames captured. Frida
  attach blocked by yama ptrace_scope=1; strace absent; shim is the capture
  method going forward. **NOTE: wineserver does the real socket syscalls —
  preload applies to wineserver (restart via `wineserver -k`).**

**Conclusions / next options (T4 continuation):**
1. Wine schannel: try `winetricks gnutls`/dll overrides (`schannel=n,b`) or
   newer wine-staging; OR
2. Windows VM for a clean official-client run (most faithful capture); OR
3. Phone-side capture (frida-gadget repack — needsUnsigned APK; or root) —
   captures phone side pre-TLS, independent of wine.
4. Discovery probes (24-byte UDP `18 00 00 00 16 00 05 03 ...` repeating on
   multiple fds) still unidentified — capture on the LAN with tcpdump during
   a daemon run to correlate (needs sudo).
