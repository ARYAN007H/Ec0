/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Ported from kdeconnect-android (DeviceInfo.kt + DeviceHelper.kt name
 * filtering, upstream SPDX: GPL-2.0-only OR GPL-3.0-only OR
 * LicenseRef-KDE-Accepted-GPL; GPL-3.0-only option taken). Identity packet
 * body keys are 1:1 with upstream: deviceId, deviceName, protocolVersion,
 * deviceType, incomingCapabilities, outgoingCapabilities (+ tcpPort added by
 * the LAN discovery layer, see Discovery.kt).
 */
package ec0.core.kdeconnect

enum class DeviceType(val wireName: String) {
    PHONE("phone"),
    TABLET("tablet"),
    DESKTOP("desktop"),
    LAPTOP("laptop"),
    TV("tv");

    override fun toString(): String = wireName

    companion object {
        fun fromString(s: String): DeviceType = when (s) {
            "phone" -> PHONE
            "tablet" -> TABLET
            "tv" -> TV
            "laptop" -> LAPTOP
            else -> DESKTOP
        }
    }
}

/**
 * The properties needed to describe a KDE Connect device. Certificate
 * handling is platform-layer (X.509 parsing/generation needs java.security /
 * bouncycastle); this model carries everything that travels on the wire.
 */
data class DeviceIdentity(
    val id: String,
    val name: String,
    val type: DeviceType,
    /** Defaults to the current protocol version (upstream DeviceHelper.PROTOCOL_VERSION). */
    val protocolVersion: Int = PROTOCOL_VERSION,
    val incomingCapabilities: Set<String>? = null,
    val outgoingCapabilities: Set<String>? = null,
    /** Present when the identity packet advertises a TCP listener (discovery). */
    val tcpPort: Int? = null,
    /**
     * TCP handshake only (upstream LanLinkProvider:259-261): the id of the
     * device this connection was opened for, and the peer's protocolVersion
     * echoed back for a pre-connect compatibility check (:180-187).
     */
    val targetDeviceId: String? = null,
    val targetProtocolVersion: Int? = null,
) {
    /**
     * Serializes to a NetworkPacket — 1:1 with upstream DeviceInfo.toIdentityPacket()
     * (+ the TCP handshake fields upstream adds at LanLinkProvider:259-261).
     * The certificate is intentionally not part of the packet; peers read it
     * from the TLS session (upstream: "LanLink can query that from the socket").
     */
    fun toIdentityPacket(): NetworkPacket = NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY).also { np ->
        np["deviceId"] = id
        np["deviceName"] = name
        np["protocolVersion"] = protocolVersion
        np["deviceType"] = type.toString()
        if (incomingCapabilities != null) np["incomingCapabilities"] = incomingCapabilities
        if (outgoingCapabilities != null) np["outgoingCapabilities"] = outgoingCapabilities
        if (tcpPort != null) np["tcpPort"] = tcpPort
        if (targetDeviceId != null) np["targetDeviceId"] = targetDeviceId
        if (targetProtocolVersion != null) np["targetProtocolVersion"] = targetProtocolVersion.toString()
    }

    companion object {
        /**
         * Current KDE Connect protocol version — upstream
         * `DeviceHelper.PROTOCOL_VERSION` (DeviceHelper.kt:31). Version 8
         * gates the pairing-timestamp check and the newer verification key
         * (see Pairing.kt); identities advertising 0 take the legacy path.
         */
        const val PROTOCOL_VERSION = 8

        // Upstream DeviceHelper: strip punctuation, trim, cap at 32 chars.
        private val NAME_INVALID_CHARACTERS_REGEX = "[\"',;:.!?()\\[\\]<>]".toRegex()
        const val MAX_DEVICE_NAME_LENGTH = 32

        fun filterInvalidCharactersFromDeviceNameAndLimitLength(input: String): String =
            input.replace(NAME_INVALID_CHARACTERS_REGEX, "").trim().take(MAX_DEVICE_NAME_LENGTH)

        // Upstream DeviceInfo.DEVICE_ID_REGEX
        private val DEVICE_ID_REGEX = "^[a-zA-Z0-9_-]{32,38}$".toRegex()

        fun isValidDeviceId(deviceId: String): Boolean = deviceId.matches(DEVICE_ID_REGEX)

        /**
         * Rebuilds a DeviceIdentity from an identity packet — 1:1 with upstream
         * DeviceInfo.fromIdentityPacketAndCert() minus the certificate (+ the
         * TCP handshake fields upstream sets at LanLinkProvider:259-261).
         */
        fun fromIdentityPacket(identityPacket: NetworkPacket): DeviceIdentity = with(identityPacket) {
            DeviceIdentity(
                id = getString("deviceId"),
                name = filterInvalidCharactersFromDeviceNameAndLimitLength(getString("deviceName", "unknown")),
                type = DeviceType.fromString(getString("deviceType", "desktop")),
                protocolVersion = getInt("protocolVersion"),
                incomingCapabilities = getStringSet("incomingCapabilities"),
                outgoingCapabilities = getStringSet("outgoingCapabilities"),
                tcpPort = getIntOrNull("tcpPort"),
                // Upstream writes targetProtocolVersion as a string (echo of the
                // peer's protocolVersion) — the int accessor coerces it back.
                targetDeviceId = getStringOrNull("targetDeviceId"),
                targetProtocolVersion = getIntOrNull("targetProtocolVersion"),
            )
        }

        /**
         * Upstream DeviceInfo.isValidIdentityPacket(): type must be identity,
         * sanitized name must be non-blank, device id must match the id regex.
         */
        fun isValidIdentityPacket(identityPacket: NetworkPacket): Boolean = with(identityPacket) {
            type == NetworkPacket.PACKET_TYPE_IDENTITY &&
                filterInvalidCharactersFromDeviceNameAndLimitLength(getString("deviceName", "")).isNotBlank() &&
                isValidDeviceId(getString("deviceId", ""))
        }
    }
}
