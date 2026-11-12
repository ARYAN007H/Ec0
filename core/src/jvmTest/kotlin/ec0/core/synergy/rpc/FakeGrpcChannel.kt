/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Fake gRPC channel for adapter tests: implements io.grpc.Channel/ClientCall
 * over an in-memory handler. The REAL generated stubs (grpc-kotlin coroutine
 * stubs) run against it, so adapters are exercised through genuine grpc-core
 * client-call machinery (sendMessage → halfClose → onMessage* → onClose)
 * without sockets or transport artifacts.
 */
package ec0.core.synergy.rpc

import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.Channel as GrpcChannel

/** Test-supplied behavior per RPC: responses (in order) + terminal status. */
class FakeRpcBehavior(
    /** Called on halfClose with every request message received so far. */
    val respond: (method: MethodDescriptor<*, *>, requests: List<Any>) -> List<Any> = { _, _ -> emptyList() },
    val status: Status = Status.OK,
)

class FakeGrpcChannel(private val behaviorFor: (MethodDescriptor<*, *>) -> FakeRpcBehavior) : GrpcChannel() {

    /** Every request message, keyed by full method name — inspection for tests. */
    val sentRequests = LinkedHashMap<String, MutableList<Any>>()

    override fun <ReqT, RespT> newCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: io.grpc.CallOptions,
    ): ClientCall<ReqT, RespT> {
        @Suppress("UNCHECKED_CAST")
        val call = FakeClientCall(method as MethodDescriptor<Any, Any>, behaviorFor(method), sentRequests)
        @Suppress("UNCHECKED_CAST")
        return call as ClientCall<ReqT, RespT>
    }

    override fun authority(): String? = "fake.synergy"
}

private class FakeClientCall<ReqT : Any, RespT : Any>(
    private val method: MethodDescriptor<ReqT, RespT>,
    private val behavior: FakeRpcBehavior,
    private val sentRequests: LinkedHashMap<String, MutableList<Any>>,
) : ClientCall<ReqT, RespT>() {

    private var listener: Listener<RespT>? = null
    private val received = mutableListOf<Any>()
    private val responseQueue = ArrayDeque<Any>()
    private var requested = 0
    private var halfClosed = false
    private var closed = false
    private var responsesPrepared = false

    override fun start(listener: Listener<RespT>, headers: Metadata) {
        this.listener = listener
        listener.onReady()
    }

    override fun request(numMessages: Int) {
        requested += numMessages
        drain()
    }

    override fun sendMessage(request: ReqT) {
        received.add(request)
        sentRequests.getOrPut(method.fullMethodName) { mutableListOf() }.add(request)
    }

    override fun halfClose() {
        halfClosed = true
        prepare()
        drain()
    }

    override fun cancel(message: String?, cause: Throwable?) {
        closed = true
    }

    override fun isReady(): Boolean = !closed

    private fun prepare() {
        if (responsesPrepared) return
        responsesPrepared = true
        @Suppress("UNCHECKED_CAST")
        val typed = method as MethodDescriptor<Any, Any>
        behavior.respond(typed, received).forEach { responseQueue.add(it) }
    }

    private fun drain() {
        val l = listener ?: return
        while (requested > 0 && responseQueue.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            l.onMessage(responseQueue.removeFirst() as RespT)
            requested--
        }
        if (halfClosed && responseQueue.isEmpty() && !closed) {
            closed = true
            if (behavior.status.isOk) {
                l.onClose(Status.OK, Metadata())
            } else {
                l.onClose(behavior.status, Metadata())
            }
        }
    }
}
