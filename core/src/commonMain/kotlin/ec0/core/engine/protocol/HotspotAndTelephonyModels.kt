/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Instant Hotspot (`category = "net"`, `cmd_type = "hotspot_control"`,
 * `info_type = "hotspot_status"`) and Telephony Call/SMS Handoff
 * (`category = "telephony"`, `info_type = "call_state"`, `cmd_type = "call_action"`)
 * wire models.
 */
package ec0.core.engine.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object HotspotAndTelephonyProtocol {
    const val CATEGORY_NET: String = "net"
    const val CMD_HOTSPOT_CONTROL: String = "hotspot_control"
    const val INFO_HOTSPOT_STATUS: String = "hotspot_status"

    const val CATEGORY_TELEPHONY: String = "telephony"
    const val INFO_CALL_STATE: String = "call_state"
    const val CMD_CALL_ACTION: String = "call_action"
}

data class HotspotControlCommand(
    val action: String,
    val preferredBandMhz: Int = 5180,
) {
    init {
        require(action in VALID_ACTIONS) { "Unsupported hotspot action: '$action'" }
        require(preferredBandMhz in 2400..7125) { "Invalid Wi-Fi band MHz: $preferredBandMhz" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("band_mhz", preferredBandMhz)
    }

    companion object {
        const val ACTION_ENABLE: String = "enable"
        const val ACTION_DISABLE: String = "disable"
        const val ACTION_QUERY: String = "query"
        val VALID_ACTIONS: Set<String> = setOf(ACTION_ENABLE, ACTION_DISABLE, ACTION_QUERY)

        fun fromJsonObject(obj: JsonObject): HotspotControlCommand {
            val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in hotspot_control")
            val band = (obj["band_mhz"] as? JsonPrimitive)?.intOrNull ?: 5180
            return HotspotControlCommand(action = action, preferredBandMhz = band)
        }
    }
}

data class HotspotStatusInfo(
    val enabled: Boolean,
    val ssid: String? = null,
    val passphrase: String? = null,
    val gatewayIp: String = "192.168.137.1",
    val bandMhz: Int = 5180,
    val connectedClients: Int = 0,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("band_mhz", bandMhz)
        put("clients", connectedClients)
        put("enabled", enabled)
        put("gateway_ip", gatewayIp)
        if (passphrase != null) put("passphrase", passphrase)
        if (ssid != null) put("ssid", ssid)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): HotspotStatusInfo = HotspotStatusInfo(
            enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ssid = (obj["ssid"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            passphrase = (obj["passphrase"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            gatewayIp = (obj["gateway_ip"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "192.168.137.1",
            bandMhz = (obj["band_mhz"] as? JsonPrimitive)?.intOrNull ?: 5180,
            connectedClients = (obj["clients"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }
}

enum class PhoneCallState(val wireName: String) {
    RINGING("ringing"),
    ACTIVE("active"),
    ENDED("ended"),
    MISSED("missed");

    companion object {
        fun fromWireName(wire: String): PhoneCallState =
            entries.firstOrNull { it.wireName == wire.lowercase() }
                ?: throw IllegalArgumentException("Unknown call state: '$wire'")
    }
}

data class CallStateInfo(
    val callId: String,
    val state: PhoneCallState,
    val callerNumber: String,
    val callerName: String = "",
    val timestampMs: Long,
) {
    init {
        require(callId.isNotBlank()) { "callId must not be blank" }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("call_id", callId)
        put("caller_name", callerName)
        put("caller_number", callerNumber)
        put("state", state.wireName)
        put("ts_ms", timestampMs)
    }

    companion object {
        fun fromJsonObject(obj: JsonObject): CallStateInfo = CallStateInfo(
            callId = (obj["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'call_id' in call_state"),
            state = PhoneCallState.fromWireName(
                (obj["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "ended",
            ),
            callerNumber = (obj["caller_number"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            callerName = (obj["caller_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            timestampMs = (obj["ts_ms"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }
}

data class CallActionCommand(
    val callId: String,
    val action: String,
    val smsReplyText: String? = null,
) {
    init {
        require(callId.isNotBlank()) { "callId must not be blank" }
        require(action in VALID_ACTIONS) { "Unsupported call action: '$action'" }
        if (action == ACTION_REJECT_WITH_SMS) {
            require(!smsReplyText.isNullOrBlank()) { "smsReplyText required for reject_sms" }
        }
    }

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("action", action)
        put("call_id", callId)
        if (smsReplyText != null) put("sms_text", smsReplyText)
    }

    companion object {
        const val ACTION_ANSWER_SPEAKER: String = "answer_speaker"
        const val ACTION_REJECT: String = "reject"
        const val ACTION_END_CALL: String = "end_call"
        const val ACTION_REJECT_WITH_SMS: String = "reject_sms"

        val VALID_ACTIONS: Set<String> = setOf(
            ACTION_ANSWER_SPEAKER,
            ACTION_REJECT,
            ACTION_END_CALL,
            ACTION_REJECT_WITH_SMS,
        )

        fun fromJsonObject(obj: JsonObject): CallActionCommand = CallActionCommand(
            callId = (obj["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'call_id' in call_action"),
            action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Missing 'action' in call_action"),
            smsReplyText = (obj["sms_text"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }
}
