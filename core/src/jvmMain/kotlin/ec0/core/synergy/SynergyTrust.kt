/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned-cert TLS trust for the Synergy WebSocket channel — models upstream
 * trustManagerForCertificates (WSCM:390-410) + the custom hostname verifier
 * (obfuscated `oq3`, WSCM:466). Trust anchor: the certificate extracted from
 * the OPSynergy APK (assets/server.cer, vendored at
 * docs/re/certs/server_computer.pem) — agent-0 confirmed BOTH profiles ship
 * an identical cert: X.509 CN=192.168.137.1 (SoftAP gateway), O=oppo,
 * SHA-256 A8:92:CC:AC:B0:EC:60:BF:53:94:45:DD:D3:CE:2D:24:60:22:47:F4:81:
 * 2F:E8:17:A9:46:0C:CF:87:F5:68:D9, valid 2020-07-29 → 2030-07-27.
 *
 * Security model: SHA-256 FINGERPRINT pinning (chain trust is meaningless —
 * self-signed OEM cert). Hostname verification is intentionally disabled at
 * the SSLSocket layer (endpointIdentificationAlgorithm = null — raw sockets
 * default) because the cert CN names the phone's hotspot gateway IP while
 * peers also connect over Wi-Fi Direct / LAN addresses; upstream did the
 * same (custom permissive verifier).
 */
package ec0.core.synergy

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object SynergyTrust {

    /** SHA-256 of the pinned certificate (DER), lowercase hex, no separators. */
    const val PINNED_FINGERPRINT: String =
        "a892ccacb0ec60bf539445ddd3ce2d246022" + "47f4812fe817a9460ccf87f568d9"

    /**
     * Trust anchor PEM — extracted from the OPSynergy APK assets (server.cer,
     * DER) and vendored at docs/re/certs/server_computer.pem. Identical cert
     * ships as server_pad.cer for the pad profile.
     */
    const val PINNED_SERVER_PEM: String = """
-----BEGIN CERTIFICATE-----
MIIDijCCAnICAQEwDQYJKoZIhvcNAQELBQAwgYoxCzAJBgNVBAYTAmNuMRAwDgYD
VQQIDAdqaWFuZ3N1MRAwDgYDVQQHDAduYW5qaW5nMQ0wCwYDVQQKDARvcHBvMRYw
FAYDVQQLDA0xOTIuMTY4LjEzNy4xMRYwFAYDVQQDDA0xOTIuMTY4LjEzNy4xMRgw
FgYJKoZIhvcNAQkBFglvcHBvQG9wcG8wHhcNMjAwNzI5MDMwMjM2WhcNMzAwNzI3
MDMwMjM2WjCBijELMAkGA1UEBhMCY24xEDAOBgNVBAgMB2ppYW5nc3UxEDAOBgNV
BAcMB25hbmppbmcxDTALBgNVBAoMBG9wcG8xFjAUBgNVBAsMDTE5Mi4xNjguMTM3
LjExFjAUBgNVBAMMDTE5Mi4xNjguMTM3LjExGDAWBgkqhkiG9w0BCQEWCW9wcG9A
b3BwbzCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALzxsKNSQZcyWJPt
VDtoV6pc5jiVOrFEeUzCsmhE4IMC0KkPrrCel15509jr22SVw6vhkeFi3E9KP7J2
Vg4RA6BTlrnXjdX1qKM6ZIcKSbrSTrBzKM5USgzqV4/4FqX0EAhhTcpplAjVkm+2
2glZ4h9z6dy/zq/n7p/s5kzOtzhFtIQd+vq8OoEDzSYNiQ1a1qKoGOj3WMcG3SZR
OG5GI+EXfjXEKXaWbKaSK/MCarkVDN5FJQjjZtX7YfAuzxoUHkMUbexOrrzaX3hq
il/d787liI4Du/g+p9WFGEQtEMyEIjzmGi5lCsegj2WOXjbDQrgdxd2ydINykVMP
J1kFEiMCAwEAATANBgkqhkiG9w0BAQsFAAOCAQEAbkwN3/Ppkpi3xRimwSq6hvKf
OeC/SxevJ3EqjbHOnlVdrj3CjinaHlsvBAamWN+ZdOWfB7bfSfOtA6NDoVu80dmr
67hpGsborvdHXC2eHy7KltCRJrMfGMsiS948njzqbC+CoMW8iQmc98PEzcDCFXbn
Cdl3ME6whIk5w0s2/PSQXzqPYlgROHngBbQyImzcKocpIUgciFNYkxwJZyu//jnu
t9sa/RaEM+ZK2r0xkOfyPw0GOUcP53tBfBGGsgjh2ow+ro6cWB5w8GmL1N+gb+gU
COYQx0HIqEWEkQkvs7oNMux9E6XCJWTbgvc1bV2StsC0kf53y/t/hrR5+8SJNg==
-----END CERTIFICATE-----
"""

    fun pinnedCertificate(): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        val pemBody = PINNED_SERVER_PEM
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s"), "") // strip newlines + raw-string indentation
        return factory.generateCertificate(ByteArrayInputStream(pemBody.toByteArray())) as X509Certificate
    }

    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
            "%02x".format(it)
        }

    /** Trust manager that accepts ONLY the pinned certificate fingerprint. */
    fun pinnedTrustManager(): X509TrustManager = object : X509TrustManager {
        private val pinned = pinnedCertificate()

        private fun check(chain: Array<out X509Certificate>?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("Empty certificate chain")
            if (sha256Fingerprint(leaf) != PINNED_FINGERPRINT) {
                throw CertificateException("Peer certificate is not the pinned Synergy server cert")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(pinned)
    }

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(pinnedTrustManager()), null)
    }

    fun sslSocketFactory(): SSLSocketFactory = sslContext().socketFactory

    /**
     * Applies upstream-parity settings to a raw SSL socket: no hostname
     * endpoint identification (oq3 analog) and TLS 1.2+.
     */
    fun configure(socket: SSLSocket) {
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = null // permissive verifier (upstream oq3)
        socket.sslParameters = params
        socket.enabledProtocols = socket.supportedProtocols.filter { it.startsWith("TLSv1") }.toTypedArray()
    }
}
