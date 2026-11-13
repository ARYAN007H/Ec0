# T4 continuation — Windows-side RE agent mission brief

Paste-ready prompt for an agent running on the **Windows dual-boot** (official
O+ Connect client installed). Context for the operator: everything below was
verified statically against `com.oplus.linker` 17.7.35 and one Wine session;
the goal is the dynamic ground truth that static analysis cannot give us.

---

## Mission

You are assisting an interoperability engineering effort (ec0 — a FOSS
cross-device ecosystem app). Your job: run the official O+ Connect Windows
client on this real Windows machine, capture its wire behavior and runtime
internals, and produce a **structured markdown findings report**. This is
clean-room protocol research: observe and describe, never copy proprietary
code or assets.

**Phone prerequisite:** an OPPO A5x 5G on the same LAN, O+ Connect enabled,
with `settings put secure pc_connect_support 1` already applied so the
"connect to PC" surface appears. USB debugging available if adb probing helps.

## Priority 1 — Discovery ground truth (Wireshark, passive)

1. Install Wireshark (Npcap, "WinPcap API-compatible mode" checked).
2. Start a capture with filter `mdns || (udp.port == 5353) || (udp.port == 53317)` BEFORE launching the client.
3. Capture 3 × 60 s windows: (a) client idle in tray, (b) phone's O+ Connect screen open, (c) during active pairing.
4. Record exactly: mDNS **service type names** (expected family: `_XXX._tcp.local.` — we suspect a `pantaconnect` SDK family but the concrete names are unknown), TXT record keys/values, SRV targets/ports, and the anonymous **24-byte UDP probe** (`18 00 00 00 16 00 05 03 ...`) — destination port, interval, and any reply.
5. Note every TCP port the client/daemon listens on (compare against the known walk `8080, 39888, 8080, 49888, 59888`).

## Priority 2 — Plaintext visibility (Frida — this is the key trick)

TLS will NOT be mitmproxy-able (the client pins certs, same as the Android
app). On Windows, Frida attaches freely (no yama equivalent):

1. `pip install frida-tools`; locate `devicespace.exe` (Rust+Go daemon, listens 10150/10155) and the Electron client process.
2. Hook `ws2_32.dll` `send`/`recv`/`WSASend`/`WSARecv` **and** `ncrypt.dll`/`schannel` `EncryptMessage`/`DecryptMessage` — the latter shows **TLS plaintext post-decryption**, which is what we need.
3. Dump: the WebSocket upgrade request (`GET /... HTTP/1.1`, headers), every JSON envelope (`{cmd, info, msg_body, msg_type, noncestr, signature, src_role, timestamp}` — verify `signature` is the constant `268435455` and `noncestr = Random(timestamp).nextLong()` or differs on this client), and the first bytes of any HTTP/2 gRPC (`PRI * HTTP/2.0` + `:path` headers like `/FileService/...`).
4. Hook `CRYPTO`-adjacent calls only if the above is insufficient. Log with timestamps.

## Priority 3 — Pairing flow end-to-end

With capture + hooks running: complete a full pairing (phone ↔ PC), then send
one file phone→PC and PC→phone. Record the entire sequence: discovery hit →
transport connect → handshake messages (order, direction, payloads) →
transfer RPCs (method names, chunk sizes, ack shapes). This is the sequence
ec0 must reproduce.

## Priority 4 — Environment intel (Process Monitor / Task Manager)

- Which DLLs does `devicespace.exe` load for TLS (schannel vs boringssl static)?
- Registry keys / config files the client reads (feature flags — the Android
  app gates features client-side via `OplusFeatureConfigManager`; find the
  Windows equivalents).
- Does the client run WITHOUT signing in (the "connect manually" path)? What
  does it send to cloud `218.67.91.82:443` (endpoint reachable, client closes
  instantly under Wine — on real Windows check whether cloud contact is
  required for any feature)?

## Deliverable

One markdown report, structured as:

1. Discovery (service names, TXT schema, UDP probe, ports) — with pcap-backed timestamps
2. Transport (upgrade request, TLS details, framing)
3. Envelope/signature ground truth (does the Windows client sign like the APK?)
4. gRPC surface actually used (paths, methods, message shapes)
5. Pairing + transfer sequence (step-by-step)
6. Feature gating on Windows + cloud dependency
7. Anything surprising

Include raw hex/JSON excerpts as evidence. Save the pcapng + Frida logs
alongside. The report gets pasted back into the ec0 repo (`docs/re/`), so
keep phone identifiers/IPs out if the network is ever shared.

---

*Method notes from the earlier Wine session (for continuity): the client is
Electron with a Rust(tokio)+Go daemon `devicespace.exe`; the Wine run died at
schannel TLS; discovery probe `18 00 00 00 16 00 05 03 ...` is unidentified;
the JSON envelope shape and base signing are from APK static analysis
(WebSocketConnectManager:558) and need wire confirmation.*
