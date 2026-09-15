package com.rustkvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryCertTrustStore : CertTrustStore {
    private val map = mutableMapOf<String, String>()

    override fun trustedFingerprint(hostPort: String): String? = map[hostPort]

    override fun trust(hostPort: String, sha256Hex: String) {
        map[hostPort] = sha256Hex.lowercase()
    }

    override fun clear(hostPort: String) {
        map.remove(hostPort)
    }
}

class CertTrustStoreTest {
    @Test
    fun remembersFingerprint() {
        val store = InMemoryCertTrustStore()
        store.trust("192.168.1.8:8443", "ABCD")
        assertEquals("abcd", store.trustedFingerprint("192.168.1.8:8443"))
    }

    @Test
    fun clearRemovesFingerprint() {
        val store = InMemoryCertTrustStore()
        store.trust("192.168.1.8:8443", "abcd")
        store.clear("192.168.1.8:8443")
        assertNull(store.trustedFingerprint("192.168.1.8:8443"))
    }
}
