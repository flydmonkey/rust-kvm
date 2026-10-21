package com.rustkvm.host.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelfSignedCertTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun certificateCoversEveryNameAsDnsOrIp() {
        val cert = SelfSignedCert.loadOrCreate(temp.root, listOf("127.0.0.1", "192.168.1.20", "localhost"))

        val sans = cert.certificate.subjectAlternativeNames.map { it[0] as Int to it[1] as String }.toSet()
        assertEquals(setOf(7 to "127.0.0.1", 7 to "192.168.1.20", 2 to "localhost"), sans)
        assertTrue(cert.certPem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertEquals(64, cert.sha256Fingerprint.length)
    }

    @Test
    fun sameNamesReuseTheCertificateAndNewNamesReissueIt() {
        val names = listOf("localhost", "192.168.1.20")
        val first = SelfSignedCert.loadOrCreate(temp.root, names)

        assertEquals(first.sha256Fingerprint, SelfSignedCert.loadOrCreate(temp.root, names).sha256Fingerprint)
        assertNotEquals(
            first.sha256Fingerprint,
            SelfSignedCert.loadOrCreate(temp.root, names + "10.0.0.5").sha256Fingerprint,
        )
    }

    @Test
    fun subjectAltNamesAlwaysIncludeLoopback() {
        val names = SelfSignedCert.subjectAltNames()
        assertTrue(names.containsAll(listOf("localhost", "127.0.0.1")))
        assertEquals(names.sorted(), names)
    }
}
