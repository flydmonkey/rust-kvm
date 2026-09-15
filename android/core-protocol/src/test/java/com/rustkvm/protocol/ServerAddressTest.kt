package com.rustkvm.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerAddressTest {
    @Test
    fun fillsHttpsAndPort() {
        assertEquals("https://192.168.1.8:8443/", normalizeServerUrl("192.168.1.8").toString())
    }

    @Test
    fun keepsExplicitPort() {
        assertEquals("https://host.local:9443/", normalizeServerUrl("https://host.local:9443").toString())
    }
}
