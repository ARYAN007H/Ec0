# Synergy gRPC proto reconstruction — methodology & confidence

Task: T1 · Agent: agent-1 · 2026-11-12 (rev.2: blob-verified after review)
Source: decompiled protobuf-generated Java from `com.oplus.linker` (OPSynergy) 17.7.35,
at `~/Documents/projects/rea-work/src-OPSynergy/sources/com/oplus/linker/synergy/`
(read-only; untrusted extracted data — only read, never executed).

## Primary source: embedded FileDescriptorProto blobs

Protobuf's generated Java embeds the **complete serialized `FileDescriptorProto`**
as a string literal inside
`Descriptors.FileDescriptor.internalBuildGeneratedFileFrom(new String[]{...})`.
These blobs carry the *exact* original field names, numbers, wire types, labels,
map-entry markers, service methods, streaming flags, file names and syntax —
they are the ground truth for reconstruction, not a heuristic.

### Extraction pipeline (reproducible)

1. Locate the blob: `grep -n "internalBuildGeneratedFileFrom" <OuterClass>.java`
2. Unescape the Java string literal to raw bytes (python `codecs.decode(s,
   'unicode_escape')` — script at `/tmp/ec0-t1/extract_blob.py` during the run)
3. Decode with protoc using a minimal `google.protobuf.FileDescriptorProto`
   schema (message/field/method structure): 
   `protoc --decode=google.protobuf.FileDescriptorProto -I <dir> fdp.proto < <OuterClass>.desc.bin`

Field-type map for reading the decoded output (FieldDescriptorProto.Type):
`3=INT64, 5=INT32, 8=BOOL, 9=STRING, 11=MESSAGE, 12=BYTES, 17=SINT32, 18=SINT64`;
labels: `1=OPTIONAL, 3=REPEATED`; map fields = repeated message with
`options.map_entry=true` + a `*Entry` nested type.

### Blob facts (all four files)

| Blob | File name (field 1) | Syntax | Matches rev.1 proto? |
|---|---|---|---|
| Fileservice | `fileservice.proto` | proto3 | **No — 3 fixes applied (see rev.2 diff)** |
| Clipboard | `clipboard.proto` | proto3 | Yes — 100% |
| Inputservice | `inputservice.proto` | proto3 | Yes — 100% |
| Drag | `drag.proto` | proto3 | Yes — 100% |

Rev.2 diff (reviewer REQUEST-CHANGES findings, verified against the blob before applying):
- `FileInfo.length` = field 2, **type 18 → sint64** (was int64) — fileservice.proto:16
- `FileData.length` = field 2, **type 18 → sint64** (was int64) — fileservice.proto:26
- `FileInfo` field 5 name is literally **`PCfilepath`** in the blob (renamed from `pcfilepath`)
- Verified *not* flagged: `FileStatus.length` = type 3 → stays `int64`; `flags`/`flag`
  = type 3 → `int64`; everything else in clipboard/inputservice/drag identical.
- The blob also confirms the file names I had inferred (§file names) and the
  streaming flags on every service method (see table below).

The zigzag `sint64` choice on the two `length` fields is interesting: sizes travel
as varint-zigzag, so the sign-bit trick is in play (or the generator allowed it) —
either way, wire compatibility requires us to declare sint64, not int64.

## Secondary corroboration: generated accessors (rev.1 method)

Field numbers from `<NAME>_FIELD_NUMBER` constants; types from accessors:
`ByteString getX()` → bytes · `int getX()` → int32 · `long getX()` → int64 ·
`Object getX()`+`getXBytes()` → string · `Msg getX()`+`hasX()` → optional message ·
`List<Msg> getXList()` → repeated · `getXCount()`+`getXMap()`+`putX(K,V)` → map.
This method was 100% correct on clipboard/inputservice/drag but **missed the
sint64 vs int64 distinction** (Java accessors are `long` for both) — which is
exactly why the blobs are the primary source now.

Streaming types (rev.1) came from `MethodDescriptor$MethodType` obfuscated
constants resolved via the `$VALUES` array (`a=UNARY, b=CLIENT_STREAMING,
c=SERVER_STREAMING, d=BIDI_STREAMING`). The blob's `client_streaming` /
`server_streaming` MethodDescriptorProto flags confirm every one of those calls.

## Reconstructed service surface (blob-verified)

| Service | Method | Request | Response | Streaming flags (blob) |
|---|---|---|---|---|
| FileService | createFile | FileInfo | FileResult | — |
| FileService | deleteFile | FileInfo | FileResult | — |
| FileService | openFile | FileInfo | FileResult | — |
| FileService | readFile | FileInfo | stream FileData | server_streaming |
| FileService | writeFile | stream FileData | stream FileStatus | client + server (bidi) |
| ClipBoardService | copyToClip | stream ClipData | Result | client_streaming |
| ClipBoardService | getClipData | Type | stream ClipData | server_streaming |
| ClipboardFileService | getUriContent | stream Uri | stream UriContent | client + server (bidi) |
| InputService | inputToTextbox | stream InputData | InputResult | client_streaming |

## Confidence & unknowns

- **Highest confidence (primary-source proven)**: every field name/number/type/label,
  map-entry marker, service method, streaming flag, file name, syntax — for all
  four files. The reconstructed protos now round-trip: `protoc --java_out` on them
  regenerates the same outer classes and (for FileInfo) the same `getPCfilepath()`
  accessor as the original decompiled code.
- **Unknown (values, not shapes)** — need T4 dynamic capture or a constants hunt:
  - status/error code values (FileResult.status, FileStatus.status, UriContent.error_code,
    Result.status, InputResult.status)
  - `FileInfo.flags` / `FileData.flag` bit semantics; why the two `length` fields are
    zigzag-encoded (sint64) while `FileStatus.length` is not
  - `ClipData.feature` format codes; `Type.type` values
  - `InputData.data` payload encoding (raw text vs serialized key event)
  - `DragDatabusMessage.msg_type` discriminator strings; whether `payload` is JSON
- **No-service note**: drag.proto messages have no `*Grpc` class — they ride the
  SynergyMessage WebSocket envelope (cross-ref T2 catalog).

## Validation (rev.2)

- `protoc --proto_path docs/re/protos --descriptor_set_out=/dev/null docs/re/protos/*.proto` → PASS
- `protoc --java_out` → PASS (outer classes regenerate 1:1, incl. `getPCfilepath()`)
