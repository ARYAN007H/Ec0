# KDE Connect protocol core — port notes (T6)

Source: vendored clone `/home/aryan/Downloads/ec0/android` (kdeconnect-android,
commit `501aee7`, READ-ONLY). Target: `core/src/commonMain/kotlin/ec0/core/kdeconnect/`.
License: upstream is `GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL`;
ec0 takes the **GPL-3.0-only** option (whole-app license GPL-3.0 — compatible).
Every ported file carries an attribution header.

## What was ported → where

| Upstream (org.kde.kdeconnect) | ec0.core.kdeconnect | Notes |
|---|---|---|
| `NetworkPacket.kt` | `NetworkPacket.kt` | Wire format 1:1 (see below) |
| `DeviceInfo.kt` + `DeviceHelper.kt` name filter | `IdentityPacket.kt` | Identity packet + device-id regex + name sanitizer |
| `PairingHandler.kt` (model parts) | `Pairing.kt` | Pair packets, states, verification keys, timeout constants |
| `backends/lan/LanLinkProvider.kt` constants/contract | `Discovery.kt` | Ports, sizes, handshake contract (sockets stay platform-side) |
| `helpers/security/SslHelper.kt` hash formats | `Sha256.kt` (hex helpers) | `getCertificateHash` "%02x:" format preserved |
| — | `Sha256.kt` | Pure-Kotlin SHA-256 (commonMain has no platform crypto) |

## Wire format (verified 1:1)

Packet = `{"id":<epoch ms>,"type":"...","body":{...}}` + `\n` terminator;
`payloadSize`/`payloadTransferInfo` keys appear **only** when a payload is
present (`@EncodeDefault(NEVER)`; upstream peers check key presence).

Evidence (upstream file → behavior):
- `NetworkPacket.kt:200-214` serialize(): id = `System.currentTimeMillis()`,
  trailing `\n`, payload fields only when `hasPayload()`.
- `NetworkPacket.kt:285-295` unserialize(): missing `type` → SerializationException;
  `payloadSize` without `payloadTransferInfo` → SerializationException.
- `helpers/JsonHelper.kt`: `ignoreUnknownKeys=true, encodeDefaults=true,
  maxNestingDepth=64` — mirrored in `NetworkPacket.json`.
- `NetworkPacketTest.kt` fixtures used verbatim: type coercion (`"12"`→12,
  `"true"`→true), `{"extra":0}` ignored, `{"body":{}}` invalid.
- `DeviceInfo.kt:58-66` identity body keys: `deviceId, deviceName,
  protocolVersion, deviceType, incomingCapabilities, outgoingCapabilities`.
- `DeviceInfo.kt:116` device-id regex `^[a-zA-Z0-9_-]{32,38}$`;
  `DeviceHelper.kt:41-42` name filter `["',;:.!?()\[\]<>]` + trim + max 32.
- `DeviceHelper.kt:31` **`PROTOCOL_VERSION = 8`** — ec0's `DeviceIdentity`
  defaults to it (review P1: defaulting to 0 would push peers onto the legacy
  pre-v8 pairing path — PairingHandler.kt:71-85/:119-129).
- `PairingHandler.kt:169-173` pair-request body `{"pair":true,"timestamp":<epoch s>}`;
  `:197-199` accept body `{"pair":true}`; `:232-234` unpair `{"pair":false}`
  (`:202-213` is cancelPairing — same packet shape, different lifecycle);
  clock skew ±1800 s gated on protocol ≥ 8 (`:71-85`); timeouts: 30 s requester
  timer (`:153`), 25 s incoming-request timer (`:90`).
- `PairingHandler.kt:246-271` verification key: SHA-256 over
  `sortedConcat(pubA, pubB) [+ timestamp-as-text]`, lowercase hex, first 8
  chars uppercased.
- `LanLinkProvider.kt:664-671` constants: UDP 1716, MIN 1716, MAX 1764,
  payload-transfer min 1739, 512 KiB identity/UDP caps, 1000 ms same-device
  reconnect delay; `:247-255` tcpPort range check + TCP connect; `:539` tcpPort
  added to broadcast identity; `:541-543` the UDP datagram is
  `identity.serialize()` bytes — **including the trailing newline**;
  `:255-262` TCP identity additionally carries `targetDeviceId` + the peer's
  `protocolVersion` echoed as `targetProtocolVersion` (string, validated by
  the listener at `:180-187`).

## Interop test vectors (the hard guarantee)

Upstream `PairingHandlerTest.kt` pins the verification key over two REAL EC
P-256 certificates. `PairingTest.kt` hardcodes the **SPKI DER bytes** of those
certificates (extracted with JVM `java.security` — /tmp single-file tool, not
committed) plus upstream's expected outputs:

- `getVerificationKey(spkiA, spkiB, 1737228658) == "54DC916E"` (order-symmetric)
- `getVerificationKey(spkiA, spkiB, 2737228658) == "8C07153A"`
- `getVerificationKeyV7(spkiA, spkiB) == "F3900DB5"`; `(spkiA, spkiA) == "97A75917"`

These fail if the pure-Kotlin SHA-256, the unsigned-byte `sortedConcat`, or the
hash formatting drifts from upstream. `Sha256Test.kt` additionally pins NIST
FIPS 180-4 vectors (empty / abc / 448-bit / 896-bit) independently of pairing.

## Deviations from upstream (all deliberate, reviewed in PR)

1. **Payload is data-only.** Upstream `NetworkPacket.Payload` wraps
   `java.io.InputStream`/`java.net.Socket`; commonMain has neither. ec0's
   `Payload(payloadSize)` carries the size; stream plumbing moves to the
   transport layer (ktor-network already on core's classpath).
2. **Clock injection.** Upstream hardcodes `System.currentTimeMillis()` as the
   envelope id. ec0 exposes `NetworkPacket.idProvider` (platform layers set it
   to wall-clock); default is a monotonic process-lifetime counter so ids still
   advance if unset. Tests set it explicitly.
3. **`envelopeId` exposed.** Parsed packets keep the envelope `id` (upstream
   discards it). Additive; body accessors still don't see it (upstream
   semantics preserved and tested).
4. **Capabilities/tcpPort null-safety.** Upstream `toIdentityPacket()` uses
   `incomingCapabilities!!` (NPEs on null). ec0 omits null fields instead —
   wire-equivalent for the populated case, no crash path.
5. **Protocol version default.** Upstream `DeviceInfo.protocolVersion = 0` is
   a persistence placeholder (filled from settings before use); ec0 defaults
   to `DeviceHelper.PROTOCOL_VERSION` (8) per review P1 so identities never
   advertise the legacy pairing path.
6. **No pairing coroutine handler.** `PairingHandler`'s state machine needs
   Device/callback/coroutine context (Android-flavored). ec0 ports the pure
   model (packets, states, constants, key derivation); the driven state
   machine lands with the transport integration task.
7. **No X.509 in commonMain.** Cert generation/parsing (bouncycastle,
   `SHA512withRSA`, CN=deviceId, OU="KDE Connect", O="KDE", −1 y/+10 y
   validity, `SslHelper.kt:100-146`) stays platform-side. commonMain only
   hashes raw bytes (SPKI/cert DER) — which is all the protocol math needs.
8. **No decode-depth guard yet.** Upstream's JSON config sets
   `maxNestingDepth = 64`; the serialization library version pinned in this
   repo's catalog (1.7.3) predates that knob — re-add when bumped.

## Not ported (explicitly out of scope for T6)

- TLS socket setup (`SslHelper.convertToSslSocket`, trusted/untrusted SSL
  contexts) — transport layer.
- UDP broadcast loop, TCP listener accept loop, rate limiting implementation
  (`Discovery.kt` documents the contract; ktor-network wiring lands later).
- Plugin capability strings beyond carrying them opaquely in identity packets.
- `NetworkPacket.isCanceled`/`cancel()` — upstream cancellation flag used by
  payload-stream consumers; meaningless without stream plumbing (returns with
  the transport layer).
- `backends/lan/MdnsDiscovery.kt` — upstream's mDNS advertising backend
  (complements the UDP broadcast discovery); a separate transport-layer task.
