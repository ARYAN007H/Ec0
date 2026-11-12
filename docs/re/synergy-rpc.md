# Synergy gRPC service clients — design notes (T12)

Code: `core/src/jvmMain/kotlin/ec0/core/synergy/rpc/` — tests in
`core/src/jvmTest/` (fake channel). Sources: agent-1's T1 proto
reconstructions (reviewer-verified against embedded FileDescriptorProto
blobs, PR #2 rev.3) regenerated as `:synergy-proto` stubs (grpc-kotlin 1.4.1
coroutine stubs), and the T2 catalog. Package-less wire paths
(`/ClipBoardService/copyToClip` etc.) are guaranteed by the package-less
protos + bare SERVICE_NAMEs (T1 rev.3).

## Surface

| Adapter | Methods (wire shape) |
|---|---|
| `ClipboardClient` | `copyToClip(stream ClipData) → Result` (client-stream), `getClipData(Type) → stream ClipData` (server-stream); convenience `copyToClipText` |
| `ClipboardFileClient` | `getUriContent(stream Uri) → stream UriContent` (bidi); convenience `uriContent` |
| `FileClient` | `createFile`/`deleteFile`/`openFile` (unary FileInfo → FileResult), `readFile` (server-stream FileData), `writeFile` (bidi FileData → FileStatus); conveniences `readFileBytes`, `writeBytes` |
| `InputClient` | `inputToTextbox(stream InputData) → InputResult` (client-stream); convenience `sendText` |

Adapters are **thin**: they construct the generated `*CoroutineStub` over an
injected `io.grpc.Channel` and expose the raw streams — every upstream-unknown
(`ClipData.feature` codes, `Result.status` values, `FileData.flag` chunk
semantics, `InputData` payload encoding) passes through verbatim with the
unknown documented in code.

## Fake-channel testing

`FakeGrpcChannel` (jvmTest) implements `io.grpc.Channel`/`ClientCall`
in-memory: requests captured via `sendMessage`, `halfClose` triggers the
test's `FakeRpcBehavior`, responses drain through `request(n)` flow-control
shaping, terminal status via `onClose`. The **real generated stubs** run
against it — adapters are exercised through genuine grpc-core client-call
machinery (including flow-control ordering and error propagation as
`StatusRuntimeException`), with zero sockets. `sentRequests` allows
wire-content assertions (UTF-8 payloads, sint64 roundtrips).

## Real-channel wiring (deferred, not forgotten)

grpc-java needs a transport artifact (okhttp/netty) for `ManagedChannel` —
deliberately **not** on core's classpath (no gradle edits; lands with the
desktop module, T13). Until then:

- `SynergyRpcEndpoint.resolve(profile, host)` walks the profile's ports
  (T10 `SynergyPortWalk`) and TLS-handshake-probes each with the profile's
  pinned trust (T10 `SynergyTrust`) — the returned (host, port) is the
  ManagedChannel target.
- The ManagedChannel must be configured with the same pinned trust
  (`SynergyTrust.pinnedTrustManager(profile)` + `endpointIdentification =
  null` analog) — okhttp builder: `OkHttpChannelBuilder.forAddress(host,
  port).sslSocketFactory(...)`; netty: `NettyChannelBuilder...sslContext()`.
  Whatever transport lands, the adapters don't change: they take `Channel`.

## Unknowns → T4

1. `ClipData.feature` format codes; `Result.status`/`FileResult.status`/
   `FileStatus.status` code tables.
2. `FileData.flag` chunk semantics (EOF marker? ordering?).
3. `InputData.data` payload encoding (raw UTF-8 vs serialized key events).
4. Which transport these gRPC calls actually ride in the real system
   (dedicated HTTP/2 port vs tunneled through the WS control channel) —
   capture will settle it; the `Channel`-injected design is agnostic.
