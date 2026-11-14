package ec0.core.engine.transport

import ec0.core.engine.smartconnect.SmartConnectConstants
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager
import kotlin.concurrent.withLock

/**
 * Leaf-DER SHA-256 certificate pinning trust manager (`docs/engine/smart-connect-design.md` §1.2).
 *
 * Both Motorola Smart Connect (`fp` in QR `wsData` and `_readyformdns._tcp` TXT) and O+ Synergy
 * (`certhash` in `ConnectInfo`) pin raw `SHA-256(leafCert.encoded)` over self-signed certificates
 * that may be X.509 v1, lack Subject Alternative Names (SAN), or use arbitrary validity ranges.
 *
 * [LeafDerPinningTrustManager] bypasses PKIX chain/CA/CN/SAN/expiry checks and instead computes
 * `SHA-256(chain[0].encoded)` (32 bytes) and compares it in constant time via
 * [MessageDigest.isEqual].
 */
class LeafDerPinningTrustManager(
    expectedFingerprintsHex: Set<String> = emptySet(),
    private val allowUnpinnedCapture: Boolean = false,
    private val customVerifier: ((leafCert: X509Certificate, fingerprintHex: String) -> Boolean)? = null
) : X509ExtendedTrustManager() {

    private val expectedDigests: List<ByteArray> = expectedFingerprintsHex
        .map { normalizeHexFingerprint(it) }
        .map { hexToBytes(it) }

    @Volatile
    var lastObservedPeerFingerprintHex: String? = null
        private set

    @Volatile
    var lastObservedPeerCertificate: X509Certificate? = null
        private set

    constructor(expectedFingerprintHex: String) : this(
        expectedFingerprintsHex = setOf(expectedFingerprintHex),
        allowUnpinnedCapture = false
    )

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verifyLeaf(chain)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verifyLeaf(chain)
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?
    ) {
        verifyLeaf(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?
    ) {
        verifyLeaf(chain)
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?
    ) {
        verifyLeaf(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?
    ) {
        verifyLeaf(chain)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verifyLeaf(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("Peer presented an empty X.509 certificate chain")
        }
        val leaf = chain[0]
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        val actualHex = bytesToHexLower(actualDigest)
        lastObservedPeerCertificate = leaf
        lastObservedPeerFingerprintHex = actualHex

        if (customVerifier != null) {
            if (!customVerifier.invoke(leaf, actualHex)) {
                throw CertificateException(
                    "Leaf certificate SHA-256 ($actualHex) rejected by custom pin verifier"
                )
            }
            return
        }

        if (expectedDigests.isEmpty()) {
            if (allowUnpinnedCapture) {
                return
            }
            throw CertificateException(
                "No pinned certificate fingerprints configured and unpinned capture is disabled"
            )
        }

        var matched = false
        for (expected in expectedDigests) {
            // Evaluate all pins using constant-time MessageDigest.isEqual
            if (MessageDigest.isEqual(actualDigest, expected)) {
                matched = true
            }
        }
        if (!matched) {
            throw CertificateException(
                "Leaf certificate SHA-256 ($actualHex) did not match any pinned fingerprint"
            )
        }
    }

    companion object {
        fun computeFingerprintHex(certificate: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            return bytesToHexLower(digest)
        }

        fun normalizeHexFingerprint(fpHex: String): String {
            val cleaned = fpHex.trim().replace(":", "").lowercase()
            require(cleaned.length == 64 && cleaned.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Expected 64-character hex SHA-256 fingerprint, got '$fpHex'"
            }
            return cleaned
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val normalized = normalizeHexFingerprint(hex)
            return ByteArray(32) { idx ->
                normalized.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }

        internal fun bytesToHexLower(bytes: ByteArray): String {
            val hexChars = "0123456789abcdef"
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xff
                out[i * 2] = hexChars[v ushr 4]
                out[i * 2 + 1] = hexChars[v and 0x0f]
            }
            return out.concatToString()
        }
    }
}

/**
 * Self-signed RSA-2048 TLS certificate and PKCS#8 private key material used by
 * [PinnedTlsWebSocketTransport].
 *
 * Uses a pure-Kotlin ASN.1 DER X.509 certificate encoder signed via `SHA256withRSA` so it
 * requires zero third-party libraries (no BouncyCastle) and zero JDK-internal `sun.security.*`
 * packages.
 */
class SmartConnectTlsIdentity(
    val certificate: X509Certificate,
    val privateKey: PrivateKey
) {
    val certificateDer: ByteArray
        get() = certificate.encoded

    val fingerprintHex: String = LeafDerPinningTrustManager.computeFingerprintHex(certificate)

    fun buildSslContext(trustManager: TrustManager): SSLContext {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("ec0-smartconnect", privateKey, CharArray(0), arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CharArray(0))
        }
        return SSLContext.getInstance("TLSv1.2").apply {
            init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
        }
    }

    companion object {
        private const val CERT_FILE_NAME = "cert.der"
        private const val KEY_FILE_NAME = "key.pkcs8"

        fun loadOrGenerate(storageDir: File, commonName: String = "EC0-SmartConnect"): SmartConnectTlsIdentity {
            storageDir.mkdirs()
            val certFile = File(storageDir, CERT_FILE_NAME)
            val keyFile = File(storageDir, KEY_FILE_NAME)
            if (certFile.isFile && keyFile.isFile) {
                runCatching {
                    val certBytes = certFile.readBytes()
                    val keyBytes = keyFile.readBytes()
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                    val privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
                    return SmartConnectTlsIdentity(cert, privateKey)
                }
            }
            val generated = generateSelfSignedRsa2048(commonName)
            runCatching {
                certFile.writeBytes(generated.certificate.encoded)
                keyFile.writeBytes(generated.privateKey.encoded)
                enforceOwnerOnlyPermissions(certFile)
                enforceOwnerOnlyPermissions(keyFile)
            }
            return generated
        }

        fun generateSelfSignedRsa2048(commonName: String = "EC0-SmartConnect"): SmartConnectTlsIdentity {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val keyPair = kpg.generateKeyPair()

            val tbsDer = buildTbsCertificateDer(
                commonName = commonName.ifBlank { "EC0-SmartConnect" },
                subjectPublicKeyInfoDer = keyPair.public.encoded
            )

            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbsDer)
            }.sign()

            val sha256WithRsaAlgId = derSequence(
                derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)), // sha256WithRSAEncryption
                derNull()
            )
            val certDer = derSequence(
                tbsDer,
                sha256WithRsaAlgId,
                derBitString(signature)
            )
            val x509 = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
            return SmartConnectTlsIdentity(x509, keyPair.private)
        }

        internal fun enforceOwnerOnlyPermissions(file: File) {
            runCatching {
                val perms = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
                Files.setPosixFilePermissions(file.toPath(), perms)
            }.onFailure {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
        }

        private fun buildTbsCertificateDer(
            commonName: String,
            subjectPublicKeyInfoDer: ByteArray
        ): ByteArray {
            val serialBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            serialBytes[0] = (serialBytes[0].toInt() and 0x7f).toByte()
            if (serialBytes[0] == 0.toByte()) serialBytes[0] = 1

            val versionExplicit0 = derExplicitTag(0, derInteger(BigInteger.valueOf(2))) // X.509 v3
            val serialNumber = derInteger(BigInteger(1, serialBytes))
            val signatureAlgId = derSequence(
                derOid(intArrayOf(1, 2, 840, 113549, 1, 1, 11)),
                derNull()
            )
            val rdnCommonName = derSequence(
                derSet(
                    derSequence(
                        derOid(intArrayOf(2, 5, 4, 3)), // id-at-commonName
                        derUtf8String(commonName)
                    )
                )
            )
            val validity = derSequence(
                derUtcTime("240101000000Z"),
                derUtcTime("391231235959Z")
            )
            return derSequence(
                versionExplicit0,
                serialNumber,
                signatureAlgId,
                rdnCommonName,
                validity,
                rdnCommonName,
                subjectPublicKeyInfoDer
            )
        }

        private fun derLength(len: Int): ByteArray {
            if (len < 128) return byteArrayOf(len.toByte())
            val temp = mutableListOf<Byte>()
            var rem = len
            while (rem > 0) {
                temp.add(0, (rem and 0xff).toByte())
                rem = rem ushr 8
            }
            return byteArrayOf((0x80 or temp.size).toByte()) + temp.toByteArray()
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            return byteArrayOf(tag.toByte()) + derLength(content.size) + content
        }

        private fun derSequence(vararg elements: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (el in elements) out.write(el)
            return derTag(0x30, out.toByteArray())
        }

        private fun derSet(vararg elements: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            for (el in elements) out.write(el)
            return derTag(0x31, out.toByteArray())
        }

        private fun derExplicitTag(tagNumber: Int, inner: ByteArray): ByteArray {
            return derTag(0xA0 or (tagNumber and 0x1F), inner)
        }

        private fun derInteger(value: BigInteger): ByteArray {
            return derTag(0x02, value.toByteArray())
        }

        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun derBitString(bytes: ByteArray): ByteArray {
            val content = ByteArray(bytes.size + 1)
            content[0] = 0x00 // 0 unused bits
            bytes.copyInto(content, destinationOffset = 1)
            return derTag(0x03, content)
        }

        private fun derUtf8String(text: String): ByteArray {
            return derTag(0x0C, text.toByteArray(StandardCharsets.UTF_8))
        }

        private fun derUtcTime(asciiTime: String): ByteArray {
            return derTag(0x17, asciiTime.toByteArray(StandardCharsets.US_ASCII))
        }

        private fun derOid(arcs: IntArray): ByteArray {
            require(arcs.size >= 2) { "OID must have at least 2 arcs" }
            val out = ByteArrayOutputStream()
            out.write(arcs[0] * 40 + arcs[1])
            for (i in 2 until arcs.size) {
                var v = arcs[i]
                val base128 = mutableListOf<Int>()
                base128.add(v and 0x7f)
                v = v ushr 7
                while (v > 0) {
                    base128.add(0, (v and 0x7f) or 0x80)
                    v = v ushr 7
                }
                for (b in base128) out.write(b)
            }
            return derTag(0x06, out.toByteArray())
        }
    }
}

/**
 * Active RFC 6455 WebSocket session over a pinned TLS connection (`docs/engine/smart-connect-design.md` §1.1).
 *
 * Guarantees:
 * - All outgoing frame writes (`TEXT`, `BINARY`, `PING`, `PONG`, `CLOSE`) are serialized by a
 *   per-connection [ReentrantLock] so concurrent callers never corrupt the byte stream.
 * - Supports payloads up to `maxFrameBytes` (default `20 MiB` for inline `svc 25 cmd 2` wallpapers).
 * - Tracks `lastActivityAtMs` for the `70_000 ms` dead-session watchdog.
 */
class PinnedTlsWebSocketSession(
    val sessionId: String,
    val requestPath: String,
    val remoteAddress: String,
    val peerCertificateFingerprintHex: String?,
    private val socket: SSLSocket,
    private val isClientSide: Boolean,
    val maxFrameBytes: Int = SmartConnectConstants.MAX_WS_FRAME_BYTES
) : AutoCloseable {

    private val writeLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val input = BufferedInputStream(socket.inputStream, 64 * 1024)
    private val output = BufferedOutputStream(socket.outputStream, 64 * 1024)
    private val random = SecureRandom()

    @Volatile
    var lastActivityAtMs: Long = System.currentTimeMillis()
        internal set

    val isOpen: Boolean
        get() = !closed.get() && !socket.isClosed

    fun sendText(text: String) {
        sendRawFrame(Ec0WsCodec.OPCODE_TEXT, text.toByteArray(StandardCharsets.UTF_8))
    }

    fun sendBinary(payload: ByteArray) {
        sendRawFrame(Ec0WsCodec.OPCODE_BINARY, payload)
    }

    fun sendPing(payload: ByteArray = byteArrayOf()) {
        val clipped = if (payload.size > 125) payload.copyOf(125) else payload
        sendRawFrame(Ec0WsCodec.OPCODE_PING, clipped)
    }

    fun sendPong(payload: ByteArray = byteArrayOf()) {
        val clipped = if (payload.size > 125) payload.copyOf(125) else payload
        sendRawFrame(Ec0WsCodec.OPCODE_PONG, clipped)
    }

    fun sendClose(
        statusCode: Int = Ec0WsCodec.CLOSE_NORMAL,
        reason: String = ""
    ) {
        if (closed.get()) return
        val frame = Ec0WsCodec.encodeCloseFrame(
            statusCode = statusCode,
            reason = reason,
            masked = isClientSide,
            maskKey = nextMaskKeyIfClient()
        )
        writeLock.withLock {
            runCatching {
                output.write(frame)
                output.flush()
            }
        }
    }

    private fun sendRawFrame(opcode: Int, payload: ByteArray) {
        check(!closed.get()) { "PinnedTlsWebSocketSession($sessionId) is closed" }
        val encoded = Ec0WsCodec.encodeFrame(
            opcode = opcode,
            payload = payload,
            fin = true,
            masked = isClientSide,
            maskKey = nextMaskKeyIfClient()
        )
        writeLock.withLock {
            output.write(encoded)
            output.flush()
        }
    }

    private fun nextMaskKeyIfClient(): ByteArray? {
        if (!isClientSide) return null
        return ByteArray(4).also { random.nextBytes(it) }
    }

    internal fun readLoop(listener: PinnedTlsWebSocketListener) {
        var fragOpcode = -1
        val fragBuffer = ByteArrayOutputStream()
        try {
            while (!closed.get() && !socket.isClosed) {
                val frame = Ec0WsCodec.readFrame(input, maxFrameBytes) ?: break
                lastActivityAtMs = System.currentTimeMillis()
                when (frame.opcode) {
                    Ec0WsCodec.OPCODE_PING -> {
                        runCatching { sendPong(frame.payload) }
                    }
                    Ec0WsCodec.OPCODE_PONG -> {
                        listener.onPong(this, frame.payload)
                    }
                    Ec0WsCodec.OPCODE_CLOSE -> {
                        val (code, reason) = parseClosePayload(frame.payload)
                        runCatching { sendClose(code, reason) }
                        listener.onClosed(this, code, reason)
                        break
                    }
                    Ec0WsCodec.OPCODE_TEXT, Ec0WsCodec.OPCODE_BINARY -> {
                        if (frame.fin) {
                            dispatchMessage(listener, frame.opcode, frame.payload)
                        } else {
                            fragOpcode = frame.opcode
                            fragBuffer.reset()
                            fragBuffer.write(frame.payload)
                        }
                    }
                    Ec0WsCodec.OPCODE_CONTINUATION -> {
                        if (fragOpcode != -1) {
                            if (fragBuffer.size() + frame.payload.size > maxFrameBytes) {
                                throw IllegalArgumentException(
                                    "Fragmented WebSocket message exceeds maxFrameBytes ($maxFrameBytes)"
                                )
                            }
                            fragBuffer.write(frame.payload)
                            if (frame.fin) {
                                val complete = fragBuffer.toByteArray()
                                val op = fragOpcode
                                fragOpcode = -1
                                fragBuffer.reset()
                                dispatchMessage(listener, op, complete)
                            }
                        }
                    }
                }
            }
        } catch (_: EOFException) {
            listener.onClosed(this, Ec0WsCodec.CLOSE_NORMAL, "EOF")
        } catch (t: Throwable) {
            if (!closed.get()) {
                listener.onError(this, t)
            }
        } finally {
            close()
        }
    }

    private fun dispatchMessage(
        listener: PinnedTlsWebSocketListener,
        opcode: Int,
        payload: ByteArray
    ) {
        if (opcode == Ec0WsCodec.OPCODE_TEXT) {
            listener.onTextMessage(this, payload.toString(StandardCharsets.UTF_8))
        } else if (opcode == Ec0WsCodec.OPCODE_BINARY) {
            listener.onBinaryMessage(this, payload)
        }
    }

    private fun parseClosePayload(payload: ByteArray): Pair<Int, String> {
        if (payload.size < 2) return Ec0WsCodec.CLOSE_NORMAL to ""
        val code = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        val reason = if (payload.size > 2) {
            payload.copyOfRange(2, payload.size).toString(StandardCharsets.UTF_8)
        } else {
            ""
        }
        return code to reason
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}

interface PinnedTlsWebSocketListener {
    fun onConnected(session: PinnedTlsWebSocketSession) {}
    fun onTextMessage(session: PinnedTlsWebSocketSession, text: String) {}
    fun onBinaryMessage(session: PinnedTlsWebSocketSession, bytes: ByteArray) {}
    fun onPong(session: PinnedTlsWebSocketSession, payload: ByteArray) {}
    fun onClosed(session: PinnedTlsWebSocketSession, code: Int, reason: String) {}
    fun onError(session: PinnedTlsWebSocketSession, cause: Throwable) {}
}

/**
 * Shared TLS-pinned RFC 6455 WebSocket transport (`docs/engine/smart-connect-design.md` §1.1).
 *
 * Supports:
 * - Single-port path-demuxed inbound TLS WebSocket server (`/readyfor/` and `/readyfor/mdns`)
 * - Outbound client connections (`connectClient`) pinned to a peer's leaf DER SHA-256 fingerprint
 * - Periodic `PING` keepalive (`20_000 ms`) and `70_000 ms` dead-session pruning
 */
class PinnedTlsWebSocketTransport(
    val tlsIdentity: SmartConnectTlsIdentity,
    val maxFrameBytes: Int = SmartConnectConstants.MAX_WS_FRAME_BYTES,
    val pingIntervalMs: Long = SmartConnectConstants.WS_PING_INTERVAL_MS,
    val deadSessionTimeoutMs: Long = SmartConnectConstants.WS_DEAD_SESSION_TIMEOUT_MS
) : AutoCloseable {

    private val pathHandlers = ConcurrentHashMap<String, PinnedTlsWebSocketListener>()
    private val activeSessions = ConcurrentHashMap<String, PinnedTlsWebSocketSession>()
    private val running = AtomicBoolean(false)
    private var serverSocket: SSLServerSocket? = null
    private var ioExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ec0-pinned-tls-ws-io").apply { isDaemon = true }
    }
    private var watchdogScheduler: ScheduledExecutorService? = null

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Registers a path handler (e.g. `"/readyfor/"` or `"/readyfor/mdns"`).
     */
    fun registerPathHandler(path: String, listener: PinnedTlsWebSocketListener) {
        pathHandlers[normalizePath(path)] = listener
    }

    /**
     * Starts the single-port TLS WebSocket server on [port] (use `0` for an ephemeral test port,
     * or `9836` for Smart Connect primary port).
     */
    fun startServer(
        port: Int = SmartConnectConstants.DEFAULT_WS_PORT,
        clientCertVerifier: ((X509Certificate, String) -> Boolean)? = null
    ): Int {
        if (!running.compareAndSet(false, true)) {
            return boundPort
        }
        val trustManager = LeafDerPinningTrustManager(
            expectedFingerprintsHex = emptySet(),
            allowUnpinnedCapture = true,
            customVerifier = clientCertVerifier
        )
        val sslContext = tlsIdentity.buildSslContext(trustManager)
        val srv = sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
        srv.reuseAddress = true
        srv.bind(InetSocketAddress(port))
        srv.enabledProtocols = srv.supportedProtocols.filter {
            it == "TLSv1.3" || it == "TLSv1.2"
        }.toTypedArray()
        // Allow optional client cert presentation without failing clients that omit client auth
        srv.wantClientAuth = true
        serverSocket = srv

        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ec0-pinned-tls-ws-watchdog").apply { isDaemon = true }
        }
        watchdogScheduler = scheduler
        if (pingIntervalMs > 0L) {
            scheduler.scheduleAtFixedRate(
                { runKeepaliveSweep() },
                pingIntervalMs,
                pingIntervalMs,
                TimeUnit.MILLISECONDS
            )
        }

        ioExecutor.execute { acceptLoop(srv) }
        return srv.localPort
    }

    /**
     * Dials an outbound TLS-pinned WebSocket connection to `wss://[host]:[port][path]`.
     *
     * @param expectedServerFingerprintHex Expected 64-char hex SHA-256 of the peer's leaf DER
     *   certificate (`null` only if [allowUnpinnedCapture] is explicitly enabled).
     */
    fun connectClient(
        host: String,
        port: Int,
        path: String,
        expectedServerFingerprintHex: String?,
        listener: PinnedTlsWebSocketListener,
        connectTimeoutMs: Int = 5_000,
        allowUnpinnedCapture: Boolean = false
    ): PinnedTlsWebSocketSession {
        val trustManager = if (expectedServerFingerprintHex != null) {
            LeafDerPinningTrustManager(expectedServerFingerprintHex)
        } else {
            LeafDerPinningTrustManager(
                expectedFingerprintsHex = emptySet(),
                allowUnpinnedCapture = allowUnpinnedCapture
            )
        }
        val sslContext = tlsIdentity.buildSslContext(trustManager)
        val rawSocket = Socket()
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)

        val sslSocket = sslContext.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
        sslSocket.enabledProtocols = sslSocket.supportedProtocols.filter {
            it == "TLSv1.3" || it == "TLSv1.2"
        }.toTypedArray()
        val sslParams: SSLParameters = sslSocket.sslParameters
        // Disable hostname/SAN verification because LeafDerPinningTrustManager pins leaf DER SHA-256
        sslParams.endpointIdentificationAlgorithm = ""
        sslSocket.sslParameters = sslParams
        sslSocket.soTimeout = connectTimeoutMs
        sslSocket.startHandshake()
        sslSocket.soTimeout = 0

        val wsKeyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val wsKey = Base64.getEncoder().encodeToString(wsKeyBytes)
        val normalizedPath = if (path.startsWith('/')) path else "/$path"

        val req = buildString {
            append("GET ").append(normalizedPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        sslSocket.outputStream.write(req.toByteArray(StandardCharsets.US_ASCII))
        sslSocket.outputStream.flush()

        val responseHeaders = readHttpHeaderBlock(sslSocket.inputStream)
        val statusLine = responseHeaders.lineSequence().firstOrNull().orEmpty()
        require(statusLine.contains("101")) {
            "WebSocket upgrade failed on $host:$port$normalizedPath: $statusLine"
        }
        val acceptMatch = Regex("(?i)^Sec-WebSocket-Accept:\\s*(.+)$", RegexOption.MULTILINE)
            .find(responseHeaders)
            ?.groupValues
            ?.get(1)
            ?.trim()
        val expectedAccept = Ec0WsCodec.computeAcceptKey(wsKey)
        require(acceptMatch == expectedAccept) {
            "Invalid Sec-WebSocket-Accept from $host:$port (expected $expectedAccept, got $acceptMatch)"
        }

        val session = PinnedTlsWebSocketSession(
            sessionId = UUID.randomUUID().toString(),
            requestPath = normalizePath(normalizedPath),
            remoteAddress = rawSocket.inetAddress?.hostAddress ?: host,
            peerCertificateFingerprintHex = trustManager.lastObservedPeerFingerprintHex,
            socket = sslSocket,
            isClientSide = true,
            maxFrameBytes = maxFrameBytes
        )
        activeSessions[session.sessionId] = session
        listener.onConnected(session)
        ioExecutor.execute {
            try {
                session.readLoop(listener)
            } finally {
                activeSessions.remove(session.sessionId)
            }
        }
        return session
    }

    private fun acceptLoop(srv: SSLServerSocket) {
        while (running.get() && !srv.isClosed) {
            val sslSocket = try {
                srv.accept() as SSLSocket
            } catch (_: Throwable) {
                break
            }
            ioExecutor.execute { handleInboundSocket(sslSocket) }
        }
    }

    private fun handleInboundSocket(sslSocket: SSLSocket) {
        try {
            sslSocket.tcpNoDelay = true
            sslSocket.soTimeout = 10_000
            sslSocket.startHandshake()
            sslSocket.soTimeout = 0

            val peerFp = runCatching {
                val peerCerts = sslSocket.session.peerCertificates
                if (peerCerts.isNotEmpty() && peerCerts[0] is X509Certificate) {
                    LeafDerPinningTrustManager.computeFingerprintHex(peerCerts[0] as X509Certificate)
                } else {
                    null
                }
            }.getOrNull()

            val headerBlock = readHttpHeaderBlock(sslSocket.inputStream)
            val lines = headerBlock.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2 || !parts[0].equals("GET", ignoreCase = true)) {
                sslSocket.close()
                return
            }
            val rawPath = parts[1].substringBefore('?')
            val normalizedPath = normalizePath(rawPath)
            val listener = pathHandlers[normalizedPath]
                ?: pathHandlers[rawPath]
                ?: run {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    sslSocket.outputStream.write(notFound.toByteArray(StandardCharsets.US_ASCII))
                    sslSocket.outputStream.flush()
                    sslSocket.close()
                    return
                }

            var wsKey: String? = null
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val name = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (name.equals("Sec-WebSocket-Key", ignoreCase = true)) {
                        wsKey = value
                    }
                }
            }
            if (wsKey.isNullOrBlank()) {
                sslSocket.close()
                return
            }

            val acceptKey = Ec0WsCodec.computeAcceptKey(wsKey)
            val upgradeResponse = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n\r\n")
            }
            sslSocket.outputStream.write(upgradeResponse.toByteArray(StandardCharsets.US_ASCII))
            sslSocket.outputStream.flush()

            val session = PinnedTlsWebSocketSession(
                sessionId = UUID.randomUUID().toString(),
                requestPath = normalizedPath,
                remoteAddress = sslSocket.inetAddress?.hostAddress ?: "127.0.0.1",
                peerCertificateFingerprintHex = peerFp,
                socket = sslSocket,
                isClientSide = false,
                maxFrameBytes = maxFrameBytes
            )
            activeSessions[session.sessionId] = session
            listener.onConnected(session)
            try {
                session.readLoop(listener)
            } finally {
                activeSessions.remove(session.sessionId)
            }
        } catch (_: Throwable) {
            runCatching { sslSocket.close() }
        }
    }

    private fun runKeepaliveSweep() {
        val now = System.currentTimeMillis()
        for ((id, session) in activeSessions) {
            if (!session.isOpen) {
                activeSessions.remove(id)
                continue
            }
            if (deadSessionTimeoutMs > 0L && now - session.lastActivityAtMs > deadSessionTimeoutMs) {
                activeSessions.remove(id)
                session.close()
                continue
            }
            runCatching {
                session.sendPing("ec0".toByteArray(StandardCharsets.US_ASCII))
            }.onFailure {
                activeSessions.remove(id)
                session.close()
            }
        }
    }

    private fun readHttpHeaderBlock(inputStream: java.io.InputStream): String {
        val buf = ByteArrayOutputStream()
        var state = 0
        while (buf.size() < 16 * 1024) {
            val b = inputStream.read()
            if (b < 0) throw EOFException("EOF reading HTTP upgrade headers")
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) {
                return buf.toString(StandardCharsets.US_ASCII.name())
            }
        }
        throw IllegalArgumentException("HTTP upgrade headers exceeded 16 KiB")
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val withLeading = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return if (withLeading == "/readyfor") "/readyfor/" else withLeading
    }

    override fun close() {
        running.set(false)
        runCatching { watchdogScheduler?.shutdownNow() }
        runCatching { serverSocket?.close() }
        for ((_, session) in activeSessions) {
            runCatching { session.close() }
        }
        activeSessions.clear()
    }
}
