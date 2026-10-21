package com.rustkvm.app.ui

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlReconnectPolicyTest {
    @Test
    fun firstConnectionFailureIsNotRetried() {
        assertFalse(shouldRetryControlConnection(hasStreamed = false, IOException("offline")))
    }

    @Test
    fun midSessionDisconnectIsRetried() {
        assertTrue(shouldRetryControlConnection(hasStreamed = true, IOException("disconnected")))
    }

    @Test
    fun terminalTrustFailureIsNeverRetried() {
        assertFalse(
            shouldRetryControlConnection(
                hasStreamed = true,
                TerminalControlConnectionException("certificate changed"),
            ),
        )
    }
}
