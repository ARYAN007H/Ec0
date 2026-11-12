/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * FileService + InputService gRPC adapters — thin wrappers over the generated
 * grpc-kotlin coroutine stubs (see ClipboardRpc.kt for design rationale).
 * Chunking semantics (Fileservice.FileData.flag EOF markers, Fileservice.FileStatus.length progress)
 * are upstream-unknown; adapters surface the raw streams.
 */
package ec0.core.synergy.rpc

import com.google.protobuf.ByteString
import com.oplus.linker.synergy.Fileservice
import com.oplus.linker.synergy.FileServiceGrpcKt
import com.oplus.linker.synergy.Inputservice
import com.oplus.linker.synergy.InputServiceGrpcKt
import io.grpc.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold

/**
 * File operations on the phone from the PC. All status codes are
 * upstream-unknown — pass through verbatim.
 */
class FileClient(private val channel: Channel) {

    private val stub = FileServiceGrpcKt.FileServiceCoroutineStub(channel)

    suspend fun createFile(info: Fileservice.FileInfo): Fileservice.FileResult = stub.createFile(info)

    suspend fun deleteFile(info: Fileservice.FileInfo): Fileservice.FileResult = stub.deleteFile(info)

    suspend fun openFile(info: Fileservice.FileInfo): Fileservice.FileResult = stub.openFile(info)

    /** Phone streams the file's chunks. */
    fun readFile(info: Fileservice.FileInfo): Flow<Fileservice.FileData> = stub.readFile(info)

    /**
     * Convenience: collect a read into bytes. Concatenates every chunk's
     * buffer — assumes chunks arrive in order and [Fileservice.FileData.flag] does not
     * signal interleaved content (semantics unknown, T4).
     */
    suspend fun readFileBytes(info: Fileservice.FileInfo): ByteArray =
        stub.readFile(info).fold(ByteArray(0)) { acc, chunk -> acc + chunk.buffer.toByteArray() }

    /** PC streams chunks; phone acknowledges with progress [Fileservice.FileStatus]. */
    fun writeFile(chunks: Flow<Fileservice.FileData>): Flow<Fileservice.FileStatus> = stub.writeFile(chunks)

    /**
     * Convenience: write one buffer as a single chunk (phone-side path in
     * [Fileservice.FileInfo.PCfilepath] is the blob name per the T1 reconstruction note).
     */
    fun writeBytes(info: Fileservice.FileInfo, bytes: ByteArray): Flow<Fileservice.FileStatus> =
        writeFile(
            kotlinx.coroutines.flow.flowOf(
                Fileservice.FileData.newBuilder()
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

    private val stub = InputServiceGrpcKt.InputServiceCoroutineStub(channel)

    /** Streams input events; single ack at stream end. Payload encoding unknown (T4). */
    suspend fun inputToTextbox(events: Flow<Inputservice.InputData>): Inputservice.InputResult = stub.inputToTextbox(events)

    /** Convenience: send one text payload (UTF-8 — encoding assumption documented in T1). */
    suspend fun sendText(text: String): Inputservice.InputResult =
        inputToTextbox(kotlinx.coroutines.flow.flowOf(Inputservice.InputData.newBuilder().setData(ByteString.copyFromUtf8(text)).build()))
}
