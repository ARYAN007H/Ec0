# `ec0-engine` — Wire Format, Cryptographic Specification & Service Design (v1)

**Status:** Slice 0 Deliverable (normative specification for Slices 1–7)  
**Author:** `gemini-argon`  
**Target Namespace:** `ec0.core.engine.*` (`commonMain` + `jvmMain`)  
**License:** GPL-3.0-only (clean-room specification)

---

## 0. Design Philosophy & Relationship to O+ Synergy

`ec0-engine` is `ec0`'s self-sufficient, first-party cross-device continuity engine for **Android ↔ Linux Desktop** (and any JVM/KMP peer). It coexists with `ec0.core.synergy` (O+ Connect native mode), `ec0.core.localsend`, and `ec0.core.kdeconnect` behind the desktop UI's `DeviceSource` seam.

From our static and dynamic reverse-engineering of O+ Connect (`docs/re/synergy-protocol.md`, `docs/re/synergy-catalog.yaml`, `docs/re/t4-capture-plan.md`), we retain the **three architectural decisions that worked well in Synergy**:
1. **Unified JSON Control Envelope (`Ec0Envelope`):** Preserves the `msg_type` (`"cmd"` | `"info"`), `msg_body`, `src_role`, `category`, and `timestamp` layout so existing Synergy mental models transfer directly.
2. **RFC 6455 WebSocket Session Spine:** A single persistent, heartbeat-monitored WebSocket connection carries both low-latency JSON control envelopes (`TEXT` opcode) and multiplexed high-throughput binary streams (`BINARY` opcode).
3. **Deterministic Port-Walk + Gateway Fallback:** Survives restrictive LANs, USB tethering, Wi-Fi Direct (`192.168.49.1`), and phone SoftAP hotspots (`192.168.137.1`) via a deterministic port-walk sequence.

At the same time, `ec0-engine` eliminates every flaw uncovered in the OEM stack:
- **Zero Cloud or OEM Dependency:** Never touches `218.67.91.82:443` or `com.oplus.linker`.
- **Real Identity & Per-Session Forward Secrecy:** Replaces APK-bundled static certificates (`server.cer` / `server_pad.cer`) with per-device **Ed25519** keys, **X25519** ephemeral ECDH, **HKDF-SHA256** key derivation, and **ChaCha20-Poly1305** authenticated frame encryption.
- **Deterministic Canonical Envelope Signing:** Replaces `signature = 0x0FFFFFFF` and `Random(timestamp)` with **Ed25519** signatures over a strictly specified canonical JSON preimage (`ec0-sig-v1`) plus a `±300 s` + 64-entry sliding replay window.
- **Resumable, Cancellable Chunked File Transfers:** Solves both LocalSend's mid-stream cancel gap and Synergy's undocumented `FileData.flag` semantics via 256 KiB SHA-256-verified chunks and `.ec0part.json` resume manifests.

---

## 1. Network Constants & Port-Walk Specification (`EngineConstants`)

```kotlin
package ec0.core.engine

object EngineConstants {
    const val PROTOCOL_VERSION: Int = 1
    const val ENGINE_SRC_ROLE: String = "ec0-engine"

    // mDNS / DNS-SD service discovery (RFC 6762 / RFC 6763)
    const val MDNS_MULTICAST_IPV4: String = "224.0.0.251"
    const val MDNS_PORT: Int = 5353
    const val MDNS_SERVICE_TYPE: String = "_ec0._tcp.local."

    // WebSocket upgrade path
    const val WS_PATH: String = "/ec0/v1"

    // Dedicated primary port + shared high-port fallback tier
    const val PRIMARY_PORT: Int = 49880
    val DEFAULT_PORT_LIST: List<Int> = listOf(49880, 39888, 49888, 59888)

    // Hotspot / P2P gateway fallback candidates when mDNS is filtered
    val GATEWAY_FALLBACK_HOSTS: List<String> = listOf(
        "192.168.137.1", // Android / Windows SoftAP hotspot gateway
        "192.168.49.1",  // Android Wi-Fi Direct (P2P) group owner
        "192.168.43.1",  // Legacy Android tether gateway
    )
}
```

### 1.1 Rationale for `49880` First in `DEFAULT_PORT_LIST`
- O+ Synergy uses `8080` (computer profile) and `8792` (pad/scene profile) as primary ports before walking `39888, 49888, 59888` (`docs/re/synergy-transport.md`).
- LocalSend uses `53317` and KDE Connect uses `1716..1764`.
- `ec0-engine` claims **`49880`** as its primary listener port so a single Linux desktop or Android device can run `ec0-engine` (`49880`), Synergy-native mode (`8080`/`8792`), LocalSend (`53317`), and KDE Connect (`1716`) **concurrently without bind collisions**.
- If `49880` is occupied or blocked by a local firewall ruleset, `ec0-engine` walks `39888 → 49888 → 59888` in deterministic order (`retryCount = DEFAULT_PORT_LIST.size = 4`).

---

## 2. Cryptographic Primitives & Key Encoding

All cryptographic primitives use standard JDK 17 JCA providers (`SunEC`, `SunJCE`, `SUN`) in `jvmMain` and pure-Kotlin SHA-256 / HMAC-SHA256 / HKDF-SHA256 in `commonMain`. Zero third-party crypto dependencies are required.

### 2.1 Long-Term Device Identity (`Ed25519`, RFC 8032)
Each device generates a persistent `Ed25519` keypair on first launch.
- **Raw Wire Representation:**
  - Public key (`pk`): **32 bytes** (RFC 8032 compressed Edwards y-coordinate + sign bit).
  - Private seed (`sk`): **32 bytes** (RFC 8032 secret scalar seed).
- **JCA ASN.1 DER Interop (Deterministic Prefix Wrapping):**
  JDK 17's `KeyFactory.getInstance("Ed25519")` encodes/decodes `X509EncodedKeySpec` (SPKI) and `PKCS8EncodedKeySpec` using fixed ASN.1 headers for OID `1.3.101.112` (`06 03 2B 65 70`):
  - **Ed25519 SPKI DER (44 bytes total):**
    `302a300506032b6570032100` (12-byte header) `|| pk (32 bytes)`
  - **Ed25519 PKCS#8 DER (48 bytes total):**
    `302e020100300506032b657004220420` (16-byte header) `|| sk (32 bytes)`
- **Device ID (`deviceId`) & Fingerprint (`fp`):**
  - `deviceId` = lowercase hex `SHA-256(pk_raw_32_bytes)` (64 hex chars).
  - Short verification code (human-comparable 8-char badge, matching KDE Connect's UX convention) = uppercase first 8 hex chars of `SHA-256(sortedConcat(pk_A_raw32, pk_B_raw32))`.

### 2.2 Ephemeral Key Agreement (`X25519`, RFC 7748)
Every WebSocket connection generates a fresh, single-use `X25519` keypair `(esk, epk)` and zeroes/discards it immediately after `HKDF-SHA256` session key derivation.
- **Raw Wire Representation:** 32-byte little-endian u-coordinate (`epk`).
- **JCA ASN.1 DER Interop (OID `1.3.101.110` = `06 03 2B 65 6E`):**
  - **X25519 SPKI DER (44 bytes total):**
    `302a300506032b656e032100` (12-byte header) `|| epk (32 bytes)`
  - **X25519 PKCS#8 DER (48 bytes total):**
    `302e020100300506032b656e04220420` (16-byte header) `|| esk (32 bytes)`
- **Low-Order Point Rejection:** If `KeyAgreement.generateSecret()` throws `InvalidKeyException` or produces an all-zero 32-byte shared secret $Z = 0^{32}$, the handshake MUST abort immediately with `HandshakeSecurityException`.

---

## 3. Pairing, TOFU Trust & Mutual Handshake Transcript

### 3.1 Pairing QR / URI Format (`ec0://pair`)
To pair a phone and Linux desktop without any cloud account:
1. Either device (typically the phone or the desktop Settings dialog) displays a QR code (and copyable URI string) encoding a `PairingQrPayload`:
   ```text
   ec0://pair?v=1&id=<deviceId-64hex>&pk=<base64url-nopad-32B-ed25519-pub>&name=<url-encoded-alias>&nonce=<32hex-16B-random>&port=<tcpPort>&ips=<comma-separated-lan-ipv4s>
   ```
2. The scanning peer validates:
   - `v == 1`
   - `pk` decodes to 32 bytes and `sha256Hex(pk) == id` (prevents mismatched ID/key injection).
   - `nonce` decodes to 16 bytes (128 bits).
3. The scanning peer pins `(id, pk, alias)` in its local `PinStore` as `TRUSTED_QR` and initiates a connection to `(ip, port)` carrying an **ephemeral-bound** `pairing_proof` inside `CLIENT_HELLO`:
   $$\text{pairing\_proof} = \text{HMAC-SHA256}(\text{key} = \text{nonce\_qr\_16B}, \text{data} = \text{ASCII}(\texttt{"ec0-pair-v1"}) \parallel \text{pk\_I\_raw32} \parallel \text{pk\_R\_raw32} \parallel \text{epk\_I\_raw32})$$
   - **Why bind `epk_I` (`107` bytes total HMAC input)?** Because the QR `nonce` is visible to anyone who physically views the QR code or captures a prior `CLIENT_HELLO`, binding `epk_I` guarantees that a captured `pairing_proof` cannot be replayed by another party with a different ephemeral key `epk_attacker` (and replaying the original `epk_I` fails both X25519 key derivation and `initSig` verification). Furthermore, the QR generator treats `nonce_qr` as a single-use token with a `120 s` expiry that is consumed upon the first completed `SERVER_AUTH_CONFIRM`.
4. **TOFU (Trust-On-First-Use) Mode for LAN Discovery:**
   - When connecting without a QR scan (manual accept prompt on both screens), both devices display the 8-character uppercase verification code `SHA-256(sortedConcat(pk_I, pk_R))[0..8)`.
   - Once the user clicks **Accept** on the receiving peer, `(deviceId, pk)` is persisted in `PinStore`.
   - **Strict Key-Pinning Invariant:** On all subsequent connections for an existing `deviceId`, if the presented `pk` differs from the pinned `pk` in `PinStore`, the handshake MUST fail closed with `KeyMismatchException` (never silently overwrite a pinned key unless the user explicitly invokes `unpair(deviceId)`).

### 3.2 4-Message Mutual Authentication & Bidirectional Key-Confirmation Transcript

Immediately after RFC 6455 WebSocket upgrade (`GET /ec0/v1 HTTP/1.1` → `101 Switching Protocols`), the Initiator (`I`, WebSocket client) and Responder (`R`, WebSocket server) execute a SIGMA-style mutual handshake with explicit key confirmation in both directions (`CLIENT_AUTH` on $K_{I\to R}$ at counter `0`, followed by `SERVER_AUTH_CONFIRM` on $K_{R\to I}$ at counter `0`) before any `Ec0Envelope` (`0x02`) or `BULK_CHUNK` (`0x03`) frame is allowed:

```text
Initiator (Client, I)                                    Responder (Server, R)
─────────────────────                                    ─────────────────────
Generate ephemeral X25519 (esk_I, epk_I)
Generate 16-byte random nonce_I

1. WS TEXT: CLIENT_HELLO ───────────────────────────────► Validate id == sha256(ik_pub)
   {                                                      Check PinStore (or ephemeral-bound pairing_proof)
     "type": "client_hello",                              Generate ephemeral X25519 (esk_R, epk_R)
     "ver": 1,                                            Generate 16-byte random nonce_R
     "id": "<hex64(sha256(ik_pub))>",                     Compute Z = X25519(esk_R, epk_I)
     "ik_pub": "<b64u(ik_pub_32B)>",                      Sign respPreimage (174 bytes) -> respSig
     "eph_pub": "<b64u(epk_I_32B)>",                      Derive (K_I2R, K_R2I, sessionId) via HKDF
     "nonce_i": "<hex32(nonce_I_16B)>",
     "alias": "arch-linux",
     "pairing_proof": "<optional-hex64>"
   }

Verify id == sha256(rk_pub)            ◄─────────────── 2. WS TEXT: SERVER_HELLO
Check PinStore for rk_pub                                 {
Compute Z = X25519(esk_I, epk_R)                            "type": "server_hello",
Verify respSig over respPreimage (174B)                     "ver": 1,
Derive (K_I2R, K_R2I, sessionId) via HKDF                   "id": "<hex64(sha256(rk_pub))>",
Sign initPreimage (174 bytes) -> initSig                    "rk_pub": "<b64u(rk_pub_32B)>",
                                                            "eph_pub": "<b64u(epk_R_32B)>",
3. WS BINARY (frameType=0x01, counter=0 under K_I2R) ───►   "nonce_r": "<hex32(nonce_R_16B)>",
   Plaintext JSON inside AEAD frame 0:                      "alias": "OPPO-A5x",
   {                                                        "sig": "<b64u(respSig_64B)>"
     "type": "client_auth",                               }
     "session_id": "<hex64(sessionId_32B)>",
     "sig": "<b64u(initSig_64B)>"                         Decrypt AEAD frame (0x01, counter=0) under K_I2R
   }                                                      Verify session_id == sessionId
                                                          Verify initSig over initPreimage (174B)
Decrypt AEAD frame (0x01, counter=0)   ◄─────────────── 4. WS BINARY (frameType=0x01, counter=0 under K_R2I)
  under K_R2I                                               Plaintext JSON inside AEAD frame 0:
Verify session_id == sessionId                              {
Verify status == "ok"                                         "type": "server_auth_confirm",
                                                              "session_id": "<hex64(sessionId_32B)>",
                                                              "status": "ok"
                                                            }
                     ══════════════ SESSION ESTABLISHED ══════════════
                     Application frames (0x02, 0x03) begin at counter = 1
                     in both directions (K_I2R counter >= 1, K_R2I counter >= 1)
```

### 3.3 Exact Binary Preimages for Handshake Signatures (174 Bytes)
Both roles sign the exact same 160-byte `transcriptBody`, prefixed by a 14-byte role-specific ASCII domain tag (preventing reflection and cross-role replay attacks):

$$\text{transcriptBody} = \text{ik\_pub (32B)} \parallel \text{rk\_pub (32B)} \parallel \text{epk\_I (32B)} \parallel \text{epk\_R (32B)} \parallel \text{nonce\_I (16B)} \parallel \text{nonce\_R (16B)}$$

- **Responder Signature Preimage (174 bytes):**
  $$\text{respPreimage} = \text{ASCII}(\texttt{"ec0-hs-resp-v1"}) \text{ (14B)} \parallel \text{transcriptBody (160B)}$$
- **Initiator Signature Preimage (174 bytes):**
  $$\text{initPreimage} = \text{ASCII}(\texttt{"ec0-hs-init-v1"}) \text{ (14B)} \parallel \text{transcriptBody (160B)}$$

### 3.4 Session Key Derivation (`HKDF-SHA256`, RFC 5869)
Given the 32-byte X25519 shared secret $Z$:
- $\text{salt} = \text{nonce\_I (16B)} \parallel \text{nonce\_R (16B)}$ (32 bytes)
- $\text{IKM} = Z$ (32 bytes)
- $\text{info} = \text{ASCII}(\texttt{"ec0-v1-session"}) \text{ (14B)} \parallel \text{ik\_pub (32B)} \parallel \text{rk\_pub (32B)} \parallel \text{epk\_I (32B)} \parallel \text{epk\_R (32B)}$ (142 bytes total)
- $\text{OKM} = \text{HKDF-Expand}(\text{HKDF-Extract}(\text{salt}, \text{IKM}), \text{info}, L = 96\text{ bytes})$:
  - `kInitToResp` ($K_{I\to R}$) = $\text{OKM}[0..32)$ (32-byte `ChaCha20-Poly1305` key for Initiator → Responder frames)
  - `kRespToInit` ($K_{R\to I}$) = $\text{OKM}[32..64)$ (32-byte `ChaCha20-Poly1305` key for Responder → Initiator frames)
  - `sessionId` = $\text{OKM}[64..96)$ (32-byte unique session identifier)

### 3.5 Session AEAD Framing (`ChaCha20-Poly1305`, RFC 8439)
Once `SERVER_HELLO` is processed, every frame sent over WebSocket `BINARY` opcode is an AEAD packet:

```text
┌───────────────────┬──────────────────────────┬───────────────────────────────────────┐
│ frameType (1B)    │ counter (8B, big-endian) │ ciphertext || poly1305_tag (N + 16B)  │
└───────────────────┴──────────────────────────┴───────────────────────────────────────┘
  0x01 = AUTH_CONFIRM  (CLIENT_AUTH on K_I2R @ counter=0; SERVER_AUTH_CONFIRM on K_R2I @ counter=0)
  0x02 = ENVELOPE_JSON (UTF-8 encoded Ec0Envelope, counter >= 1)
  0x03 = BULK_CHUNK    (multiplexed file/clipboard binary chunk, counter >= 1)
```

- **12-Byte AEAD Nonce Construction:**
  `nonce (12B) = [0x00, 0x00, 0x00, frameType] (4B) || counter_uint64_be (8B)`
- **Additional Authenticated Data (AAD, 41 bytes):**
  `aad (41B) = sessionId (32B) || frameType (1B) || counter_uint64_be (8B)`
- **Counter Rules:**
  - Each direction maintains an independent 64-bit unsigned monotonic counter (`sendCounter`, `recvCounter`).
  - Counter `0` in each direction is reserved exclusively for `frameType = 0x01` (`CLIENT_AUTH` on $K_{I\to R}$, `SERVER_AUTH_CONFIRM` on $K_{R\to I}$). Any `0x02` or `0x03` frame at counter `0`, or any `0x01` frame at `counter > 0`, is a fatal protocol violation.
  - Because WebSocket runs over ordered TCP, `recvCounter` MUST equal the expected next counter (`expectedCounter++`); any out-of-order or repeated counter or Poly1305 tag failure immediately terminates the session.

---

## 4. Control-Plane Envelope & Deterministic Canonical Signing (`ec0-sig-v1`)

### 4.1 `Ec0Envelope` JSON Wire Schema
Every `frameType = 0x02` (`ENVELOPE_JSON`) payload (and any signed offline-queued message) is a UTF-8 JSON object with the following fields:

| JSON Key | Kotlin Type | Required | Description |
|---|---|---|---|
| `msg_type` | `String` | Yes | `"cmd"` or `"info"` (mirrors `SynergyEnvelopeCodec.TYPE_CMD` / `TYPE_INFO`) |
| `msg_body` | `JsonObject?` | Optional | Structured `cmd` (`{cmd_type, cmd_body, cmd_value, reason}`) or `info` (`{info_type, info_body, info_value}`) object; omitted if null |
| `src_role` | `String` | Yes | Default `"ec0-engine"` |
| `category` | `String?` | Optional | Service domain: `"clipboard"`, `"file"`, `"input"`, `"notif"`, `"presence"`, `"mirror"`; omitted if null |
| `timestamp` | `Long` | Yes | Sender wall-clock epoch milliseconds UTC |
| `seq` | `Long` | Yes | Strictly positive (`>= 1`) monotonic sequence number per sender session |
| `nonce` | `Long` | Yes | 64-bit random signed integer (`SecureRandom.nextLong()`) |
| `sig` | `String` | Yes | Base64url (unpadded, 86 chars) 64-byte `Ed25519` signature over the `ec0-sig-v1` canonical preimage |

### 4.2 Normative Canonical JSON Rules for `msg_body` (`CanonicalJson`)
To guarantee that `Ed25519` signature generation and verification produce **byte-identical preimages** across Kotlin/JVM, Kotlin/Android, Python, and Rust without depending on `kotlinx.serialization` internal map iteration order, `CanonicalJson.encode(element: JsonElement?): String` enforces the following deterministic RFC 8785 (JCS) subset rules:

1. **Null Top-Level Body:** If `msg_body == null` or `msg_body is JsonNull`, its canonical string is `"{}"` (2 ASCII bytes: `0x7B 0x7D`).
2. **Zero Whitespace:** No spaces, tabs, or newlines outside string literals.`:` immediately follows object keys; `,` immediately separates elements.
3. **Recursive Lexicographical Key Ordering:**
   - For every `JsonObject` (at the root of `msg_body` and at all nested depths), entries whose value is `JsonNull` are **omitted** first (`explicitNulls = false`).
   - Remaining keys are sorted in ascending **UTF-16 code unit order** (`String.compareTo` in Kotlin, identical to ASCII / Unicode code point order for all BMP characters).
4. **Deterministic Integer-Only Number Formatting:**
   - **Wire Rule:** All numeric fields in `ec0-engine` protocol schemas are 64-bit signed integers (`Long`) or 32-bit signed integers (`Int`). Floating-point values (`Double`, `Float`, `NaN`, `Infinity`) are **prohibited** in `Ec0Envelope` bodies.
   - **Encoding:** Every numeric `JsonPrimitive` is parsed via `Long.parseLong(raw)` and formatted via `Long.toString()` — base-10 ASCII digits, optional leading `-` for strictly negative values, no `+` sign, no leading zeros (except `"0"`), no decimal point (`.`), and no exponent (`e`/`E`).
   - **Fail-Closed Validation:** If a numeric `JsonPrimitive` contains `.`, `e`, `E`, or cannot be parsed as a signed 64-bit `Long`, `CanonicalJson.encode()` MUST throw `IllegalArgumentException("Non-integer number prohibited in canonical Ec0Envelope")`.
5. **Deterministic String Escaping:**
   - Enclosed in ASCII double quotes `"..."` (`0x22`), encoded as UTF-8.
   - For each character `c`:
     - `"` (`U+0022`) → `\"`
     - `\` (`U+005C`) → `\\`
     - `\b` (`U+0008`) → `\b`
     - `\t` (`U+0009`) → `\t`
     - `\n` (`U+000A`) → `\n`
     - `\f` (`U+000C`) → `\f`
     - `\r` (`U+000D`) → `\r`
     - Any other control character in `U+0000..U+001F` → `\u00xx` using **lowercase** hexadecimal digits (`0-9`, `a-f`).
     - All other Unicode characters (`>= U+0020`) are emitted as raw UTF-8 bytes without `\uXXXX` escaping.
6. **Booleans & Arrays:**
   - Booleans emit lowercase `true` or `false`.
   - `JsonArray` elements retain their exact index order, with `JsonNull` elements encoded as `null` inside arrays and nested objects/values recursively canonicalized.

### 4.3 `ec0-sig-v1` Signing Preimage Construction
The exact byte array passed to `Ed25519` `Signature.update(bytes)` is the UTF-8 encoding of 8 newline-separated (`\n`, `0x0A`) fields (with **no** trailing `\n` after line 8):

```text
ec0-sig-v1\n
<msg_type>\n
<src_role>\n
<category_or_empty>\n
<timestamp.toString()>\n
<seq.toString()>\n
<nonce.toString()>\n
<CanonicalJson.encode(msg_body)>
```

- **Header Field Sanitization:** `msg_type`, `src_role`, and `category` MUST NOT contain `\n` (`0x0A`) or `\r` (`0x0D`); if present, the signer/verifier throws `IllegalArgumentException` to prevent line-injection attacks between header slots.
- **Why Both Session AEAD and Per-Envelope Ed25519 Signatures?**
  1. Direct transferability from `SynergyEnvelope` (`SynergyEnvelopeCodec` in `ec0.core.synergy`).
  2. **Offline Outbox Integrity:** Clipboard items and notifications queued while a peer is asleep/offline are signed at creation time and carry non-repudiable origin authentication + creation timestamp when flushed later.

### 4.4 Replay Protection (`ReplayWindow`) & Normative Offline-Flush Deduplication
Each session tracks incoming envelopes using a sliding replay window + clock-skew gate + cross-session content deduplication:
1. **Live Frame Clock-Skew Gate:** $|t_{\text{now}} - \text{envelope.timestamp}| \le 300\,000\text{ ms}$ ($\pm 5\text{ minutes}$) for live frames.
2. **64-Packet Sliding Sequence Bitmask (`ReplayWindow`):**
   - Tracks `highestSeq: Long` (initialized to `0` per session) and `windowMask: ULong` (64-bit bitmask of seen sequence numbers in `[highestSeq - 63 .. highestSeq]`).
   - Reject if `seq <= 0`.
   - If `seq > highestSeq`: shift `windowMask` left by `(seq - highestSeq)` (or zero it if delta $\ge 64$), set bit `0`, and update `highestSeq = seq`.
   - If `highestSeq - seq < 64`: check bit `offset = (highestSeq - seq).toInt()`. If bit is already `1`, reject as duplicate replay; otherwise set bit to `1`.
   - If `highestSeq - seq >= 64`: reject as too old (`seq` fell behind the 64-packet window).
3. **Normative Offline-Flush Cross-Session Replay Defense (`OfflineFlushDeduplicator`):**
   Because a fresh session resets `ReplayWindow` (`highestSeq = 0`), offline-queued envelopes flushed after a reconnect MUST NOT rely solely on `ReplayWindow`:
   - **Outer Envelope:** An `"offline_flush"` command is itself a live `Ec0Envelope` signed in the **current** session (validated by the live `±300 s` window and the current session's `ReplayWindow`), carrying a strictly increasing per-session `batch_seq: Long` (`1, 2, ...`) and an array `items` of original signed `Ec0Envelope` objects.
   - **Inner Queued Envelopes:** Each inner `Ec0Envelope` retains its original creation `timestamp`, `seq`, `nonce`, and `Ed25519` `sig`. The receiver verifies:
     1. The inner `Ed25519` signature is valid over `ec0-sig-v1` using the peer's pinned `Ed25519` public key.
     2. $|t_{\text{now}} - \text{inner.timestamp}| \le 3\,600\,000\text{ ms}$ (1-hour maximum offline TTL).
     3. Inside a single batch, inner `seq` values are strictly increasing.
     4. **Normative Content-ID & Origin-Nonce Deduplication:** The receiver maintains a persistent/cross-session bounded LRU cache (`maxEntries = 512`) keyed by both the envelope identity `(peerDeviceId, inner.timestamp, inner.nonce)` **and** the domain content identifier (`clip_sha256` for `"clipboard_sync"`, `notif_id` + `ts_ms` for `"notif_post"`, `transfer_id` for `"file_offer"`). Any inner item matching an already-seen `(peerDeviceId, timestamp, nonce)` or content ID is silently dropped as a cross-session duplicate.

---

## 5. Discovery Specification (`_ec0._tcp.local.`)

### 5.1 DNS-SD Record Set (RFC 6762 / RFC 6763)
Each `ec0-engine` node announces and queries `_ec0._tcp.local.` on UDP multicast `224.0.0.251:5353`:
- **PTR Record:** `_ec0._tcp.local.` → `<shortId>._ec0._tcp.local.` (TTL = `120 s`, or `0 s` on graceful shutdown goodbye packet), where `<shortId>` is the first 16 hex chars of `deviceId`.
- **SRV Record:** `<shortId>._ec0._tcp.local.` → priority `0`, weight `0`, port `<listeningPort>`, target `<shortId>.local.`.
- **A / AAAA Records:** `<shortId>.local.` → host LAN IPv4 / IPv6 addresses.
- **TXT Record (`<shortId>._ec0._tcp.local.`):** Length-prefixed `key=value` ASCII strings:
  - `v=1` (protocol version)
  - `id=<64-hex-deviceId>`
  - `name=<UTF-8 device alias, max 32 bytes>`
  - `type=<desktop|mobile|tablet|headless>`
  - `caps=<comma-separated: clip,file,input,notif,battery,mirror>`
  - `fp=<first-16-hex-of-deviceId>`

### 5.2 Burst Announce & Endpoint Fallback Order
1. **Burst Announce Schedule:** On startup or network change, emit 3 mDNS announcements at `0 ms`, `250 ms`, and `1000 ms` (mirrors RFC 6762 §8.3 and the proven burst pattern in T13/T14).
2. **`EndpointResolver` Resolution Order:**
   When connecting to a peer `deviceId`:
   1. Live mDNS cache entry (not expired by TTL) at its SRV-advertised `ip:port`.
   2. Live mDNS IP with port walk `DEFAULT_PORT_LIST = [49880, 39888, 49888, 59888]`.
   3. Last-known persisted IP for `deviceId` with port walk `DEFAULT_PORT_LIST`.
   4. `GATEWAY_FALLBACK_HOSTS` (`192.168.137.1`, `192.168.49.1`, `192.168.43.1`) with port walk `DEFAULT_PORT_LIST` (ensuring instant connection when the phone is acting as a SoftAP hotspot or Wi-Fi Direct group owner even if multicast UDP is dropped).

---

## 6. Service Specifications (Slices 4–6)

### 6.1 Multiplexed Bulk Binary Chunk Wire Format (`frameType = 0x03`)
Large payloads (files and images $> 64\text{ KiB}$) travel as AEAD-encrypted `BULK_CHUNK` (`0x03`) binary frames so JSON serialization never Base64-bloats multi-gigabyte files:

```text
┌──────────────┬──────────────┬───────────┬──────────────────┬─────────────────────────┐
│ streamId(4B) │ chunkIdx(4B) │ flags(1B) │ chunkSha256(32B) │ payload (0..262,144 B)  │
└──────────────┴──────────────┴───────────┴──────────────────┴─────────────────────────┘
  Big-endian     Big-endian     Bit 0: EOF   Raw 32-byte        Max 256 KiB per chunk
  Int32          Int32          Bit 1: ERR   SHA-256 of payload
```

### 6.2 Service 1 — Clipboard Sync (`category = "clipboard"`)
- **Commands (`cmd_type`):**
  - `"clipboard_sync"`:
    - `cmd_body`: `{"clip_id":"<uuid>","clip_sha256":"<hex64>","inline_b64u":"<optional>","mime":"text/plain|image/png|image/jpeg","size_bytes":1234,"stream_id":0}`
    - Payloads $\le 64\text{ KiB}$ are carried in `inline_b64u`; payloads $> 64\text{ KiB}$ up to `maxSizeBytes` (default `16 MiB`) set `stream_id > 0` and stream via `BULK_CHUNK` frames.
- **Loopback Deduplication:**
  - Each node stores `lastAppliedIncomingSha256: String?`. When the local OS clipboard listener fires, if `sha256(currentClipboardBytes) == lastAppliedIncomingSha256`, the event is suppressed so A → B never echoes B → A.
- **Privacy Controls:**
  - Per-peer `privateMode: Boolean` (when enabled, outgoing clipboard auto-push is paused; manual push still works).
  - Sensitive flag `is_sensitive: Boolean` (mapped from Android `ClipDescription.EXTRA_IS_SENSITIVE`): never synced when true.

### 6.3 Service 2 — Resumable File & Folder Transfer (`category = "file"`)
- **Chunk Size:** `262_144` bytes (`256 KiB`).
- **4-Stage Protocol:**
  1. **`"file_offer"` (Sender → Receiver):**
     ```json
     {
       "chunk_size": 262144,
       "files": [
         {
           "file_id": "f0",
           "last_modified_ms": 1763000000000,
           "rel_path": "photos/trip/img01.jpg",
           "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
           "size_bytes": 1048576
         }
       ],
       "total_bytes": 1048576,
       "transfer_id": "tx-001"
     }
     ```
  2. **`"file_accept"` (Receiver → Sender):**
     Receiver validates every `rel_path` (rejecting any path containing `..`, leading `/`, `\`, or drive letters), checks existing `.ec0part.json` resume manifests on disk, and replies:
     ```json
     {
       "accepted": true,
       "resume_chunks": { "f0": 2 },
       "stream_ids": { "f0": 101 },
       "transfer_id": "tx-001"
     }
     ```
     Where `resume_chunks["f0"] = 2` means chunks `0` and `1` are already verified on disk; Sender starts streaming at `chunkIdx = 2`!
  3. **`BULK_CHUNK` Streaming:**
     Sender transmits chunks `resumeChunk .. totalChunks - 1` on `streamId = 101`. Receiver verifies `SHA-256(payload) == chunkSha256` on every chunk before writing to `<dest>.ec0part` at `offset = chunkIdx * 262_144L` and updating `<dest>.ec0part.json`.
  4. **`"file_complete"` / `"file_cancel"`:**
     - Upon receiving the final chunk (`flags & 0x01 != 0`), Receiver verifies the whole-file `SHA-256`, atomically renames `<dest>.ec0part` → `<dest>`, deletes `<dest>.ec0part.json`, and emits `info_type = "file_status"` (`status = "DONE"`).
     - Either peer can send `cmd_type = "file_cancel"` (`{"reason":"user_cancelled","transfer_id":"tx-001"}`) at any point; the sender aborts its chunk loop before the next 256 KiB chunk and transitions the transfer to `CANCELLED` (guarded by `FINISHED_STATES` so a late ack can never flip `CANCELLED` → `DONE`).

### 6.4 Service 3 — Input Share (`category = "input"`)
- **`"input_key"`:** `{"action":"down|up|text","key_code":65,"modifiers":1,"text":""}`
- **`"input_pointer"`:** `{"buttons":1,"dx":12,"dy":-4,"mode":"relative|touchpad","scroll_x":0,"scroll_y":0}`
- **Linux & Android Execution Seams:**
  - Desktop → Android: Dispatched through `PhoneInputSink` (implemented on Android via `ec0` InputMethodService / AccessibilityGesture or on desktop via wireless/USB ADB shell input fallback).
  - Android Touchpad → Linux Desktop: Dispatched through `DesktopInputSink` (implemented on Linux JVM via `java.awt.Robot` / `uinput` adapter).

### 6.5 Service 4 — Notification Relay (`category = "notif"`)
- **`"notif_post"` (Info):** `{"actions":["Reply","Mark Read"],"app_name":"Messages","can_reply":true,"notif_id":"n-42","pkg":"com.google.android.apps.messaging","text":"On my way","title":"Alice","ts_ms":1763000000000}`
- **`"notif_dismiss"` (Cmd/Info):** `{"notif_id":"n-42"}` (bidirectional: dismissing on Linux desktop dismisses on Android via `NotificationListenerService.cancelNotification(key)`).
- **`"notif_reply"` (Cmd, Desktop → Phone):** `{"action":"Reply","notif_id":"n-42","reply_text":"See you in 5!"}` (invokes Android `PendingIntent` with `RemoteInput`).

### 6.6 Service 5 — Battery & Presence Beacons (`category = "presence"`)
- **`"presence_beacon"` (Info, every 30 s or on state change):**
  `{"active_caps":["clip","file","input","notif","battery","mirror"],"battery_pct":84,"charging":true,"device_name":"OPPO-A5x","screen_on":true}`

### 6.7 Service 6 (Stretch) — `scrcpy` Screen Mirroring Hook (`category = "mirror"`)
- **`"mirror_start"` / `"mirror_ready"` / `"mirror_stop"`:**
  1. Desktop sends `"mirror_start"` (`{"bitrate_kbps":8000,"max_fps":60,"max_size":1920}`).
  2. Phone replies `"mirror_ready"` (`{"adb_port":5555,"mode":"adb_tcp"}`).
  3. **Normative `adb connect` Prerequisite:** Before launching `scrcpy`, `ScrcpyMirrorCoordinator` MUST first invoke `adb connect <host>:<adb_port>` via `ProcessLauncher` and verify that the command exits with `0` and stdout contains `"connected to"` or `"already connected to"` (ensuring the wireless ADB transport is established in the local ADB server).
  4. `ScrcpyMirrorCoordinator` then launches the external `scrcpy` binary via `ProcessLauncher`:
     `scrcpy --serial <host>:<adb_port> --window-title <alias> --max-size 1920 --max-fps 60 --video-bit-rate 8M`
     keeping `scrcpy` cleanly isolated as an unmodified Apache-2.0 binary (`README.md` §License). On `"mirror_stop"` or session teardown, the coordinator terminates the `scrcpy` child process.

---

## 7. Threat Model & Security Invariants

| Threat | Mitigation in `ec0-engine` |
|---|---|
| **Active LAN MITM during connection** | Ephemeral `X25519` ECDH signed by pinned `Ed25519` keys over both peers' fresh 128-bit nonces (`nonce_I`, `nonce_R`). Static OEM cert impersonation is impossible. |
| **Pre-play / Reflection Attack** | 14-byte role-separated ASCII tags (`"ec0-hs-resp-v1"` vs `"ec0-hs-init-v1"`) over the 6-tuple `(ik_pub, rk_pub, epk_I, epk_R, nonce_I, nonce_R)`. |
| **Envelope Replay / Reordering** | Dual defense: (1) AEAD strict 64-bit monotonic per-frame counter `0, 1, 2...` on the live socket, and (2) `Ec0Envelope` `Ed25519` signature + `ReplayWindow` (`±300 s` timestamp check + 64-entry `seq` bitmask). |
| **JSON Canonicalization Ambiguity** | Strict `CanonicalJson` encoder: recursive UTF-16 key sort, zero whitespace, null elision, and **integer-only numbers** (`Long.toString()`), rejecting floats before signing. |
| **Path Traversal on File Receive** | Receiver rejects any `rel_path` containing `..`, leading `/`, `\`, NUL bytes, or resolving outside `downloadDir.toRealPath()`. |
| **Memory Exhaustion (DoS)** | Max WebSocket frame size capped at `512 KiB` (`BULK_CHUNK` max payload = `256 KiB` + `41 B` header + `16 B` tag); max envelope JSON capped at `128 KiB`. |
| **Silent Key Substitution** | `PinStore` enforces strict TOFU: a changed `Ed25519` public key for a known `deviceId` throws `KeyMismatchException` and requires explicit user unpair. |

---

## 8. Verified Cryptographic Test Vectors (Pinned for Slice 1 `commonTest` / `jvmTest`)

All vectors below were independently computed and cross-verified using Node.js OpenSSL (`crypto`) and Python `hashlib`/`hmac` prior to specification freeze.

### 8.1 RFC 8032 §7.1 Ed25519 Vectors & ASN.1 Wrapping
- **Ed25519 SPKI Header (12 bytes):** `302a300506032b6570032100`
- **Ed25519 PKCS#8 Header (16 bytes):** `302e020100300506032b657004220420`
- **TEST 1 (Initiator Keypair in Golden Vector below):**
  - `sk_raw` (32B): `9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60`
  - `pk_raw` (32B): `d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a`
  - `deviceId = sha256(pk_raw)`: `21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9`
  - `sig(b"")` (64B): `e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b`
- **TEST 2 (Responder Keypair in Golden Vector below):**
  - `sk_raw` (32B): `4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb`
  - `pk_raw` (32B): `3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c`
  - `sig(0x72)` (64B): `92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00`

### 8.2 RFC 7748 §6.1 X25519 Vectors & ASN.1 Wrapping
- **X25519 SPKI Header (12 bytes):** `302a300506032b656e032100`
- **X25519 PKCS#8 Header (16 bytes):** `302e020100300506032b656e04220420`
- **Alice (Initiator Ephemeral):**
  - `esk_I_raw` (32B): `77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a`
  - `epk_I_raw` (32B): `8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a`
- **Bob (Responder Ephemeral):**
  - `esk_R_raw` (32B): `5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb`
  - `epk_R_raw` (32B): `de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f`
- **Shared Secret $Z$ (32B):** `4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742`

### 8.3 Golden Vector 1 — Mutual Handshake & `HKDF-SHA256` Session Key Derivation
Using Initiator = Ed25519 TEST 1 + X25519 Alice, Responder = Ed25519 TEST 2 + X25519 Bob, and nonces:
- `nonce_I` (16B): `0102030405060708090a0b0c0d0e0f10`
- `nonce_R` (16B): `1112131415161718191a1b1c1d1e1f20`
- `SHA-256(respPreimage)` (174B): `2ad3499d109a804cf60d653f09515d52cd8e38e7cf2653ddb758448256a8893e`
- `respSig` (64B hex): `1065cf7cd7b6905a4b461175432ebfd4b2a8a53e1e8c018b049a894ef007b428183cc1900900ce9f470251a9e363cd5acdcb327dfd67449d192cddb1cf866609`
- `SHA-256(initPreimage)` (174B): `014ba89f9f009fc6a39bfa5381927e71a5dcb172caeb922c944988024e10d5a0`
- `initSig` (64B hex): `0022b4c1f95eedc9fc092d80624cd5466ce904ff6641357d116865777a3acf858b0f590dc9f45df7e1ec9ae2cc5e76b211267c92cbedd734fff7a3ba3f0d010a`
- `HKDF-Extract PRK` (32B hex): `35da37f9a644ebcce158e3dc4f9f6d176af0e41768c7e31928fb23ababac5fba`
- `kInitToResp` ($K_{I\to R}$, 32B hex): `97a94be57fa3709c7b899753e653ab6a564b54e7719aafeaadcd20aee39ecfdb`
- `kRespToInit` ($K_{R\to I}$, 32B hex): `a0018bca1e9f43a43188de462e269db08cd283a5c2bfe8f5c80a3367102aae3b`
- `sessionId` (32B hex): `40ba833dce3c130e47879cce64bbd4f6fd40cf1708c80ce14141b7091f7ac647`
- **Ephemeral-Bound `pairing_proof` Vector:**
  - Given `nonce_qr` (16B hex) = `aabbccddeeff00112233445566778899`
  - Input (`107` bytes) = `"ec0-pair-v1" (11B ASCII) || ik_pub (32B) || rk_pub (32B) || epk_I (32B)`
  - `pairing_proof = HMAC-SHA256(nonce_qr, input)` (32B hex): `b2fed94557974e200549b98fa5e027d6b8712c8813bced6d0c6339e1c6d04d38`

### 8.4 Golden Vector 2 — `ec0-sig-v1` Canonical Envelope Signing
Given an `Ec0Envelope` signed with Initiator key (`Ed25519` TEST 1):
- `msg_type` = `"cmd"`
- `src_role` = `"ec0-engine"`
- `category` = `"clipboard"`
- `timestamp` = `1763000000000L`
- `seq` = `1L`
- `nonce` = `4829104728193048291L`
- `msg_body` (canonicalized with keys sorted recursively in UTF-16 order and integer numbers):
  ```json
  {"cmd_body":{"clip_sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","mime":"text/plain","size_bytes":3},"cmd_type":"clipboard_sync","cmd_value":0,"reason":""}
  ```
- **Exact 8-Line Preimage (`256` UTF-8 bytes):**
  ```text
  ec0-sig-v1
  cmd
  ec0-engine
  clipboard
  1763000000000
  1
  4829104728193048291
  {"cmd_body":{"clip_sha256":"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","mime":"text/plain","size_bytes":3},"cmd_type":"clipboard_sync","cmd_value":0,"reason":""}
  ```
- **`SHA-256(preimage)`:** `92ef942b467b87625e298a6047cdc851a810118faf05b509d3f3512108885ced`
- **`Ed25519` Signature (hex, 64B):** `947ab26a2f7feb396b35ee12c53f585f3b6c49e5705f92494e115e883e76b8d5a54d0a868f1e5c0b9e9a2b2396a80643fd2198c3dcbfbdcf7ad97d3932a2c307`
- **`Ed25519` Signature (`sig` wire field, Base64url unpadded):** `lHqyai9_6zlrNe4SxT9YXztsSeVwX5JJThFeiD52uNWlTQqGjx5cC56aKyOWqAZD_SGYw9y_vc962X05MqLDBw`
