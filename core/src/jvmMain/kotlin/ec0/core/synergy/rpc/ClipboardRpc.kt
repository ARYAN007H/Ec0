/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Clipboard gRPC adapters — ClipBoardService (copyToClip client-stream,
 * getClipData server-stream) + ClipboardFileService (getUriContent bidi),
 * reconstructed from OPSynergy 17.7.35 (agent-1's T1 protos, reviewer-verified
 * package-less wire paths). Adapters are thin: they wrap the generated
 * grpc-kotlin coroutine stubs, take an injected io.grpc.Channel, and add
 * nothing else — every unknown (feature codes, status values) stays
 * explicit. See docs/re/synergy-rpc.md.
 */
package ec0.core.synergy.rpc

import com.oplus.linker.synergy.ClipBoardServiceGrpc
import com.oplus.linker.synergy.Clipboard
import com.oplus.linker.synergy.ClipboardFileServiceGrpc
import io.grpc.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Clipboard content push/pull. `feature` codes and `status` values are
 * upstream-unknown — pass through verbatim.
 */
class ClipboardClient(private val channel: Channel) {

    private val stub = ClipBoardServiceGrpc.ClipBoardServiceCoroutineStub(channel)

    /** Streams clipboard items to the phone; phone acknowledges with [Clipboard.Result]. */
    suspend fun copyToClip(items: Flow<Clipboard.ClipData>): Clipboard.Result = stub.copyToClip(items)

    /**
     * Convenience: copy one text item. `feature` is the format code the peer
     * expects — upstream semantics unknown (T4), defaults to 0.
     */
    suspend fun copyToClipText(text: String, feature: Int = 0): Clipboard.Result =
        copyToClip(
            flowOf(
                Clipboard.ClipData.newBuilder()
                    .setData(com.google.protobuf.ByteString.copyFromUtf8(text))
                    .setFeature(feature)
                    .build(),
            ),
        )

    /** Requests clipboard content of the given type; phone streams items back. */
    fun getClipData(type: Clipboard.Type): Flow<Clipboard.ClipData> = stub.getClipData(type)
}

/**
 * Content fetch for URIs attached to clipboard data (bidi: Uri requests in,
 * UriContent chunks out). `error_code` semantics unknown — surface verbatim.
 */
class ClipboardFileClient(private val channel: Channel) {

    private val stub = ClipboardFileServiceGrpc.ClipboardFileServiceCoroutineStub(channel)

    fun getUriContent(requests: Flow<Clipboard.Uri>): Flow<Clipboard.UriContent> =
        stub.getUriContent(requests)

    /** Convenience: request one URI's content. */
    fun uriContent(uri: String): Flow<Clipboard.UriContent> =
        getUriContent(flowOf(Clipboard.Uri.newBuilder().setUri(uri).setCancel(false).build()))
}
