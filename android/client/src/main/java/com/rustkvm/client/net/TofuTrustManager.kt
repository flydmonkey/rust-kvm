package com.rustkvm.client.net

import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.sha256Fingerprint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

sealed class CertDecision {
    data object Trusted : CertDecision()
    data class NeedsUserTrust(
        val fingerprint: String,
        val subject: String,
    ) : CertDecision()

    data class Mismatch(
        val old: String,
        val new: String,
    ) : CertDecision()
}

@Suppress("DEPRECATION")
fun evaluateCertificate(
    chain: Array<out X509Certificate>,
    hostPort: String,
    trustStore: CertTrustStore,
): CertDecision {
    val certificate = chain.firstOrNull()
        ?: throw CertificateException("Server did not present a certificate")
    val fingerprint = sha256Fingerprint(certificate)
    val trusted = trustStore.trustedFingerprint(hostPort)

    return when {
        trusted == null -> CertDecision.NeedsUserTrust(
            fingerprint = fingerprint,
            subject = certificate.subjectDN.name,
        )

        trusted.equals(fingerprint, ignoreCase = true) -> CertDecision.Trusted
        else -> CertDecision.Mismatch(old = trusted, new = fingerprint)
    }
}

class TofuTrustManager(
    private val hostPort: String,
    private val trustStore: CertTrustStore,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        when (val decision = evaluateCertificate(chain, hostPort, trustStore)) {
            CertDecision.Trusted -> Unit
            else -> throw TofuCertificateException(decision)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal class TofuCertificateException(
    val decision: CertDecision,
) : CertificateException("Server certificate requires a trust decision: $decision")
