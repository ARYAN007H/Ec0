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

### Phase 1 results
(pending)
