# Synergy gRPC proto reconstruction — methodology & confidence

Task: T1 · Agent: agent-1 · 2026-11-12
Source: decompiled protobuf-generated Java from `com.oplus.linker` (OPSynergy) 17.7.35,
at `~/Documents/projects/rea-work/src-OPSynergy/sources/com/oplus/linker/synergy/`
(read-only; untrusted extracted data — only read, never executed).

## Deliverables

- `docs/re/protos/fileservice.proto` — `FileService` (+ FileInfo/FileData/FileResult/FileStatus)
- `docs/re/protos/clipboard.proto` — `ClipBoardService` + `ClipboardFileService` (+ ClipData/UriRawInfo/Type/Result/Uri/UriContent)
- `docs/re/protos/inputservice.proto` — `InputService` (+ InputData/InputResult)
- `docs/re/protos/drag.proto` — no-service databus messages (DataBusChannelMessage/MacConnectMessage/DragDatabusMessage/DragDeviceInfo/DragFileInfo/DragStartedPayload/DragDroppedPayload/DragFinishedPayload)

## Methodology

1. **Outer-class mapping.** Each generated Java outer class (Fileservice, Clipboard,
   Inputservice, Drag) = one `.proto` file (`java_multiple_files=false`). The gRPC
   `*Grpc` classes confirm the pairing via their `FileDescriptorSupplier.getFileDescriptor()`
   calls (e.g. `FileServiceGrpc → Fileservice.getDescriptor()`).
2. **Field numbers.** `public static final int <NAME>_FIELD_NUMBER = N;` constants
   inside each message class → field `N`, name from the constant (snake-cased).
3. **Field types.** From generated accessors:
   - `ByteString get<Name>()` → `bytes`
   - `int get<Name>()` → `int32`; `long` → `int64`; `boolean` → `bool`
   - `Object get<Name>()` + `get<Name>Bytes()` → `string`
   - `SomeMsg get<Name>()` + `boolean has<Name>()` → optional message field
   - `List<Msg> get<Name>List()` → `repeated Msg`
   - `get<Name>Count()` + `getExtrasMap()` + `putExtras(String,String)` → `map<string, string>`
4. **Service methods.** From `*Grpc.java` method-descriptor blocks:
   `ty1VarB.f = yy1.a(SERVICE_NAME, "<method>")` → method name;
   `ty1VarB.c/d = gk2.a(<Msg>.getDefaultInstance())` → request/response messages.
5. **Streaming types.** `ty1VarB.e = MethodDescriptor$MethodType.X` — the R8-obfuscated
   enum constants were resolved by reading `io/grpc/MethodDescriptor$MethodType.java`'s
   `$VALUES` array (original names intact in the enum constructor calls):
   `a=UNARY, b=CLIENT_STREAMING, c=SERVER_STREAMING, d=BIDI_STREAMING`.

## Reconstructed service surface

| Service | Method | Request | Response | Type |
|---|---|---|---|---|
| FileService | createFile | FileInfo | FileResult | unary |
| FileService | deleteFile | FileInfo | FileResult | unary |
| FileService | openFile | FileInfo | FileResult | unary |
| FileService | readFile | FileInfo | stream FileData | server-streaming |
| FileService | writeFile | stream FileData | stream FileStatus | bidi |
| ClipBoardService | copyToClip | stream ClipData | Result | client-streaming |
| ClipBoardService | getClipData | Type | stream ClipData | server-streaming |
| ClipboardFileService | getUriContent | stream Uri | stream UriContent | bidi |
| InputService | inputToTextbox | stream InputData | InputResult | client-streaming |

## Confidence & unknowns

- **High confidence**: all field numbers/types/labels above (mechanical extraction
  from generated code), all service/method/message pairings, all streaming types
  (enum values verified from `$VALUES`).
- **Inferred (low impact)**: `.proto` file names — the compiled descriptors carry the
  original file names, but R8 strips/obfuscates them and jadx didn't surface any
  `"*.proto"` string; file names only matter for `java_outer_classname`, not for
  gRPC full method names (`<service>/<method>`).
- **Unknown (values, not shapes)**:
  - status/error code values (FileResult.status, FileStatus.status, UriContent.error_code,
    Result.status, InputResult.status) — need dynamic capture (T4) or constants hunt
  - `FileInfo.flags` / `FileData.flag` bit semantics
  - `ClipData.feature` format codes; `Type.type` values
  - `InputData.data` payload encoding (raw text vs serialized key event)
  - `DragDatabusMessage.msg_type` discriminator strings and whether `payload` is JSON
    (the values likely live in agent-2's SynergyCmd catalog / `drag/core` code)
- **Field-name caveat**: `getPCfilepath()` → written as `pcfilepath`; protobuf's
  camel-casing of the original field name may have differed (e.g. `pc_file_path`).
  Zero runtime impact for gRPC/JSON interop if we mirror the same casing we declare.
- **Presence semantics**: proto3 scalar fields show no `has…()` accessors → declared
  as plain singular (implicit presence), matching generated code. Message fields with
  `has…()` are singular optional.

## Validation

`protoc --proto_path docs/re/protos --descriptor_set_out=/dev/null docs/re/protos/*.proto`
runs clean (see log entry for the exact run).
