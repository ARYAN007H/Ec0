package ec0.core.localsend

import ec0.core.localsend.dto.FileDto
import ec0.core.localsend.dto.FileMetadata
import ec0.core.localsend.dto.InfoResponseDtoV2
import ec0.core.localsend.dto.MulticastMessageV2
import ec0.core.localsend.dto.PrepareDownloadResponseDtoV2
import ec0.core.localsend.dto.PrepareUploadRequestDtoV2
import ec0.core.localsend.dto.PrepareUploadResponseDtoV2
import ec0.core.localsend.dto.RegisterDtoV2
import ec0.core.localsend.dto.RegisterResponseDtoV2
import ec0.core.localsend.security.LocalsendSecurity
import ec0.core.localsend.security.PinDecision
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden-fixture roundtrip tests. Fixture JSONs come from the reference
 * implementation's own test suite (`packages/core/src/http/dto_v2.rs`,
 * `packages/core/src/model/discovery.rs`).
 */
class LocalsendV2DtoTest {

    // -- RegisterDtoV2 -------------------------------------------------------

    @Test
    fun registerDto_deserializesGoldenJson() {
        val json = """
            {
              "alias": "Secret Banana",
              "version": "2.0",
              "deviceModel": "Windows",
              "deviceType": "desktop",
              "fingerprint": "random string",
              "port": 53317,
              "protocol": "https",
              "download": true
            }
        """.trimIndent()

        val dto = LocalsendV2.json.decodeFromString<RegisterDtoV2>(json)
        assertEquals("Secret Banana", dto.alias)
        assertEquals("2.0", dto.version)
        assertEquals("Windows", dto.deviceModel)
        assertEquals(DeviceType.DESKTOP, dto.deviceType)
        assertEquals("random string", dto.fingerprint)
        assertEquals(53317, dto.port)
        assertEquals(ProtocolType.HTTPS, dto.protocol)
        assertTrue(dto.download)
    }

    @Test
    fun registerDto_unknownDeviceTypeFallsBackToDesktop() {
        // protocol section 7.1, verified by reference test "fridge" -> desktop
        val json = """
            {
              "alias": "Test Device",
              "version": "2.0",
              "deviceType": "fridge",
              "fingerprint": "abc123",
              "port": 53317,
              "protocol": "http"
            }
        """.trimIndent()
        val dto = LocalsendV2.json.decodeFromString<RegisterDtoV2>(json)
        assertEquals(DeviceType.DESKTOP, dto.deviceType)
    }

    @Test
    fun registerDto_serializationOmitsNullsAndAlwaysEmitsDownload() {
        val dto = RegisterDtoV2(
            alias = "Sender",
            version = LocalsendV2.PROTOCOL_VERSION,
            fingerprint = "fp",
            port = LocalsendV2.DEFAULT_SERVER_PORT,
            protocol = ProtocolType.HTTPS,
        )
        val json = LocalsendV2.json.encodeToString(dto)
        assertFalse(json.contains("deviceModel"))
        assertFalse(json.contains("deviceType"))
        assertTrue(json.contains("\"download\":false"))
        assertTrue(json.contains("\"protocol\":\"https\""))
    }

    @Test
    fun registerDto_roundtripWithUnknownKeyIgnored() {
        val json = """
            {
              "alias": "A",
              "version": "2.2",
              "fingerprint": "f",
              "port": 1,
              "protocol": "http",
              "download": false,
              "someFutureField": 42
            }
        """.trimIndent()
        val dto = LocalsendV2.json.decodeFromString<RegisterDtoV2>(json)
        assertEquals("A", dto.alias)
        assertEquals(dto, LocalsendV2.json.decodeFromString<RegisterDtoV2>(LocalsendV2.json.encodeToString(dto)))
    }

    // -- RegisterResponseDtoV2 -------------------------------------------------

    @Test
    fun registerResponse_minimalJsonUsesDefaults() {
        val json = """{"alias": "Test Device", "version": "2.0", "fingerprint": "abc123"}"""
        val dto = LocalsendV2.json.decodeFromString<RegisterResponseDtoV2>(json)
        assertFalse(dto.download)
        assertEquals("", dto.fingerprint)
        assertNull(dto.deviceModel)
    }

    // -- MulticastMessageV2 ----------------------------------------------------

    @Test
    fun multicastMessage_serializesExactFieldNames() {
        val msg = MulticastMessageV2(
            alias = "Nice Orange",
            version = "2.2",
            deviceModel = "Samsung",
            deviceType = DeviceType.MOBILE,
            fingerprint = "random string",
            port = 53317,
            protocol = ProtocolType.HTTPS,
            download = true,
        )
        val json = LocalsendV2.json.encodeToString(msg)
        assertTrue(json.contains("\"alias\":\"Nice Orange\""))
        assertTrue(json.contains("\"version\":\"2.2\""))
        assertTrue(json.contains("\"fingerprint\":\"random string\""))
        assertTrue(json.contains("\"download\":true"))
        assertTrue(json.contains("\"protocol\":\"https\""))
        assertTrue(json.contains("\"deviceType\":\"mobile\""))
    }

    @Test
    fun multicastMessage_deserializesWithoutOptionalFields() {
        val json = """
            {
              "alias": "Secret Banana",
              "version": "2.2",
              "fingerprint": "random string",
              "port": 53317,
              "protocol": "http"
            }
        """.trimIndent()
        val msg = LocalsendV2.json.decodeFromString<MulticastMessageV2>(json)
        assertNull(msg.deviceModel)
        assertNull(msg.deviceType)
        assertEquals(ProtocolType.HTTP, msg.protocol)
        assertFalse(msg.download)
    }

    // -- FileDto ----------------------------------------------------------------

    @Test
    fun fileDto_roundtripWithMetadata() {
        val dto = FileDto(
            id = "file1",
            fileName = "test.png",
            size = 1024,
            fileType = "image/png",
            sha256 = "deadbeef",
            metadata = FileMetadata(modified = "2024-01-01T00:00:00Z", accessed = "2024-01-02T00:00:00Z"),
        )
        val json = LocalsendV2.json.encodeToString(dto)
        assertTrue(json.contains("\"fileName\":\"test.png\""))
        assertTrue(json.contains("\"fileType\":\"image/png\""))
        assertTrue(json.contains("\"sha256\":\"deadbeef\""))
        assertTrue(json.contains("\"modified\":\"2024-01-01T00:00:00Z\""))
        assertEquals(dto, LocalsendV2.json.decodeFromString(json))
    }

    // -- Prepare upload/download -------------------------------------------------

    @Test
    fun prepareUploadRequest_matchesReferenceShape() {
        val request = PrepareUploadRequestDtoV2(
            info = RegisterDtoV2(
                alias = "Sender",
                version = "2.2",
                fingerprint = "sender-fingerprint",
                port = 53317,
                protocol = ProtocolType.HTTPS,
            ),
            files = mapOf(
                "file1" to FileDto(id = "file1", fileName = "test.png", size = 1024, fileType = "image/png"),
            ),
        )
        val json = LocalsendV2.json.encodeToString(request)
        assertTrue(json.contains("\"info\""))
        assertTrue(json.contains("\"files\""))
        assertTrue(json.contains("\"fingerprint\":\"sender-fingerprint\""))
        assertEquals(request, LocalsendV2.json.decodeFromString(json))
    }

    @Test
    fun prepareUploadResponse_roundtrip() {
        val dto = PrepareUploadResponseDtoV2(sessionId = "session-1", files = mapOf("file1" to "token-1"))
        val json = LocalsendV2.json.encodeToString(dto)
        assertTrue(json.contains("\"sessionId\":\"session-1\""))
        assertEquals(dto, LocalsendV2.json.decodeFromString(json))
    }

    @Test
    fun prepareDownloadResponse_roundtrip() {
        val dto = PrepareDownloadResponseDtoV2(
            info = InfoResponseDtoV2(
                alias = "Nice Orange",
                version = "2.2",
                fingerprint = "cert-sha256-hex",
                download = true,
            ),
            sessionId = "session-2",
            files = mapOf("file2" to FileDto(id = "file2", fileName = "a.pdf", size = 8, fileType = "application/pdf")),
        )
        val json = LocalsendV2.json.encodeToString(dto)
        assertTrue(json.contains("\"sessionId\":\"session-2\""))
        assertTrue(json.contains("\"a.pdf\""))
        assertEquals(dto, LocalsendV2.json.decodeFromString(json))
    }

    // -- Route contract ------------------------------------------------------------

    @Test
    fun routeTable_hasAllSevenV2Endpoints() {
        val paths = ec0.core.localsend.api.LocalsendV2Route.entries.map { it.path }
        listOf(
            "/api/localsend/v2/register",
            "/api/localsend/v2/prepare-upload",
            "/api/localsend/v2/upload",
            "/api/localsend/v2/cancel",
            "/api/localsend/v2/prepare-download",
            "/api/localsend/v2/download",
            "/api/localsend/v2/info",
        ).forEach { assertTrue(paths.contains(it), "missing $it") }
        assertEquals(
            listOf("sessionId", "fileId", "token"),
            ec0.core.localsend.api.LocalsendV2Route.UPLOAD.requiredQuery,
        )
        assertEquals(
            listOf("sessionId", "fileId"),
            ec0.core.localsend.api.LocalsendV2Route.DOWNLOAD.requiredQuery,
        )
    }

    // -- PIN model -------------------------------------------------------------------

    @Test
    fun pinModel_followsReferenceCheckPin() {
        // No PIN required -> always allowed
        assertEquals(PinDecision.ALLOWED, LocalsendSecurity.evaluatePin(null, null, 0))
        // Correct PIN allowed, wrong PIN denied
        assertEquals(PinDecision.ALLOWED, LocalsendSecurity.evaluatePin("1234", "1234", 2))
        assertEquals(PinDecision.DENIED_WRONG_PIN, LocalsendSecurity.evaluatePin("1234", "wrong", 0))
        assertEquals(PinDecision.DENIED_WRONG_PIN, LocalsendSecurity.evaluatePin("1234", null, 0))
        // Limit reached -> blocked regardless of supplied pin
        assertEquals(PinDecision.BLOCKED_TOO_MANY_ATTEMPTS, LocalsendSecurity.evaluatePin("1234", "1234", 3))
    }
}
