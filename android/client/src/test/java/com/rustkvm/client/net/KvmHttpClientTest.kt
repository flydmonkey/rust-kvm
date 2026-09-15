package com.rustkvm.client.net

import com.rustkvm.protocol.CertTrustStore
import org.junit.Assert.assertEquals
import org.junit.Test

class KvmHttpClientTest {
    @Test
    fun rejectsHttpBaseUrl() {
        val error = runCatching {
            KvmHttpClient(
                baseUrl = "http://192.168.1.8:8080/",
                trustStore = NoOpTrustStore,
                onCertDecision = { false },
            )
        }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("仅支持 https", error?.message)
    }
}

private object NoOpTrustStore : CertTrustStore {
    override fun trustedFingerprint(hostPort: String): String? = null

    override fun trust(hostPort: String, sha256Hex: String) = Unit

    override fun clear(hostPort: String) = Unit
}
