package ec0.core.localsend

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Device type category.
 *
 * Wire format: lowercase strings ("mobile", "desktop", …).
 * Reference: `packages/core/src/model/discovery.rs` (`DeviceType`,
 * `device_type_v2` codec) — v2 uses lowercase on the wire while the enum
 * names are SCREAMING_SNAKE internally.
 */
@Serializable(with = DeviceTypeSerializer::class)
enum class DeviceType(val wire: String) {
    MOBILE("mobile"),
    DESKTOP("desktop"),
    WEB("web"),
    HEADLESS("headless"),
    SERVER("server");

    companion object {
        /**
         * Protocol v2 section 7.1: unknown device type values fall back to
         * DESKTOP (verified by the reference implementation's
         * `test_device_type_unknown_falls_back_to_desktop`).
         */
        fun fromWireLenient(value: String): DeviceType = when (value.lowercase()) {
            "mobile" -> MOBILE
            "desktop" -> DESKTOP
            "web" -> WEB
            "headless" -> HEADLESS
            "server" -> SERVER
            else -> DESKTOP
        }
    }
}

/** Lenient lowercase codec for [DeviceType] with the §7.1 unknown fallback. */
object DeviceTypeSerializer : KSerializer<DeviceType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ec0.core.localsend.DeviceType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): DeviceType =
        DeviceType.fromWireLenient(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: DeviceType) {
        encoder.encodeString(value.wire)
    }
}

/**
 * Transport protocol type announced by a device ("http" / "https" on the wire).
 *
 * Strict decode: unknown values throw — matches the reference Rust codec
 * (`protocol_type_v2::deserialize` errors on unknown variants).
 */
@Serializable
enum class ProtocolType(val wire: String) {
    @SerialName("http") HTTP,
    @SerialName("https") HTTPS;
}
