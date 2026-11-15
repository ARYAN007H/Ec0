/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pure-Kotlin mDNS / DNS-SD wire-format codec (RFC 1035, RFC 6762, RFC 6763)
 * for `_ec0._tcp.local.` service discovery and announcement.
 *
 * Supports PTR (12), TXT (16), SRV (33), A (1), and AAAA (28) resource records,
 * DNS label compression pointers (0xC0) with strict cycle/bounds guards,
 * and goodbye packets (TTL = 0).
 */
package ec0.core.engine

data class MdnsHeader(
    val id: Int = 0,
    val flags: Int,
    val qdCount: Int,
    val anCount: Int,
    val nsCount: Int = 0,
    val arCount: Int = 0,
)

data class MdnsQuestion(
    val name: String,
    val qType: Int,
    val qClass: Int = MdnsCodec.CLASS_IN,
)

sealed interface MdnsResourceRecord {
    val name: String
    val rrType: Int
    val rrClass: Int
    val ttlSeconds: Long

    data class PtrRecord(
        override val name: String,
        override val rrClass: Int,
        override val ttlSeconds: Long,
        val ptrDomainName: String,
    ) : MdnsResourceRecord {
        override val rrType: Int = MdnsCodec.TYPE_PTR
    }

    data class SrvRecord(
        override val name: String,
        override val rrClass: Int,
        override val ttlSeconds: Long,
        val priority: Int,
        val weight: Int,
        val port: Int,
        val targetHost: String,
    ) : MdnsResourceRecord {
        override val rrType: Int = MdnsCodec.TYPE_SRV
    }

    data class TxtRecord(
        override val name: String,
        override val rrClass: Int,
        override val ttlSeconds: Long,
        val attributes: Map<String, String>,
    ) : MdnsResourceRecord {
        override val rrType: Int = MdnsCodec.TYPE_TXT
    }

    data class ARecord(
        override val name: String,
        override val rrClass: Int,
        override val ttlSeconds: Long,
        val ipv4Address: String,
    ) : MdnsResourceRecord {
        override val rrType: Int = MdnsCodec.TYPE_A
    }

    data class AaaaRecord(
        override val name: String,
        override val rrClass: Int,
        override val ttlSeconds: Long,
        val ipv6Address: String,
    ) : MdnsResourceRecord {
        override val rrType: Int = MdnsCodec.TYPE_AAAA
    }

    data class RawRecord(
        override val name: String,
        override val rrType: Int,
        override val rrClass: Int,
        override val ttlSeconds: Long,
        val rdata: ByteArray,
    ) : MdnsResourceRecord
}

data class MdnsPacket(
    val header: MdnsHeader,
    val questions: List<MdnsQuestion>,
    val answers: List<MdnsResourceRecord>,
    val authorities: List<MdnsResourceRecord> = emptyList(),
    val additionals: List<MdnsResourceRecord> = emptyList(),
)

data class DiscoveredPeerRecord(
    val peer: PeerInfo,
    val ttlSeconds: Long,
    val receivedAtMs: Long,
) {
    val isGoodbye: Boolean
        get() = ttlSeconds == 0L

    fun isExpired(nowMs: Long): Boolean {
        if (isGoodbye) return true
        return nowMs >= receivedAtMs + (ttlSeconds * 1000L)
    }
}

data class DiscoveredCamDroidService(
    val instanceName: String,
    val deviceName: String,
    val port: Int,
    val addresses: List<String>,
    val codecs: List<String> = listOf("h264", "h265", "mjpeg"),
    val resolution: String = "1080p",
    val version: String = "1.0",
    val ttlSeconds: Long,
    val receivedAtMs: Long,
) {
    val isGoodbye: Boolean get() = ttlSeconds == 0L
    fun isExpired(nowMs: Long): Boolean = isGoodbye || (nowMs - receivedAtMs) >= (ttlSeconds * 1000L)
}

/**
 * Discovered Motorola Smart Connect ("Ready For") `_readyformdns._tcp.local.` endpoint
 * announced via mDNS PTR/SRV/TXT/A records (`docs/engine/smart-connect-design.md` §3-S-A).
 *
 * Note: [deviceId] is the rotating 16-byte salted-hash `nonceId` (encoded as 24-char Base64),
 * matched to a paired peer via `SmartConnectIdentity.matchNonceIdBase64ToPairedPeer`.
 */
data class DiscoveredReadyForPeer(
    val instanceName: String,
    val deviceId: String,
    val deviceName: String,
    val port: Int,
    val addresses: List<String>,
    val fp: String,
    val type: Int = 1,
    val os: Int = 1,
    val appType: Int = 1,
    val cap: Int = 0,
    val timestamp: Long = 0L,
    val ttlSeconds: Long,
    val receivedAtMs: Long,
) {
    val isGoodbye: Boolean get() = ttlSeconds == 0L
    fun isExpired(nowMs: Long): Boolean = isGoodbye || (nowMs - receivedAtMs) >= (ttlSeconds * 1000L)
}

object MdnsCodec {
    const val TYPE_A: Int = 1
    const val TYPE_PTR: Int = 12
    const val TYPE_TXT: Int = 16
    const val TYPE_AAAA: Int = 28
    const val TYPE_SRV: Int = 33
    const val TYPE_ANY: Int = 255

    const val CLASS_IN: Int = 1
    const val CLASS_FLUSH: Int = 0x8001 // CLASS_IN | cache-flush bit (0x8000)
    const val CLASS_UNICAST_RESPONSE_BIT: Int = 0x8000

    const val FLAGS_QUERY: Int = 0x0000
    const val FLAGS_RESPONSE_AA: Int = 0x8400 // QR=1, AA=1

    private const val MAX_COMPRESSION_JUMPS: Int = 16
    private const val MAX_LABEL_BYTES: Int = 63

    /**
     * Builds an mDNS PTR query packet for `_ec0._tcp.local.`.
     */
    fun encodePtrQuery(
        serviceType: String = EngineConstants.MDNS_SERVICE_TYPE,
        unicastResponse: Boolean = false,
    ): ByteArray {
        val qClass = if (unicastResponse) CLASS_IN or CLASS_UNICAST_RESPONSE_BIT else CLASS_IN
        val packet = MdnsPacket(
            header = MdnsHeader(id = 0, flags = FLAGS_QUERY, qdCount = 1, anCount = 0),
            questions = listOf(
                MdnsQuestion(
                    name = normalizeDomain(serviceType),
                    qType = TYPE_PTR,
                    qClass = qClass,
                ),
            ),
            answers = emptyList(),
        )
        return encodePacket(packet)
    }

    /**
     * Builds an unsolicited mDNS announcement response packet (`flags = 0x8400`)
     * containing PTR, SRV, TXT, A, and optional AAAA records for [peer].
     */
    fun encodeAnnouncement(
        peer: PeerInfo,
        ttlSeconds: Long = EngineConstants.MDNS_DEFAULT_TTL_SECONDS.toLong(),
        serviceType: String = EngineConstants.MDNS_SERVICE_TYPE,
    ): ByteArray {
        require(ttlSeconds >= 0L) { "ttlSeconds must be non-negative" }
        val normalizedService = normalizeDomain(serviceType)
        val instanceLabel = "${ peer.deviceId.take(12) }"
        val instanceDomain = "$instanceLabel.$normalizedService"
        val hostDomain = "$instanceLabel.local."

        val txtAttrs = buildTxtAttributes(peer)
        val answers = mutableListOf<MdnsResourceRecord>(
            MdnsResourceRecord.PtrRecord(
                name = normalizedService,
                rrClass = CLASS_IN,
                ttlSeconds = ttlSeconds,
                ptrDomainName = instanceDomain,
            ),
            MdnsResourceRecord.SrvRecord(
                name = instanceDomain,
                rrClass = CLASS_FLUSH,
                ttlSeconds = ttlSeconds,
                priority = 0,
                weight = 0,
                port = peer.port,
                targetHost = hostDomain,
            ),
            MdnsResourceRecord.TxtRecord(
                name = instanceDomain,
                rrClass = CLASS_FLUSH,
                ttlSeconds = ttlSeconds,
                attributes = txtAttrs,
            ),
        )

        for (addr in peer.addresses) {
            if (isValidIpv4(addr)) {
                answers.add(
                    MdnsResourceRecord.ARecord(
                        name = hostDomain,
                        rrClass = CLASS_FLUSH,
                        ttlSeconds = ttlSeconds,
                        ipv4Address = addr,
                    ),
                )
            } else if (isValidIpv6(addr)) {
                answers.add(
                    MdnsResourceRecord.AaaaRecord(
                        name = hostDomain,
                        rrClass = CLASS_FLUSH,
                        ttlSeconds = ttlSeconds,
                        ipv6Address = addr,
                    ),
                )
            }
        }

        return encodePacket(
            MdnsPacket(
                header = MdnsHeader(
                    id = 0,
                    flags = FLAGS_RESPONSE_AA,
                    qdCount = 0,
                    anCount = answers.size,
                ),
                questions = emptyList(),
                answers = answers,
            ),
        )
    }

    /**
     * Builds an RFC 6762 §10.1 Goodbye packet (`TTL = 0`) so peers immediately
     * evict [peer] from their discovery cache upon clean shutdown.
     */
    fun encodeGoodbye(
        peer: PeerInfo,
        serviceType: String = EngineConstants.MDNS_SERVICE_TYPE,
    ): ByteArray = encodeAnnouncement(peer = peer, ttlSeconds = 0L, serviceType = serviceType)

    /**
     * Builds the normative `_ec0._tcp.local.` TXT attribute map per design.md §5.1.
     */
    fun buildTxtAttributes(peer: PeerInfo): Map<String, String> = linkedMapOf(
        "v" to peer.protocolVersion.toString(),
        "id" to peer.deviceId,
        "fp" to peer.shortFingerprint,
        "name" to peer.displayName,
        "role" to peer.role,
        "caps" to EngineCapability.formatCsv(peer.capabilities),
        "pair" to if (peer.pairingOpen) "1" else "0",
    )

    /**
     * Serializes an [MdnsPacket] into standard big-endian DNS wire bytes.
     */
    fun encodePacket(packet: MdnsPacket): ByteArray {
        val out = ByteArrayBuilder()
        // 12-byte DNS header
        out.writeU16(packet.header.id)
        out.writeU16(packet.header.flags)
        out.writeU16(packet.questions.size)
        out.writeU16(packet.answers.size)
        out.writeU16(packet.authorities.size)
        out.writeU16(packet.additionals.size)

        for (q in packet.questions) {
            out.writeDomainName(q.name)
            out.writeU16(q.qType)
            out.writeU16(q.qClass)
        }

        val allRecords = packet.answers + packet.authorities + packet.additionals
        for (rr in allRecords) {
            out.writeDomainName(rr.name)
            out.writeU16(rr.rrType)
            out.writeU16(rr.rrClass)
            out.writeU32(rr.ttlSeconds)
            val rdata = encodeRdata(rr)
            out.writeU16(rdata.size)
            out.writeBytes(rdata)
        }

        return out.toByteArray()
    }

    private fun encodeRdata(rr: MdnsResourceRecord): ByteArray {
        val out = ByteArrayBuilder()
        when (rr) {
            is MdnsResourceRecord.PtrRecord -> out.writeDomainName(rr.ptrDomainName)
            is MdnsResourceRecord.SrvRecord -> {
                out.writeU16(rr.priority)
                out.writeU16(rr.weight)
                out.writeU16(rr.port)
                out.writeDomainName(rr.targetHost)
            }
            is MdnsResourceRecord.TxtRecord -> {
                if (rr.attributes.isEmpty()) {
                    out.writeU8(0)
                } else {
                    for ((k, v) in rr.attributes) {
                        val entryBytes = "$k=$v".encodeToByteArray()
                        val capped = if (entryBytes.size > 255) entryBytes.copyOf(255) else entryBytes
                        out.writeU8(capped.size)
                        out.writeBytes(capped)
                    }
                }
            }
            is MdnsResourceRecord.ARecord -> {
                out.writeBytes(parseIpv4Bytes(rr.ipv4Address))
            }
            is MdnsResourceRecord.AaaaRecord -> {
                out.writeBytes(parseIpv6Bytes(rr.ipv6Address))
            }
            is MdnsResourceRecord.RawRecord -> {
                out.writeBytes(rr.rdata)
            }
        }
        return out.toByteArray()
    }

    /**
     * Decodes a DNS/mDNS wire packet from [bytes] (up to [length]).
     * Throws [IllegalArgumentException] on truncated frames, invalid compression pointers,
     * or pointer loops.
     */
    fun decodePacket(bytes: ByteArray, length: Int = bytes.size): MdnsPacket {
        require(length >= 12 && length <= bytes.size) {
            "DNS packet must be at least 12 bytes (got $length)"
        }
        val reader = DnsReader(bytes, length)
        val id = reader.readU16()
        val flags = reader.readU16()
        val qdCount = reader.readU16()
        val anCount = reader.readU16()
        val nsCount = reader.readU16()
        val arCount = reader.readU16()

        val questions = ArrayList<MdnsQuestion>(qdCount.coerceAtMost(64))
        repeat(qdCount) {
            val qName = reader.readDomainName()
            val qType = reader.readU16()
            val qClass = reader.readU16()
            questions.add(MdnsQuestion(qName, qType, qClass))
        }

        val answers = readRecords(reader, anCount)
        val authorities = readRecords(reader, nsCount)
        val additionals = readRecords(reader, arCount)

        return MdnsPacket(
            header = MdnsHeader(id, flags, qdCount, anCount, nsCount, arCount),
            questions = questions,
            answers = answers,
            authorities = authorities,
            additionals = additionals,
        )
    }

    private fun readRecords(reader: DnsReader, count: Int): List<MdnsResourceRecord> {
        val list = ArrayList<MdnsResourceRecord>(count.coerceAtMost(64))
        repeat(count) {
            val name = reader.readDomainName()
            val rrType = reader.readU16()
            val rrClass = reader.readU16()
            val ttl = reader.readU32()
            val rdLength = reader.readU16()
            val rdataStart = reader.position
            require(rdataStart + rdLength <= reader.limit) {
                "Truncated RDATA: need $rdLength bytes at offset $rdataStart (limit ${reader.limit})"
            }
            val record = when (rrType) {
                TYPE_PTR -> {
                    val ptrDomain = reader.readDomainName()
                    MdnsResourceRecord.PtrRecord(name, rrClass, ttl, ptrDomain)
                }
                TYPE_SRV -> {
                    require(rdLength >= 7) { "SRV RDATA too short: $rdLength" }
                    val priority = reader.readU16()
                    val weight = reader.readU16()
                    val port = reader.readU16()
                    val targetHost = reader.readDomainName()
                    MdnsResourceRecord.SrvRecord(name, rrClass, ttl, priority, weight, port, targetHost)
                }
                TYPE_TXT -> {
                    val attrs = linkedMapOf<String, String>()
                    val end = rdataStart + rdLength
                    while (reader.position < end) {
                        val txtLen = reader.readU8()
                        require(reader.position + txtLen <= end) {
                            "TXT string length $txtLen exceeds RDATA boundary"
                        }
                        if (txtLen > 0) {
                            val strBytes = reader.readBytes(txtLen)
                            val str = strBytes.decodeToString()
                            val eq = str.indexOf('=')
                            if (eq > 0) {
                                attrs[str.substring(0, eq)] = str.substring(eq + 1)
                            }
                        }
                    }
                    MdnsResourceRecord.TxtRecord(name, rrClass, ttl, attrs)
                }
                TYPE_A -> {
                    require(rdLength == 4) { "A record must have 4-byte RDATA (got $rdLength)" }
                    val b = reader.readBytes(4)
                    val ip = "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"
                    MdnsResourceRecord.ARecord(name, rrClass, ttl, ip)
                }
                TYPE_AAAA -> {
                    require(rdLength == 16) { "AAAA record must have 16-byte RDATA (got $rdLength)" }
                    val b = reader.readBytes(16)
                    val groups = (0 until 8).map { i ->
                        val hi = b[i * 2].toInt() and 0xFF
                        val lo = b[i * 2 + 1].toInt() and 0xFF
                        ((hi shl 8) or lo).toString(16)
                    }
                    MdnsResourceRecord.AaaaRecord(name, rrClass, ttl, groups.joinToString(":"))
                }
                else -> {
                    val raw = reader.readBytes(rdLength)
                    MdnsResourceRecord.RawRecord(name, rrType, rrClass, ttl, raw)
                }
            }
            reader.position = rdataStart + rdLength
            list.add(record)
        }
        return list
    }

    /**
     * Correlates PTR, SRV, TXT, A, and AAAA records inside [packet] into
     * verified [DiscoveredPeerRecord] entries.
     */
    fun extractPeers(
        packet: MdnsPacket,
        nowMs: Long,
        fallbackSenderIp: String? = null,
        serviceType: String = EngineConstants.MDNS_SERVICE_TYPE,
    ): List<DiscoveredPeerRecord> {
        val normalizedService = normalizeDomain(serviceType)
        val allRecords = packet.answers + packet.additionals + packet.authorities

        val srvByName = mutableMapOf<String, MdnsResourceRecord.SrvRecord>()
        val txtByName = mutableMapOf<String, MdnsResourceRecord.TxtRecord>()
        val ptrInstances = mutableMapOf<String, Long>()
        val addrsByHost = mutableMapOf<String, MutableList<String>>()

        for (rr in allRecords) {
            when (rr) {
                is MdnsResourceRecord.PtrRecord -> {
                    if (rr.name.equals(normalizedService, ignoreCase = true)) {
                        ptrInstances[normalizeDomain(rr.ptrDomainName)] = rr.ttlSeconds
                    }
                }
                is MdnsResourceRecord.SrvRecord -> {
                    srvByName[normalizeDomain(rr.name)] = rr
                }
                is MdnsResourceRecord.TxtRecord -> {
                    txtByName[normalizeDomain(rr.name)] = rr
                }
                is MdnsResourceRecord.ARecord -> {
                    addrsByHost.getOrPut(normalizeDomain(rr.name)) { mutableListOf() }.add(rr.ipv4Address)
                }
                is MdnsResourceRecord.AaaaRecord -> {
                    addrsByHost.getOrPut(normalizeDomain(rr.name)) { mutableListOf() }.add(rr.ipv6Address)
                }
                is MdnsResourceRecord.RawRecord -> Unit
            }
        }

        val candidateNames = (ptrInstances.keys + srvByName.keys + txtByName.keys)
            .filter { it.endsWith(".$normalizedService", ignoreCase = true) }
            .toSet()

        val result = mutableListOf<DiscoveredPeerRecord>()
        for (instanceName in candidateNames) {
            val txt = txtByName[instanceName] ?: continue
            val srv = srvByName[instanceName]
            val attrs = txt.attributes

            val deviceId = attrs["id"]?.lowercase() ?: continue
            if (deviceId.length != 64 || !deviceId.all { it in '0'..'9' || it in 'a'..'f' }) continue
            val shortFp = attrs["fp"]?.lowercase() ?: deviceId.take(12)
            if (shortFp != deviceId.take(12)) continue

            val version = attrs["v"]?.toIntOrNull() ?: EngineConstants.PROTOCOL_VERSION
            val displayName = attrs["name"]?.takeIf { it.isNotBlank() } ?: "ec0-device"
            val role = attrs["role"]?.takeIf { it.isNotBlank() } ?: EngineConstants.ROLE_DESKTOP
            val caps = EngineCapability.parseCsv(attrs["caps"] ?: "")
            val pairingOpen = attrs["pair"] == "1"
            val port = srv?.port?.takeIf { it in 1..65535 } ?: EngineConstants.PRIMARY_PORT

            val hostAddrs = srv?.let { addrsByHost[normalizeDomain(it.targetHost)] }?.toList().orEmpty()
            val resolvedAddrs = when {
                hostAddrs.isNotEmpty() -> hostAddrs
                fallbackSenderIp != null -> listOf(fallbackSenderIp)
                else -> emptyList()
            }

            val ttlCandidates = listOfNotNull(
                ptrInstances[instanceName],
                srv?.ttlSeconds,
                txt.ttlSeconds,
            )
            val effectiveTtl = if (ttlCandidates.any { it == 0L }) {
                0L
            } else {
                ttlCandidates.minOrNull() ?: EngineConstants.MDNS_DEFAULT_TTL_SECONDS.toLong()
            }

            result.add(
                DiscoveredPeerRecord(
                    peer = PeerInfo(
                        deviceId = deviceId,
                        displayName = displayName,
                        role = role,
                        protocolVersion = version,
                        capabilities = caps,
                        shortFingerprint = shortFp,
                        pairingOpen = pairingOpen,
                        addresses = resolvedAddrs,
                        port = port,
                        lastSeenMs = nowMs,
                    ),
                    ttlSeconds = effectiveTtl,
                    receivedAtMs = nowMs,
                ),
            )
        }
        return result
    }

    /**
     * Extracts discovered `ARYAN007H/CamDroid` (`_camdroid._tcp.local.`) streaming
     * endpoints from an incoming [MdnsPacket] (registered by `NsdHelper.kt` with TXT
     * attributes `version`, `codecs`, `resolution`, `device`).
     */
    fun extractCamDroidServices(
        packet: MdnsPacket,
        nowMs: Long,
        serviceType: String = "_camdroid._tcp.local.",
        fallbackSenderIp: String? = null,
    ): List<DiscoveredCamDroidService> {
        val normalizedService = normalizeDomain(serviceType)
        val allRecords = packet.answers + packet.additionals

        val ptrInstances = mutableMapOf<String, Long>()
        val srvByName = mutableMapOf<String, MdnsResourceRecord.SrvRecord>()
        val txtByName = mutableMapOf<String, MdnsResourceRecord.TxtRecord>()
        val addrsByHost = mutableMapOf<String, MutableList<String>>()

        for (rr in allRecords) {
            when (rr) {
                is MdnsResourceRecord.PtrRecord -> {
                    if (normalizeDomain(rr.name).equals(normalizedService, ignoreCase = true)) {
                        ptrInstances[normalizeDomain(rr.ptrDomainName)] = rr.ttlSeconds
                    }
                }
                is MdnsResourceRecord.SrvRecord -> srvByName[normalizeDomain(rr.name)] = rr
                is MdnsResourceRecord.TxtRecord -> txtByName[normalizeDomain(rr.name)] = rr
                is MdnsResourceRecord.ARecord ->
                    addrsByHost.getOrPut(normalizeDomain(rr.name)) { mutableListOf() }.add(rr.ipv4Address)
                is MdnsResourceRecord.AaaaRecord ->
                    addrsByHost.getOrPut(normalizeDomain(rr.name)) { mutableListOf() }.add(rr.ipv6Address)
                is MdnsResourceRecord.RawRecord -> Unit
            }
        }

        val candidateNames = (ptrInstances.keys + srvByName.keys + txtByName.keys)
            .filter { it.endsWith(".$normalizedService", ignoreCase = true) }
            .toSet()

        val result = mutableListOf<DiscoveredCamDroidService>()
        for (instanceName in candidateNames) {
            val srv = srvByName[instanceName]
            val txt = txtByName[instanceName]
            val attrs = txt?.attributes.orEmpty()
            val rawInstancePrefix = instanceName.removeSuffix(".$normalizedService")
            val deviceName = attrs["device"]?.takeIf { it.isNotBlank() }
                ?: rawInstancePrefix.removePrefix("CamDroid-").ifBlank { "CamDroid" }
            val codecs = attrs["codecs"]
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotEmpty() }
                ?.ifEmpty { listOf("h264", "h265", "mjpeg") }
                ?: listOf("h264", "h265", "mjpeg")
            val resolution = attrs["resolution"]?.takeIf { it.isNotBlank() } ?: "1080p"
            val version = attrs["version"]?.takeIf { it.isNotBlank() } ?: "1.0"
            val port = srv?.port?.takeIf { it in 1..65535 } ?: 4747

            val hostAddrs = srv?.let { addrsByHost[normalizeDomain(it.targetHost)] }?.toList().orEmpty()
            val resolvedAddrs = when {
                hostAddrs.isNotEmpty() -> hostAddrs
                fallbackSenderIp != null -> listOf(fallbackSenderIp)
                else -> emptyList()
            }

            val ttlCandidates = listOfNotNull(ptrInstances[instanceName], srv?.ttlSeconds, txt?.ttlSeconds)
            val effectiveTtl = if (ttlCandidates.any { it == 0L }) 0L else (ttlCandidates.minOrNull() ?: 120L)

            result.add(
                DiscoveredCamDroidService(
                    instanceName = rawInstancePrefix,
                    deviceName = deviceName,
                    port = port,
                    addresses = resolvedAddrs,
                    codecs = codecs,
                    resolution = resolution,
                    version = version,
                    ttlSeconds = effectiveTtl,
                    receivedAtMs = nowMs,
                ),
            )
        }
        return result
    }

    /**
     * Encodes an mDNS response announcing a `CamDroid` `_camdroid._tcp.local.`
     * endpoint (for testing and phone-side announcement).
     */
    fun encodeCamDroidAnnouncement(
        deviceName: String,
        port: Int = 4747,
        addresses: List<String>,
        codecs: List<String> = listOf("h264", "h265", "mjpeg"),
        resolution: String = "1080p",
        ttlSeconds: Long = 120L,
    ): ByteArray {
        val safeInstance = "CamDroid-${deviceName.replace('.', '-').replace(' ', '-')}"
        val serviceDomain = "_camdroid._tcp.local."
        val fullInstance = "$safeInstance.$serviceDomain"
        val hostTarget = "$safeInstance.local."
        val records = mutableListOf<MdnsResourceRecord>(
            MdnsResourceRecord.PtrRecord(
                name = serviceDomain,
                rrClass = CLASS_IN,
                ttlSeconds = ttlSeconds,
                ptrDomainName = fullInstance,
            ),
            MdnsResourceRecord.SrvRecord(
                name = fullInstance,
                rrClass = CLASS_FLUSH,
                ttlSeconds = ttlSeconds,
                priority = 0,
                weight = 0,
                port = port,
                targetHost = hostTarget,
            ),
            MdnsResourceRecord.TxtRecord(
                name = fullInstance,
                rrClass = CLASS_FLUSH,
                ttlSeconds = ttlSeconds,
                attributes = linkedMapOf(
                    "version" to "1.0",
                    "codecs" to codecs.joinToString(","),
                    "resolution" to resolution,
                    "device" to deviceName,
                ),
            ),
        )
        for (addr in addresses) {
            if (isValidIpv4(addr)) {
                records.add(
                    MdnsResourceRecord.ARecord(
                        name = hostTarget,
                        rrClass = CLASS_FLUSH,
                        ttlSeconds = ttlSeconds,
                        ipv4Address = addr,
                    ),
                )
            }
        }
        return encodePacket(
            MdnsPacket(
                header = MdnsHeader(
                    id = 0,
                    flags = FLAGS_RESPONSE_AA,
                    qdCount = 0,
                    anCount = records.size,
                ),
                questions = emptyList(),
                answers = records,
            ),
        )
    }

    /**
     * Encodes an mDNS announcement for Motorola Smart Connect `_readyformdns._tcp.local.`
     * (`docs/engine/smart-connect-design.md` §3-S-A).
     *
     * TXT attributes:
     * - `deviceId`: current rotating `nonceId` (24-char Base64 of 16 bytes)
     * - `deviceName`: host display name
     * - `type`: `"4"` (Laptop)
     * - `os`: `"6"` (Windows PC profile required by stock APK)
     * - `appType`: `"2"` (Desktop Smart Connect daemon)
     * - `cap`: `"0"`
     * - `timestamp`: epoch milliseconds string
     * - `fp`: 64-char lowercase hex SHA-256 of leaf DER certificate
     */
    fun encodeReadyForAnnouncement(
        nonceIdBase64: String,
        deviceName: String,
        port: Int = 9836,
        addresses: List<String>,
        certFingerprintHex: String,
        timestampMs: Long,
        type: Int = 4,
        os: Int = 6,
        appType: Int = 2,
        cap: Int = 0,
        ttlSeconds: Long = 120L,
    ): ByteArray {
        val safeHostPrefix = deviceName
            .replace(Regex("[^A-Za-z0-9-]"), "-")
            .trim('-')
            .ifBlank { "EC0-PC" }
            .take(40)
        val serviceDomain = "_readyformdns._tcp.local."
        val fullInstance = "$safeHostPrefix.$serviceDomain"
        val hostTarget = "$safeHostPrefix.local."
        val normalizedFp = certFingerprintHex.trim().lowercase()

        val records = mutableListOf<MdnsResourceRecord>(
            MdnsResourceRecord.PtrRecord(
                name = serviceDomain,
                rrClass = CLASS_IN,
                ttlSeconds = ttlSeconds,
                ptrDomainName = fullInstance,
            ),
            MdnsResourceRecord.SrvRecord(
                name = fullInstance,
                rrClass = CLASS_FLUSH,
                ttlSeconds = ttlSeconds,
                priority = 0,
                weight = 0,
                port = port,
                targetHost = hostTarget,
            ),
            MdnsResourceRecord.TxtRecord(
                name = fullInstance,
                rrClass = CLASS_FLUSH,
                ttlSeconds = ttlSeconds,
                attributes = linkedMapOf(
                    "deviceId" to nonceIdBase64.trim(),
                    "deviceName" to deviceName.ifBlank { "EC0-PC" },
                    "type" to type.toString(),
                    "os" to os.toString(),
                    "appType" to appType.toString(),
                    "cap" to cap.toString(),
                    "timestamp" to timestampMs.toString(),
                    "fp" to normalizedFp,
                ),
            ),
        )
        for (addr in addresses) {
            if (isValidIpv4(addr)) {
                records.add(
                    MdnsResourceRecord.ARecord(
                        name = hostTarget,
                        rrClass = CLASS_FLUSH,
                        ttlSeconds = ttlSeconds,
                        ipv4Address = addr,
                    ),
                )
            } else if (isValidIpv6(addr)) {
                records.add(
                    MdnsResourceRecord.AaaaRecord(
                        name = hostTarget,
                        rrClass = CLASS_FLUSH,
                        ttlSeconds = ttlSeconds,
                        ipv6Address = addr,
                    ),
                )
            }
        }
        return encodePacket(
            MdnsPacket(
                header = MdnsHeader(
                    id = 0,
                    flags = FLAGS_RESPONSE_AA,
                    qdCount = 0,
                    anCount = records.size,
                ),
                questions = emptyList(),
                answers = records,
            ),
        )
    }

    /**
     * Extracts discovered Motorola Smart Connect (`_readyformdns._tcp.local.`) peers from an
     * incoming [MdnsPacket].
     */
    fun extractReadyForPeers(
        packet: MdnsPacket,
        nowMs: Long,
        serviceType: String = "_readyformdns._tcp.local.",
        fallbackSenderIp: String? = null,
    ): List<DiscoveredReadyForPeer> {
        val normalizedService = normalizeDomain(serviceType)
        val allRecords = packet.answers + packet.additionals

        val ptrInstances = mutableMapOf<String, Long>()
        val srvByName = mutableMapOf<String, MdnsResourceRecord.SrvRecord>()
        val txtByName = mutableMapOf<String, MdnsResourceRecord.TxtRecord>()
        val addrsByHost = mutableMapOf<String, MutableList<String>>()

        for (rr in allRecords) {
            when (rr) {
                is MdnsResourceRecord.PtrRecord -> {
                    if (normalizeDomain(rr.name).equals(normalizedService, ignoreCase = true)) {
                        ptrInstances[normalizeDomain(rr.ptrDomainName)] = rr.ttlSeconds
                    }
                }
                is MdnsResourceRecord.SrvRecord -> srvByName[normalizeDomain(rr.name)] = rr
                is MdnsResourceRecord.TxtRecord -> txtByName[normalizeDomain(rr.name)] = rr
                is MdnsResourceRecord.ARecord ->
                    addrsByHost.getOrPut(normalizeDomain(rr.name)) { mutableListOf() }.add(rr.ipv4Address)
                is MdnsResourceRecord.AaaaRecord ->
                    addrsByHost.getOrPut(normalizeDomain(rr.name)) { mutableListOf() }.add(rr.ipv6Address)
                is MdnsResourceRecord.RawRecord -> Unit
            }
        }

        val candidateNames = (ptrInstances.keys + srvByName.keys + txtByName.keys)
            .filter { it.endsWith(".$normalizedService", ignoreCase = true) }
            .toSet()

        val result = mutableListOf<DiscoveredReadyForPeer>()
        for (instanceName in candidateNames) {
            val txt = txtByName[instanceName]
            val srv = srvByName[instanceName]
            val attrs = txt?.attributes.orEmpty()

            val deviceId = attrs["deviceId"]?.trim().orEmpty()
            if (deviceId.isEmpty()) continue

            val rawInstancePrefix = instanceName.removeSuffix(".$normalizedService")
            val deviceName = attrs["deviceName"]?.takeIf { it.isNotBlank() }
                ?: rawInstancePrefix.ifBlank { "ReadyFor-Device" }
            val fp = attrs["fp"]?.trim()?.lowercase().orEmpty()
            val type = attrs["type"]?.toIntOrNull() ?: 1
            val os = attrs["os"]?.toIntOrNull() ?: 1
            val appType = attrs["appType"]?.toIntOrNull() ?: 1
            val cap = attrs["cap"]?.toIntOrNull() ?: 0
            val timestamp = attrs["timestamp"]?.toLongOrNull() ?: 0L
            val port = srv?.port?.takeIf { it in 1..65535 } ?: 9836

            val hostAddrs = srv?.let { addrsByHost[normalizeDomain(it.targetHost)] }?.toList().orEmpty()
            val resolvedAddrs = when {
                hostAddrs.isNotEmpty() -> hostAddrs
                fallbackSenderIp != null -> listOf(fallbackSenderIp)
                else -> emptyList()
            }

            val ttlCandidates = listOfNotNull(ptrInstances[instanceName], srv?.ttlSeconds, txt?.ttlSeconds)
            val effectiveTtl = if (ttlCandidates.any { it == 0L }) 0L else (ttlCandidates.minOrNull() ?: 120L)

            result.add(
                DiscoveredReadyForPeer(
                    instanceName = rawInstancePrefix,
                    deviceId = deviceId,
                    deviceName = deviceName,
                    port = port,
                    addresses = resolvedAddrs,
                    fp = fp,
                    type = type,
                    os = os,
                    appType = appType,
                    cap = cap,
                    timestamp = timestamp,
                    ttlSeconds = effectiveTtl,
                    receivedAtMs = nowMs,
                ),
            )
        }
        return result
    }

    fun normalizeDomain(domain: String): String {
        val trimmed = domain.trim()
        if (trimmed.isEmpty() || trimmed == ".") return "."
        return if (trimmed.endsWith('.')) trimmed else "$trimmed."
    }

    fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            val n = p.toIntOrNull() ?: return@all false
            n in 0..255 && p == n.toString()
        }
    }

    fun isValidIpv6(ip: String): Boolean {
        val parts = ip.split(':')
        if (parts.size != 8) return false
        return parts.all { p ->
            p.length in 1..4 && p.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
        }
    }

    private fun parseIpv4Bytes(ip: String): ByteArray {
        val parts = ip.split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $ip" }
        return ByteArray(4) { i ->
            val v = parts[i].toIntOrNull()
            require(v != null && v in 0..255) { "Invalid IPv4 octet in $ip" }
            v.toByte()
        }
    }

    private fun parseIpv6Bytes(ip: String): ByteArray {
        val parts = ip.split(':')
        require(parts.size == 8) { "Invalid full IPv6 address: $ip" }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            val group = parts[i].toInt(16)
            out[i * 2] = ((group ushr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (group and 0xFF).toByte()
        }
        return out
    }

    private class ByteArrayBuilder {
        private var buf = ByteArray(256)
        var size: Int = 0
            private set

        private fun ensureCapacity(extra: Int) {
            if (size + extra > buf.size) {
                var newCap = buf.size * 2
                while (newCap < size + extra) newCap *= 2
                buf = buf.copyOf(newCap)
            }
        }

        fun writeU8(v: Int) {
            ensureCapacity(1)
            buf[size++] = (v and 0xFF).toByte()
        }

        fun writeU16(v: Int) {
            ensureCapacity(2)
            buf[size++] = ((v ushr 8) and 0xFF).toByte()
            buf[size++] = (v and 0xFF).toByte()
        }

        fun writeU32(v: Long) {
            ensureCapacity(4)
            buf[size++] = ((v ushr 24) and 0xFFL).toByte()
            buf[size++] = ((v ushr 16) and 0xFFL).toByte()
            buf[size++] = ((v ushr 8) and 0xFFL).toByte()
            buf[size++] = (v and 0xFFL).toByte()
        }

        fun writeBytes(bytes: ByteArray) {
            ensureCapacity(bytes.size)
            bytes.copyInto(buf, destinationOffset = size)
            size += bytes.size
        }

        fun writeDomainName(domain: String) {
            val normalized = normalizeDomain(domain)
            if (normalized == ".") {
                writeU8(0)
                return
            }
            val labels = normalized.removeSuffix(".").split('.')
            for (label in labels) {
                val bytes = label.encodeToByteArray()
                require(bytes.isNotEmpty() && bytes.size <= MAX_LABEL_BYTES) {
                    "DNS label '$label' byte length must be 1..63 (got ${bytes.size})"
                }
                writeU8(bytes.size)
                writeBytes(bytes)
            }
            writeU8(0)
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private class DnsReader(
        private val bytes: ByteArray,
        val limit: Int,
    ) {
        var position: Int = 0

        fun readU8(): Int {
            require(position + 1 <= limit) { "Unexpected EOF reading U8 at $position" }
            return bytes[position++].toInt() and 0xFF
        }

        fun readU16(): Int {
            require(position + 2 <= limit) { "Unexpected EOF reading U16 at $position" }
            val hi = bytes[position++].toInt() and 0xFF
            val lo = bytes[position++].toInt() and 0xFF
            return (hi shl 8) or lo
        }

        fun readU32(): Long {
            require(position + 4 <= limit) { "Unexpected EOF reading U32 at $position" }
            val b0 = bytes[position++].toLong() and 0xFFL
            val b1 = bytes[position++].toLong() and 0xFFL
            val b2 = bytes[position++].toLong() and 0xFFL
            val b3 = bytes[position++].toLong() and 0xFFL
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun readBytes(count: Int): ByteArray {
            require(count >= 0 && position + count <= limit) {
                "Unexpected EOF reading $count bytes at $position"
            }
            val slice = bytes.copyOfRange(position, position + count)
            position += count
            return slice
        }

        fun readDomainName(): String {
            val labels = mutableListOf<String>()
            var cur = position
            var jumped = false
            var jumps = 0
            val visitedOffsets = mutableSetOf<Int>()

            while (true) {
                require(cur < limit) { "Unexpected EOF in DNS domain name at offset $cur" }
                val len = bytes[cur].toInt() and 0xFF
                if (len == 0) {
                    if (!jumped) {
                        position = cur + 1
                    }
                    break
                }
                val mask = len and 0xC0
                if (mask == 0xC0) {
                    require(cur + 1 < limit) { "Truncated DNS compression pointer at $cur" }
                    val b2 = bytes[cur + 1].toInt() and 0xFF
                    val targetOffset = ((len and 0x3F) shl 8) or b2
                    require(targetOffset in 12 until cur && targetOffset < limit) {
                        "Invalid DNS compression pointer offset $targetOffset from $cur"
                    }
                    require(visitedOffsets.add(targetOffset)) {
                        "Cyclic DNS compression pointer detected at offset $targetOffset"
                    }
                    jumps++
                    require(jumps <= MAX_COMPRESSION_JUMPS) {
                        "Exceeded maximum DNS compression jumps ($MAX_COMPRESSION_JUMPS)"
                    }
                    if (!jumped) {
                        position = cur + 2
                        jumped = true
                    }
                    cur = targetOffset
                } else if (mask == 0x00) {
                    cur++
                    require(cur + len <= limit) {
                        "Truncated DNS label of length $len at offset $cur"
                    }
                    val label = bytes.copyOfRange(cur, cur + len).decodeToString()
                    labels.add(label)
                    cur += len
                } else {
                    throw IllegalArgumentException("Unsupported DNS label type bits: 0x${len.toString(16)}")
                }
            }

            return if (labels.isEmpty()) "." else "${labels.joinToString(".")}."
        }
    }
}
