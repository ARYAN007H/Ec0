/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned-trust tests — BOTH vendored anchors (computer + pad, distinct certs
 * per PR #7 review) must parse and match their recorded fingerprints; the
 * trust managers accept their own profile's cert and reject the other
 * profile's + any third-party certificate.
 */
package ec0.core.synergy

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SynergyTrustTest {

    // Third-party cert = kdeconnect-android PairingHandlerTest certA (EC P-256)
    private val foreignCertPem = """
        -----BEGIN CERTIFICATE-----
        MIIBkzCCATmgAwIBAgIBATAKBggqhkjOPQQDBDBTMS0wKwYDVQQDDCRlZTA2MWE3NV9lNDAzXzRlY2NfOTI2
        MV81ZmZlMjcyMmY2OTgxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwHhcNMjMwOTE1MjIw
        MDAwWhcNMzQwOTE1MjIwMDAwWjBTMS0wKwYDVQQDDCRlZTA2MWE3NV9lNDAzXzRlY2NfOTI2MV81ZmZlMjcy
        MmY2OTgxFDASBgNVBAsMC0tERSBDb25uZWN0MQwwCgYDVQQKDANLREUwWTATBgcqhkjOPQIBBggqhkjOPQMB
        BwNCAASqOIKTm5j6x8DKgYSkItLmjCgIXP0gkOW6bmVvloDGsYnvqYLMFGe7YW8g8lT/qPBTEfDOM4UpQ8X6
        jidE+XrnMAoGCCqGSM49BAMEA0gAMEUCIEpk6VNpbt3tfbWDf0TmoJftRq3wAs3Dke7d5vMZlivyAiEA/ZXt
        SRqPjs/2RN9SynKhSUA9/z0PNq6LYoAaC6TdomM=
        -----END CERTIFICATE-----
    """.trimIndent()

    private fun parseCert(pem: String): java.security.cert.X509Certificate {
        val body = pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s"), "")
        val der = java.util.Base64.getMimeDecoder().decode(body)
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(der)) as java.security.cert.X509Certificate
    }

    @Test
    fun computerCertParsesAndMatchesRecordedFingerprint() {
        val cert = SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.COMPUTER)
        assertTrue(cert.subjectX500Principal.getName().contains("CN=192.168.137.1"))
        assertEquals(SynergyTrust.PINNED_FINGERPRINT_COMPUTER, SynergyTrust.sha256Fingerprint(cert))
        assertEquals("a892ccacb0ec60bf539445ddd3ce2d24602247f4812fe817a9460ccf87f568d9", SynergyTrust.PINNED_FINGERPRINT_COMPUTER)
        assertTrue(cert.notBefore.time < System.currentTimeMillis())
        assertTrue(System.currentTimeMillis() < cert.notAfter.time)
    }

    @Test
    fun padCertParsesAndMatchesRecordedFingerprint() {
        val cert = SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.PAD)
        assertTrue(cert.subjectX500Principal.getName().contains("CN=192.168.49.1"))
        assertEquals(SynergyTrust.PINNED_FINGERPRINT_PAD, SynergyTrust.sha256Fingerprint(cert))
        assertEquals("afc50cd2ce6db8e45d461588da376c2ba307d8f61628a6d7dbf0ac7566c1f0ea", SynergyTrust.PINNED_FINGERPRINT_PAD)
    }

    @Test
    fun profilesPinDistinctCertificates() {
        val computer = SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.COMPUTER)
        val pad = SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.PAD)
        assertTrue(!computer.encoded.contentEquals(pad.encoded))
    }

    @Test
    fun trustManagerAcceptsOwnProfileCert() {
        val computerTm = SynergyTrust.pinnedTrustManager(SynergyWsClient.Profile.COMPUTER)
        val padTm = SynergyTrust.pinnedTrustManager(SynergyWsClient.Profile.PAD)
        computerTm.checkServerTrusted(arrayOf(SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.COMPUTER)), "RSA")
        padTm.checkServerTrusted(arrayOf(SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.PAD)), "RSA")
        assertTrue(computerTm.acceptedIssuers.contains(SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.COMPUTER)))
    }

    @Test
    fun trustManagerRejectsWrongProfileAndForeignCerts() {
        val computerTm = SynergyTrust.pinnedTrustManager(SynergyWsClient.Profile.COMPUTER)
        val pad = SynergyTrust.pinnedCertificate(SynergyWsClient.Profile.PAD)
        val foreign = parseCert(foreignCertPem)

        // Cross-profile: the pad cert must NOT satisfy the computer trust manager
        assertFailsWith<CertificateException> { computerTm.checkServerTrusted(arrayOf(pad), "RSA") }
        assertFailsWith<CertificateException> { computerTm.checkServerTrusted(arrayOf(foreign), "EC") }
        assertFailsWith<CertificateException> { computerTm.checkServerTrusted(emptyArray(), "RSA") }
    }

    @Test
    fun sslContextBuildsForBothProfiles() {
        assertTrue(SynergyTrust.sslContext(SynergyWsClient.Profile.COMPUTER).protocol.startsWith("TLS"))
        assertTrue(SynergyTrust.sslContext(SynergyWsClient.Profile.PAD).protocol.startsWith("TLS"))
    }
}
