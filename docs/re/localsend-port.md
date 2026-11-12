# LocalSend v2 protocol port — methodology & evidence

Task: T5 · Agent: agent-1 · 2026-11-12
Target: `core/src/commonMain/kotlin/ec0/core/localsend/**` (+ commonTest)
Source: vendored clone `/home/aryan/Downloads/ec0/localsend-upstream` (LocalSend
app version 1.18.2; READ-ONLY reference — nothing copied verbatim, protocol
shapes re-declared in Kotlin).

## Which reference layer is the wire contract

The 1.18 clone has THREE protocol-touching layers:

1. **Rust core (`packages/core/src/`)** — owns the actual wire: serde DTOs,
   UDP multicast, HTTP server/client, TLS pinning. The Dart app calls into it
   through flutter_rust_bridge (`packages/localsend_isolates/lib/rust/api/`).
2. **Dart bridge DTOs (`packages/localsend_isolates/lib/model/dto/`)** —
   flutter_rust_bridge side types. NOT the wire contract: e.g. their FileDto
   uses `hash`/`lastModified`/`lastAccessed`, diverging from the Rust core's
   `sha256`/`metadata.modified`/`metadata.accessed`. These cross the
   Dart↔Rust bridge, not the network.
3. Legacy `app/lib` protocol code — not present anymore in this layout.

**Decision: the Rust core's serde shapes are authoritative for this port**
(they define what bytes hit the network), with the official protocol
semantics documented in the Rust doc-comments (e.g. §7.1 fallback).

## Ported surface (file → reference evidence)

| Kotlin | Reference (packages/core/src/...) | Notes |
|---|---|---|
| `LocalsendV2` consts + shared Json | `model/discovery.rs` (PROTOCOL_VERSION_V2="2.2"), `http/server/mod.rs` route table | Json config mirrors serde: ignoreUnknownKeys, explicitNulls=false (skip_if_none), encodeDefaults=true (`#[serde(default)]` fields always emitted) |
| `DeviceType` + lenient serializer | `model/discovery.rs` `device_type_v2` | lowercase wire; unknown → DESKTOP (§7.1, covered by reference test "fridge") |
| `ProtocolType` | `model/discovery.rs` `protocol_type_v2` | strict decode — unknown throws (matches Rust) |
| `FileDto` + `FileMetadata` | `model/transfer.rs` | camelCase; sha256/preview/metadata optional+skipped |
| `RegisterDtoV2` / `RegisterResponseDtoV2` / `InfoResponseDtoV2` | `http/dto_v2.rs` | response echoes no port/protocol; fingerprint defaults "" but always serialized |
| `MulticastMessageV2` | `model/discovery.rs` | UDP :53317 announce (never a response) |
| `PrepareUploadRequestDtoV2` / `...ResponseDtoV2` / `PrepareDownloadResponseDtoV2` | `http/dto_v2.rs` | files maps keyed by file ID |
| `LocalsendV2Route` | `http/server/mod.rs:631-676` + `http/client/v2.rs` | 7 routes: register, prepare-upload(pin?), upload(sessionId,fileId,token), cancel(sessionId), prepare-download(sessionId?,pin?), download(sessionId,fileId), info |
| `LocalsendSecurity` (PIN + fingerprint + token model) | `http/server/common/pin.rs`, `model/discovery.rs` fingerprint docs, `server/v2.rs` token paths | PIN via query param, 3 attempts/IP → 429, wrong PIN → **401** (403 = recipient decline); fingerprint = cert SHA-256 (HTTPS) / random (HTTP); **file tokens = plain UUIDs, equality-compared** (see below) |

## Token model (corrected after review)

The v2 HTTP **file tokens are plain random UUIDs**: prepare-upload creates
`SessionFileV2 { token: Uuid::new_v4().to_string() }` (`server/v2.rs:326`) and
upload verifies by equality + pending state (`file.token != *token` —
`server/v2.rs:400`). There is **no signing and no crypto** in the v2 HTTP
token path. `crypto/token.rs` (signed timestamp+nonce tokens) is
**WebRTC-signaling-only** — its sole caller is `webrtc/webrtc.rs`. An earlier
revision of this doc attributed signed tokens to the HTTP path; that was a
misattribution caught in review (implementers following it would
signature-verify upload tokens and reject every real peer). ec0 treats tokens
as opaque strings — the correct call.

## Status-code contract (per reference `server/v2.rs`)

- prepare-upload: wrong/missing PIN → **401** (`check_pin`); recipient
  declines → **403** "Rejected"; sender cancels during decision → **403**;
  all files filtered out (nothing accepted) → **204 no-content**; otherwise
  **200** with `PrepareUploadResponseDtoV2`.
- upload: success → **empty 200** (no body, no text status).
- PIN attempts beyond the 3-per-IP limit → **429** ("Too many requests",
  `pin.rs`).
- The route table also carries `POST /api/localsend/v2/show`
  (`server/mod.rs:676`, internal show-window route) — noted for completeness;
  not part of ec0's ported surface yet.

## Version caveat

Reference implements **v2.2** (`PROTOCOL_VERSION_V2`). The vendored app is
1.18.2 and speaks 2.2 through the Rust core; the ≤1.17 releases speak older
2.x variants on the wire. The DTO shapes above parse 2.0/2.1 JSON as well
(leniency on the wire: `deviceType` is lenient with the §7.1 fallback —
**only `protocol` is strict**). The exact wire-shape boundary where
`sha256`/`metadata` entered FileDto is not provable from this clone — the
in-repo CHANGELOG only documents the v2 introduction itself
("implement LocalSend protocol v2 with v1 fallback", CHANGELOG.md:275); check
the official protocol-spec history before making compat claims about ≤1.17
peers.

## Deliberately NOT ported (out of T5 scope)

- Actual HTTP server/client wiring (ktor) — integration lands in the desktop/
  phone PRs; the route table is declared as data for those PRs.
- Crypto/TLS: certificate generation, SHA-256 file hashing, and WebRTC
  signaling (the signed-token machinery in `crypto/token.rs` belongs to
  WebRTC signaling, not the v2 HTTP transfer path — see the corrected token
  model above). File tokens stay plain opaque UUID strings.

## Validation

- Golden-fixture roundtrip tests in `core/src/commonTest/.../LocalsendV2DtoTest.kt`
  use the reference implementation's own test JSONs (Secret Banana / Nice
  Orange fixtures from dto_v2.rs + discovery.rs test modules) plus
  serialization-exactness assertions (null-skipping, download always present).
- Gate: CI green (`gradle build` runs jvmTest incl. these) + independent review.
