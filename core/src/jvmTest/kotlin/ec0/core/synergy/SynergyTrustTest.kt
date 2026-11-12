/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned-trust tests — the vendored anchor (docs/re/certs/server_computer.pem)
 * must parse, match agent-0's recorded fingerprint, and the trust manager
 * must reject any other certificate (represented here by the kdeconnect
 * upstream test cert from T6's fixture set).
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

    // Foreign cert = kdeconnect-android PairingHandlerTest certA (EC P-256) —
    // any valid-but-different certificate exercises the rejection path.
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
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(body.toByteArray())) as java.security.cert.X509Certificate
    }

    @Test
    fun pinnedCertParsesAndMatchesRecordedFingerprint() {
        val cert = SynergyTrust.pinnedCertificate()
        assertEquals("CN=192.168.137.1", cert.subjectX500Principal.getName().split(",").last().trim())
        assertEquals(SynergyTrust.PINNED_FINGERPRINT, SynergyTrust.sha256Fingerprint(cert))
        assertEquals("a892ccacb0ec60bf539445ddd3ce2d24602247f4812fe817a9460ccf87f568d9", SynergyTrust.PINNED_FINGERPRINT)
        // Validity window covers the present (2020-07-29 → 2030-07-27)
        assertTrue(cert.notBefore.time < System.currentTimeMillis())
        assertTrue(System.currentTimeMillis() < cert.notAfter.time)
    }

    @Test
    fun trustManagerAcceptsPinnedCert() {
        val tm = SynergyTrust.pinnedTrustManager()
        val pinned = SynergyTrust.pinnedCertificate()
        tm.checkServerTrusted(arrayOf(pinned), "RSA")
        tm.checkClientTrusted(arrayOf(pinned), "RSA")
        assertTrue(tm.acceptedIssuers.contains(pinned))
    }

    @Test
    fun trustManagerRejectsForeignCert() {
        val tm = SynergyTrust.pinnedTrustManager()
        val foreign = parseCert(foreignCertPem)
        assertFailsWith<CertificateException> {
            tm.checkServerTrusted(arrayOf(foreign), "EC")
        }
        assertFailsWith<CertificateException> {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
    }

    @Test
    fun sslContextBuilds() {
        val context = SynergyTrust.sslContext()
        assertTrue(context.protocol.startsWith("TLS"))
    }
}
