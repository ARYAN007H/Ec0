# Synergy Protocol Spec (v0 draft) — ec0

Unified wire spec for O+ Connect / Synergy, synthesized from:
- **T1**: reconstructed gRPC protos (`docs/re/protos/`, descriptor-blob-verified, PR #2 rev.3)
- **T2**: message catalog (`docs/re/synergy-catalog.yaml`, 76/76 constants reviewer-verified, PR #1 r3)
- **T4**: dynamic capture — ⚠️ PENDING, sections marked `[T4]` are unconfirmed
- Findings baseline: `docs/re/oplus-connect-findings.md`

Status: **DRAFT** — static-analysis-derived; `[T4]` items require live capture
before implementation relies on them.

---

## 0. Stack overview

```
┌─────────────────────────────────────────────────────────────┐
│ Features: clipboard · files · input · cast · drag · camera  │
├─────────────────────────────────────────────────────────────┤
│ gRPC services (TLS): FileService · ClipBoardService ·       │
│   InputService · ClipboardFileService  (bare names, paths   │
│   = /<Service>/<Method>, no proto package)                  │
├─────────────────────────────────────────────────────────────┤
│ Control plane: WebSocket (OkHttp, WSS)                      │
│   JSON envelope SynergyMessage{cmd, info, msg_body,         │
│   msg_type, noncestr, signature, src_role, timestamp}       │
│   category key "category" · src_role value " synergy"       │
├─────────────────────────────────────────────────────────────┤
│ Transport: TCP port fallback per peer profile               │
│   computer: 8080, 39888, 8080, 49888, 59888 (retry=list.size)│
│   pad:      8792, 39888, 8792, 49888, 59888                 │
│   scene override: 8792 (WebSocketScene:190)                 │
│   SoftAP gateways: 192.168.137.1 (hotspot) · 192.168.49.*   │
│   (Wi-Fi Direct group)                                      │
├─────────────────────────────────────────────────────────────┤
│ TLS: pinned APK-bundled certs (server.cer = server_pad.cer, │
│   DER-in-APK, vendored PEM in docs/re/certs/) — X.509       │
│   CN=192.168.137.1, O=oppo, sha256 A8:92:CC:AC...68:D9      │
│   → phone presents this cert when acting as TLS server on   │
│   its SoftAP gateway; client trusts it via pinned asset     │
├─────────────────────────────────────────────────────────────┤
│ Discovery: pantaconnect SDK advertising layer               │
│   [T4] concrete mDNS service names/types UNKNOWN statically │
│   (PC daemon probes mDNS :5353; intent actions in catalog)  │
└─────────────────────────────────────────────────────────────┘
```

## 1. Connection lifecycle

1. **Discovery** — `[T4]` peer advertisement (multi-transport: BLE/WiFi/mDNS
   behind pantaconnect `advertisingOptions`). Phone engine services
   (`SynergyCoreService`, `DeepConnectCoreService`) run continuously.
2. **Transport selection** — LAN direct / Wi-Fi Direct group / SoftAP fallback;
   port walk per profile list above (duplicates intentional; `FirstPort`
   reset to list[0] after exhaustion).
3. **TLS** — client connects with trust anchored in bundled pinned cert
   asset for the profile (server.cer / server_pad.cer). `[T4]` whether the
   phone presents a cert chain under this CA and whether any client-auth is
   required at TLS layer.
4. **WebSocket upgrade** — `[T4]` exact URL path + handshake headers
   (static: URL built from discovered ip/port; `check_web_socket` cmd exists
   for PC-version probing).
5. **Session** — SynergyMessage exchange over WS; bulk data over gRPC
   streams (writeFile bidi, readFile server-stream, copyToClip
   client-stream, getClipData server-stream, getUriContent bidi,
   inputToTextbox client-stream; all else unary).
6. **Pairing/trust** — `[T4]` QR/pairing flow (`CaptureActivity` scanner;
   `SynergyTrustAgentService`; pairing key derivation UNKNOWN — the base
   send path uses constant signature=0x0FFFFFFF + Random(timestamp) nonce,
   strong per-message signing reserved for specific scenes `[T4]`).

## 2. Control-plane envelope (authoritative: SynergyMessage.java)

| Field | Type | Notes |
|---|---|---|
| `cmd` | object | {cmd_type, cmd_body, cmd_value, [flags]} — cmd_type from 40 TYPE_CMD_* |
| `info` | object | {info_type, info_body, info_value} — info_type from 10 TYPE_INFO_* |
| `msg_body` | string | |
| `msg_type` | | TYPE_CMD→SynergyCmd dispatch, TYPE_INFO→SynergyInfo |
| `noncestr` | long | base path: `Random(timestamp).nextLong()` |
| `signature` | long | base path: constant 0x0FFFFFFF (268435455) |
| `src_role` | string | " synergy" in engine config |
| `timestamp` | long | |

Constants catalog: `docs/re/synergy-catalog.yaml` (single source of truth for
codegen; per-constant role classifications reviewed).

## 3. gRPC services (authoritative: docs/re/protos + regenerated stubs)

Stubs: `:synergy-proto` module (protobuf + grpc-kotlin generated).
Key wire facts: **no proto package** (bare SERVICE_NAMEs → paths
`/FileService/createFile` style); `FileInfo.length`/`FileData.length` are
**sint64** (ZigZag); `PCfilepath` field name; `extras` = map<string,string>.

| Service | Methods (streaming) |
|---|---|
| FileService | createFile, [2 more unary], readFile (server-str), writeFile (bidi) |
| ClipBoardService | copyToClip (client-str), getClipData (server-str) |
| ClipboardFileService | getUriContent (bidi) |
| InputService | inputToTextbox (client-str) |

`drag.proto` carries databus messages only (no service) — frames ride the
SynergyMessage envelope (DragDatabusMessage etc.).

## 4. Feature flows `[T4]` (to be captured live)

- Clipboard sync (cmd clipboardstatus + ClipBoardService)
- File transfer (fileshareengine/wisetransfer + FileService)
- Input/keyboard-mouse (keymouselink KMS + InputService)
- Cast (castengine/heycast/wisecast; scene APPLICATION_CAST_RELAY; TV port 8792)
- Drag (DragCoreService + drag databus)

## 5. Open questions for T4 capture

1. mDNS service type/name + TXT records (phone↔PC advertisement)
2. WS URL path + handshake headers + any token in query
3. Pairing: QR payload format, key derivation, signature scenes that exceed
   the 0x0FFFFFFF base path
4. TLS: server cert chain under pinned CA; client-auth requirement
5. gRPC endpoint ports vs WS port relationship (10150/10155 seen on PC daemon)
6. Handshake cmd sequence after WS open (first 10 SynergyMessages, both roles)
