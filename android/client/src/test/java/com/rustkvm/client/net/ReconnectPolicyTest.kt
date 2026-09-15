package com.rustkvm.client.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun doublesFromHalfSecondAndCapsAtTenSeconds() {
        val policy = ReconnectPolicy()

        assertEquals(
            listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L),
            List(7) { policy.nextDelayMs() },
        )
    }

    @Test
    fun resetRestoresInitialDelay() {
        val policy = ReconnectPolicy()
        policy.nextDelayMs()
        policy.nextDelayMs()

        policy.reset()

        assertEquals(500L, policy.nextDelayMs())
    }
}
