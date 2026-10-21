package com.rustkvm.client.net

import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.sha256Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

class TofuTrustManagerTest {
    private val certificate = FakeCertificate(
        encodedBytes = "certificate-one".toByteArray(),
        subject = "CN=rust-kvm",
    )

    @Test
    fun emptyStoreNeedsUserTrust() {
        val store = MemoryTrustStore()

        val decision = evaluateCertificate(
            chain = arrayOf(certificate),
            hostPort = "192.168.1.8:8443",
            trustStore = store,
        )

        assertEquals(
            CertDecision.NeedsUserTrust(
                fingerprint = sha256Fingerprint(certificate),
                subject = "CN=rust-kvm",
            ),
            decision,
        )
    }

    @Test
    fun matchingFingerprintIsTrusted() {
        val store = MemoryTrustStore().apply {
            trust("192.168.1.8:8443", sha256Fingerprint(certificate))
        }

        val decision = evaluateCertificate(
            chain = arrayOf(certificate),
            hostPort = "192.168.1.8:8443",
            trustStore = store,
        )

        assertTrue(decision === CertDecision.Trusted)
    }

    @Test
    fun changedFingerprintReportsMismatch() {
        val store = MemoryTrustStore().apply {
            trust("192.168.1.8:8443", "old-fingerprint")
        }

        val decision = evaluateCertificate(
            chain = arrayOf(certificate),
            hostPort = "192.168.1.8:8443",
            trustStore = store,
        )

        assertEquals(
            CertDecision.Mismatch(
                old = "old-fingerprint",
                new = sha256Fingerprint(certificate),
            ),
            decision,
        )
    }
}

private class MemoryTrustStore : CertTrustStore {
    private val fingerprints = mutableMapOf<String, String>()

    override fun trustedFingerprint(hostPort: String): String? = fingerprints[hostPort]

    override fun trust(hostPort: String, sha256Hex: String) {
        fingerprints[hostPort] = sha256Hex.lowercase()
    }

    override fun clear(hostPort: String) {
        fingerprints.remove(hostPort)
    }
}

@Suppress("DEPRECATION")
private class FakeCertificate(
    private val encodedBytes: ByteArray,
    subject: String,
) : X509Certificate() {
    private val subjectPrincipal = Principal { subject }

    override fun getEncoded(): ByteArray = encodedBytes.copyOf()
    override fun getSubjectDN(): Principal = subjectPrincipal
    override fun getIssuerDN(): Principal = subjectPrincipal
    override fun checkValidity() = Unit
    override fun checkValidity(date: Date?) = Unit
    override fun getVersion(): Int = 3
    override fun getSerialNumber(): BigInteger = BigInteger.ONE
    override fun getNotBefore(): Date = Date(0)
    override fun getNotAfter(): Date = Date(Long.MAX_VALUE)
    override fun getTBSCertificate(): ByteArray = encoded
    override fun getSignature(): ByteArray = byteArrayOf()
    override fun getSigAlgName(): String = "NONE"
    override fun getSigAlgOID(): String = "0.0"
    override fun getSigAlgParams(): ByteArray? = null
    override fun getIssuerUniqueID(): BooleanArray? = null
    override fun getSubjectUniqueID(): BooleanArray? = null
    override fun getKeyUsage(): BooleanArray? = null
    override fun getBasicConstraints(): Int = -1
    override fun verify(key: PublicKey?) = Unit
    override fun verify(key: PublicKey?, sigProvider: String?) = Unit
    override fun getPublicKey(): PublicKey? = null
    override fun hasUnsupportedCriticalExtension(): Boolean = false
    override fun getCriticalExtensionOIDs(): Set<String>? = null
    override fun getNonCriticalExtensionOIDs(): Set<String>? = null
    override fun getExtensionValue(oid: String?): ByteArray? = null
    override fun toString(): String = subjectPrincipal.name
}
