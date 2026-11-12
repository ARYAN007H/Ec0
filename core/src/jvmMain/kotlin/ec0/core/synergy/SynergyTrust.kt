/*
 * Part of ec0 — SPDX-License-Identifier: GPL-3.0-only
 *
 * Pinned-cert TLS trust for the Synergy WebSocket channel — models upstream
 * trustManagerForCertificates (WSCM:390-410) + the PER-PROFILE host
 * verifiers (WSCM:289-294 computer / :297-305 pad). Trust anchors: the two
 * certificates extracted from the OPSynergy APK assets (vendored at
 * docs/re/certs/{server_computer,server_pad}.pem):
 *
 *   computer (assets/server.cer):     CN=192.168.137.1 (SoftAP gateway),
 *                                     O=oppo — sha256 a892ccac…68d9
 *   pad      (assets/server_pad.cer): CN=192.168.49.1 (Wi-Fi Direct group),
 *                                     O=oplus, OU=GameEco — sha256 afc50cd2…f0ea
 *
 * (The two certs are DISTINCT — an earlier "identical" claim was corrected
 * during PR #7 review; per-profile verifiers in the decompile corroborate.)
 *
 * Security model: SHA-256 FINGERPRINT pinning (chain trust is meaningless —
 * self-signed OEM certs). Hostname verification is intentionally disabled at
 * the SSLSocket layer (endpointIdentificationAlgorithm = null — raw sockets
 * default) because each cert's CN names the phone's hotspot/Wi-Fi-Direct
 * gateway IP while peers also connect over other local addresses; upstream
 * ships custom per-profile verifiers for the same reason.
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

    /** SHA-256 of the computer-profile certificate (DER), lowercase hex. */
    const val PINNED_FINGERPRINT_COMPUTER: String =
        "a892ccacb0ec60bf539445ddd3ce2d246022" + "47f4812fe817a9460ccf87f568d9"

    /** SHA-256 of the pad-profile certificate (DER), lowercase hex. */
    const val PINNED_FINGERPRINT_PAD: String =
        "afc50cd2ce6db8e45d461588da376c2ba3" + "07d8f61628a6d7dbf0ac7566c1f0ea"

    /** Computer-profile trust anchor (APK assets/server.cer). */
    const val PINNED_SERVER_PEM_COMPUTER: String = """
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

    /** Pad-profile trust anchor (APK assets/server_pad.cer — a DIFFERENT cert). */
    const val PINNED_SERVER_PEM_PAD: String = """
-----BEGIN CERTIFICATE-----
MIIDczCCAlugAwIBAgIEKYZ2AzANBgkqhkiG9w0BAQsFADBqMQswCQYDVQQGEwJD
TjEQMA4GA1UECBMHSmlhbmdTdTEQMA4GA1UEBxMHTmFuSmluZzEOMAwGA1UEChMF
b3BsdXMxEDAOBgNVBAsTB0dhbWVFY28xFTATBgNVBAMTDDE5Mi4xNjguNDkuMTAe
Fw0yMTEwMjAwNzEwMDdaFw0zMTEwMTgwNzEwMDdaMGoxCzAJBgNVBAYTAkNOMRAw
DgYDVQQIEwdKaWFuZ1N1MRAwDgYDVQQHEwdOYW5KaW5nMQ4wDAYDVQQKEwVvcGx1
czEQMA4GA1UECxMHR2FtZUVjbzEVMBMGA1UEAxMMMTkyLjE2OC40OS4xMIIBIjAN
BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjN6F6kBK4Tnop3yqFTVW8QGMdg9T
Mla3HN8hK1dbq6q9NvIqAiusqkrzT4NcgibLs42JubT/BJIOFuLiWUv1CtvUsadl
6sTSWdZvk4MCieWrnRAKIkH+rc+Ob1MYgxeuHmgvf438wr2gR4E6+9XuP2yBpnzz
JLpQ36kYarksPz4HzygA1hxa+h+xwz105MJQzdKmgvp9Op1IiPlIOu+AswJ99Ksc
TYGVv9/4jHZmyZLDWIsCS8lNDAe8E3F6gqw3Lpr7pG5Jxpa8fYgy4sb+fUCQcvd+
/lKJ2IWEWOpwdO2hmeWSzCNuNfQV9VO5CxwY5h/PDGG8PbhcNWDh3qDKBQIDAQAB
oyEwHzAdBgNVHQ4EFgQU0h1XKbCxULhf0s5hJsJ+rACbszswDQYJKoZIhvcNAQEL
BQADggEBAFttLxitFCNuuGJ1FMRXhfE+oh6lqUo1apD1brNpd7PasXDJUuPQOqdR
AE2LHzN/dyJkNtJsvOxqp3vEplniurEhanYPmzGmoJ8bJ42YCisqhfn1krOxrsHQ
LDfoT0FCugbfscbD5IHf3X+IiMhmWmrIDzFg4jRm6QcOKW5jExl+0VFmtclWdJNu
Q6ctIL2d2JJnA+QwOo8bHJjpAfC+jVVy8sonnoQmjK51arWyX4Cxd8OkS722NN/R
D02MW8F09VkFqHD2XEIPdpGGyxa0pcdt5Acb1bjngtYml2W9pxNlWM6vXRfdMOWk
2JyouVAjtcUG7OHQ0IXVM9v29iTKFzE=
-----END CERTIFICATE-----
"""

    fun fingerprintFor(profile: SynergyWsClient.Profile): String = when (profile) {
        SynergyWsClient.Profile.COMPUTER -> PINNED_FINGERPRINT_COMPUTER
        SynergyWsClient.Profile.PAD -> PINNED_FINGERPRINT_PAD
    }

    fun pemFor(profile: SynergyWsClient.Profile): String = when (profile) {
        SynergyWsClient.Profile.COMPUTER -> PINNED_SERVER_PEM_COMPUTER
        SynergyWsClient.Profile.PAD -> PINNED_SERVER_PEM_PAD
    }

    fun pinnedCertificate(profile: SynergyWsClient.Profile): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        val pemBody = pemFor(profile)
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s"), "") // strip newlines + raw-string indentation
        val der = java.util.Base64.getMimeDecoder().decode(pemBody) // PEM text → DER bytes
        return factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") {
            "%02x".format(it)
        }

    /** Trust manager that accepts ONLY the profile's pinned certificate fingerprint. */
    fun pinnedTrustManager(profile: SynergyWsClient.Profile): X509TrustManager {
        val expected = fingerprintFor(profile)
        val anchor = pinnedCertificate(profile)
        return object : X509TrustManager {
            private fun check(chain: Array<out X509Certificate>?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Empty certificate chain")
                if (sha256Fingerprint(leaf) != expected) {
                    throw CertificateException("Peer certificate is not the pinned $profile Synergy server cert")
                }
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(anchor)
        }
    }

    fun sslContext(profile: SynergyWsClient.Profile): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(pinnedTrustManager(profile)), null)
    }

    fun sslSocketFactory(profile: SynergyWsClient.Profile): SSLSocketFactory =
        sslContext(profile).socketFactory

    /**
     * Applies upstream-parity settings to a raw SSL socket: no hostname
     * endpoint identification (per-profile verifier analog) and TLS 1.2+.
     */
    fun configure(socket: SSLSocket) {
        val params = socket.sslParameters
        params.endpointIdentificationAlgorithm = null
        socket.sslParameters = params
        socket.enabledProtocols = socket.supportedProtocols.filter { it.startsWith("TLSv1") }.toTypedArray()
    }
}
