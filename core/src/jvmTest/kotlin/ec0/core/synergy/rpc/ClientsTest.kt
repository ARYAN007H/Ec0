/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapter tests over the fake channel — real grpc-kotlin coroutine stubs,
 * fake ClientCall machinery, zero sockets.
 */
package ec0.core.synergy.rpc

import com.google.protobuf.ByteString
import com.oplus.linker.synergy.Clipboard
import com.oplus.linker.synergy.Fileservice
import com.oplus.linker.synergy.Inputservice
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClipboardClientTest {

    private val clipData = Clipboard.ClipData.newBuilder()
        .setData(ByteString.copyFromUtf8("hello clipboard"))
        .setFeature(1)
        .build()

    @Test
    fun copyToClipStreamsItemsAndReturnsAck() = runBlocking {
        val channel = FakeGrpcChannel { method ->
            assertEquals("ClipBoardService/copyToClip", method.fullMethodName)
            FakeRpcBehavior(respond = { _, requests ->
                assertEquals(1, requests.size)
                listOf(Clipboard.Result.newBuilder().setStatus(0).build())
            })
        }
        val result = ClipboardClient(channel).copyToClip(flowOf(clipData))
        assertEquals(0, result.status)
        assertEquals(clipData, (channel.sentRequests["ClipBoardService/copyToClip"]!!).single())
    }

    @Test
    fun copyToClipTextConvenienceBuildsUtf8Item() = runBlocking {
        val channel = FakeGrpcChannel { FakeRpcBehavior(respond = { _, _ -> listOf(Clipboard.Result.newBuilder().setStatus(7).build()) }) }
        val result = ClipboardClient(channel).copyToClipText("hé", feature = 3)
        assertEquals(7, result.status)
        val sent = channel.sentRequests["ClipBoardService/copyToClip"]!!.single() as Clipboard.ClipData
        assertEquals("hé", sent.data.toStringUtf8())
        assertEquals(3, sent.feature)
    }

    @Test
    fun getClipDataStreamsMultipleItems() = runBlocking {
        val items = listOf(
            Clipboard.ClipData.newBuilder().setData(ByteString.copyFromUtf8("one")).build(),
            Clipboard.ClipData.newBuilder().setData(ByteString.copyFromUtf8("two")).build(),
            Clipboard.ClipData.newBuilder().setData(ByteString.copyFromUtf8("three")).build(),
        )
        val channel = FakeGrpcChannel { method ->
            assertEquals("ClipBoardService/getClipData", method.fullMethodName)
            FakeRpcBehavior(respond = { _, _ -> items })
        }
        val received = ClipboardClient(channel).getClipData(Clipboard.Type.newBuilder().setType(2).build()).toList()
        assertEquals(items, received)
    }

    @Test
    fun serverStreamErrorPropagates() = runBlocking {
        val channel = FakeGrpcChannel { FakeRpcBehavior(respond = { _, _ -> emptyList<Any>() }, status = Status.INTERNAL) }
        val thrown = assertFailsWith<StatusException> {
            ClipboardClient(channel).getClipData(Clipboard.Type.newBuilder().build()).toList()
        }
        assertEquals(Status.Code.INTERNAL, thrown.status.code)
        Unit
    }

    @Test
    fun uriContentBidiStreamsChunks() = runBlocking {
        val chunk = Clipboard.UriContent.newBuilder()
            .setErrorCode(0)
            .setUri("content://media/image/1")
            .setBuffer(ByteString.copyFromUtf8("pngbytes"))
            .build()
        val channel = FakeGrpcChannel { method ->
            assertEquals("ClipboardFileService/getUriContent", method.fullMethodName)
            FakeRpcBehavior(respond = { _, requests ->
                assertEquals("content://media/image/1", (requests.single() as Clipboard.Uri).uri)
                listOf(chunk)
            })
        }
        val received = ClipboardFileClient(channel).uriContent("content://media/image/1").toList()
        assertEquals(listOf(chunk), received)
    }
}

class FileClientTest {

    private val info = Fileservice.FileInfo.newBuilder()
        .setFilepath("/storage/emulated/0/DCIM/a.jpg")
        .setLength(12345)
        .setUri("content://media/file/a")
        .setPCfilepath("/home/aryan/backup/a.jpg")
        .build()

    @Test
    fun unaryCreateFileReturnsResult() = runBlocking {
        val expected = Fileservice.FileResult.newBuilder().setStatus(0).setFile("/home/aryan/backup/a.jpg").build()
        val channel = FakeGrpcChannel { method ->
            assertEquals("FileService/createFile", method.fullMethodName)
            FakeRpcBehavior(respond = { _, requests -> assertEquals(info, requests.single()); listOf(expected) })
        }
        assertEquals(expected, FileClient(channel).createFile(info))
    }

    @Test
    fun readFileStreamsChunksAndBytesCollect() = runBlocking {
        val chunks = listOf(
            Fileservice.FileData.newBuilder()
                .setInfo("/storage/emulated/0/DCIM/a.jpg")
                .setLength(4)
                .setBuffer(ByteString.copyFromUtf8("abcd"))
                .build(),
            Fileservice.FileData.newBuilder()
                .setInfo("/storage/emulated/0/DCIM/a.jpg")
                .setLength(2)
                .setBuffer(ByteString.copyFromUtf8("ef"))
                .build(),
        )
        val channel = FakeGrpcChannel { method ->
            assertEquals("FileService/readFile", method.fullMethodName)
            FakeRpcBehavior(respond = { _, requests ->
                assertEquals(info, requests.single())
                assertEquals(12345L, (requests.single() as Fileservice.FileInfo).length) // sint64 survived
                chunks
            })
        }
        val received = FileClient(channel).readFile(info).toList()
        assertEquals(chunks, received)
        assertTrue(FileClient(channel).readFileBytes(info).contentEquals("abcdef".toByteArray()))
    }

    @Test
    fun writeBytesEmitsChunkAndCollectsStatuses() = runBlocking {
        val statuses = listOf(
            Fileservice.FileStatus.newBuilder().setStatus(0).setLength(8).build(),
        )
        val channel = FakeGrpcChannel { method ->
            assertEquals("FileService/writeFile", method.fullMethodName)
            FakeRpcBehavior(respond = { _, requests ->
                val data = requests.single() as Fileservice.FileData
                assertEquals(8L, data.length) // zigzag-encoded on the wire, decoded by protobuf
                assertEquals("abcdefgh", data.buffer.toStringUtf8())
                statuses
            })
        }
        val info = Fileservice.FileInfo.newBuilder().setFilepath("/tmp/notes.txt").setPCfilepath("/pc/notes.txt").build()
        val received = FileClient(channel).writeBytes(info, "abcdefgh".toByteArray()).toList()
        assertEquals(statuses, received)
    }

    @Test
    fun unaryErrorPropagates() = runBlocking {
        val channel = FakeGrpcChannel { FakeRpcBehavior(respond = { _, _ -> emptyList<Any>() }, status = Status.UNAVAILABLE) }
        val thrown = assertFailsWith<StatusException> {
            FileClient(channel).createFile(info)
        }
        assertEquals(Status.Code.UNAVAILABLE, thrown.status.code)
        Unit
    }
}

class InputClientTest {

    @Test
    fun inputToTextboxStreamsEventsAndAcks() = runBlocking {
        val ack = Inputservice.InputResult.newBuilder().setStatus(0).build()
        val channel = FakeGrpcChannel { method ->
            assertEquals("InputService/inputToTextbox", method.fullMethodName)
            FakeRpcBehavior(respond = { _, requests ->
                assertEquals(3, requests.size)
                listOf(ack)
            })
        }
        val events = flowOf("a", "b", "c").map { Inputservice.InputData.newBuilder().setData(ByteString.copyFromUtf8(it)).build() }
        assertEquals(0, InputClient(channel).inputToTextbox(events).status)
        assertEquals(3, channel.sentRequests["InputService/inputToTextbox"]!!.size)
    }

    @Test
    fun sendTextConvenience() = runBlocking {
        val channel = FakeGrpcChannel { FakeRpcBehavior(respond = { _, _ -> listOf(Inputservice.InputResult.newBuilder().setStatus(1).build()) }) }
        assertEquals(1, InputClient(channel).sendText("héllo").status)
        val sent = channel.sentRequests["InputService/inputToTextbox"]!!.single() as Inputservice.InputData
        assertEquals("héllo", sent.data.toStringUtf8())
    }
}
