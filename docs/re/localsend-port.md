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
| `LocalsendV2Route` | `http/server/mod.rs:631-676` + `http/client/v2.rs` | 7 routes: register, prepare-upload, upload(sessionId,fileId,token), cancel(sessionId), prepare-download(sessionId,pin?), download(sessionId,fileId), info |
| `LocalsendSecurity` (PIN + fingerprint + token model) | `http/server/common/pin.rs`, `model/discovery.rs` fingerprint docs, `crypto/token.rs` | PIN via query param, 3 attempts/IP → 429; fingerprint = cert SHA-256 (HTTPS) / random (HTTP); v2.2 tokens signed — opaque strings here |

## Version caveat

Reference implements **v2.2** (`PROTOCOL_VERSION_V2`). LocalSend 1.x releases
speak 2.0/2.1 on the wire (e.g. plain file tokens, no signed-token verification).
The DTO shapes above are compatible with 2.0/2.1 JSON except: `metadata`/
`sha256` are v2.1+ FileDto fields, and older peers may send `files` tokens
without signatures. ec0's client/server layers (integration PRs) must send
`version: "2.2"` but accept peers declaring "2.0"/"2.1" — the DTO set here
parses all three (strict only on `protocol`/`deviceType` per reference).

## Deliberately NOT ported (out of T5 scope)

- Actual HTTP server/client wiring (ktor) — integration lands in the desktop/
  phone PRs; the route table is declared as data for those PRs.
- Crypto: token signing (ed25519), TLS certificate generation, SHA-256 file
  hashing. Tokens stay opaque strings; the security model documents the
  semantics with file:line evidence for the implementer.

## Validation

- Golden-fixture roundtrip tests in `core/src/commonTest/.../LocalsendV2DtoTest.kt`
  use the reference implementation's own test JSONs (Secret Banana / Nice
  Orange fixtures from dto_v2.rs + discovery.rs test modules) plus
  serialization-exactness assertions (null-skipping, download always present).
- Gate: CI green (`gradle build` runs jvmTest incl. these) + independent review.
