/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * FileService + InputService gRPC adapters — thin wrappers over the generated
 * grpc-kotlin coroutine stubs (see ClipboardRpc.kt for design rationale).
 * Chunking semantics (FileData.flag EOF markers, FileStatus.length progress)
 * are upstream-unknown; adapters surface the raw streams.
 */
package ec0.core.synergy.rpc

import com.google.protobuf.ByteString
import com.oplus.linker.synergy.FileData
import com.oplus.linker.synergy.FileInfo
import com.oplus.linker.synergy.FileResult
import com.oplus.linker.synergy.FileServiceGrpc
import com.oplus.linker.synergy.FileStatus
import com.oplus.linker.synergy.InputData
import com.oplus.linker.synergy.InputResult
import com.oplus.linker.synergy.InputServiceGrpc
import io.grpc.Channel
import kotlinx.coroutines.flow.Flow

/**
 * File operations on the phone from the PC. All status codes are
 * upstream-unknown — pass through verbatim.
 */
class FileClient(private val channel: Channel) {

    private val stub = FileServiceGrpc.FileServiceCoroutineStub(channel)

    suspend fun createFile(info: FileInfo): FileResult = stub.createFile(info)

    suspend fun deleteFile(info: FileInfo): FileResult = stub.deleteFile(info)

    suspend fun openFile(info: FileInfo): FileResult = stub.openFile(info)

    /** Phone streams the file's chunks. */
    fun readFile(info: FileInfo): Flow<FileData> = stub.readFile(info)

    /**
     * Convenience: collect a read into bytes. Concatenates every chunk's
     * buffer — assumes chunks arrive in order and [FileData.flag] does not
     * signal interleaved content (semantics unknown, T4).
     */
    suspend fun readFileBytes(info: FileInfo): ByteArray =
        stub.readFile(info).fold(ByteArray(0)) { acc, chunk -> acc + chunk.buffer.toByteArray() }

    /** PC streams chunks; phone acknowledges with progress [FileStatus]. */
    fun writeFile(chunks: Flow<FileData>): Flow<FileStatus> = stub.writeFile(chunks)

    /**
     * Convenience: write one buffer as a single chunk (phone-side path in
     * [FileInfo.PCfilepath] is the blob name per the T1 reconstruction note).
     */
    fun writeBytes(info: FileInfo, bytes: ByteArray): Flow<FileStatus> =
        writeFile(
            kotlinx.coroutines.flow.flowOf(
                FileData.newBuilder()
                    .setInfo(info.filepath)
                    .setLength(bytes.size.toLong())
                    .setBuffer(ByteString.copyFrom(bytes))
                    .setUri(info.uri)
                    .build(),
            ),
        )
}

/** PC → phone text input (keystrokes/text into the focused text box). */
class InputClient(private val channel: Channel) {

    private val stub = InputServiceGrpc.InputServiceCoroutineStub(channel)

    /** Streams input events; single ack at stream end. Payload encoding unknown (T4). */
    suspend fun inputToTextbox(events: Flow<InputData>): InputResult = stub.inputToTextbox(events)

    /** Convenience: send one text payload (UTF-8 — encoding assumption documented in T1). */
    suspend fun sendText(text: String): InputResult =
        inputToTextbox(kotlinx.coroutines.flow.flowOf(InputData.newBuilder().setData(ByteString.copyFromUtf8(text)).build()))
}
