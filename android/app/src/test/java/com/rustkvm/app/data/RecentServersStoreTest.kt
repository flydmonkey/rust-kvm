package com.rustkvm.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentServersStoreTest {
    @Test
    fun addingServerMovesItToFrontWithoutDuplicates() {
        val updated = recentServersAfterAdding(
            current = listOf("https://one:8443/", "https://two:8443/"),
            server = "https://two:8443/",
        )

        assertEquals(
            listOf("https://two:8443/", "https://one:8443/"),
            updated,
        )
    }

    @Test
    fun keepsOnlyEightMostRecentServers() {
        val current = (1..8).map { "https://server-$it:8443/" }

        val updated = recentServersAfterAdding(current, "https://new:8443/")

        assertEquals(8, updated.size)
        assertEquals("https://new:8443/", updated.first())
        assertEquals("https://server-7:8443/", updated.last())
    }
}
