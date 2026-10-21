package com.rustkvm.client.net

class ReconnectPolicy {
    private var nextDelayMs = INITIAL_DELAY_MS

    fun nextDelayMs(): Long {
        val result = nextDelayMs
        nextDelayMs = (nextDelayMs * 2).coerceAtMost(MAX_DELAY_MS)
        return result
    }

    fun reset() {
        nextDelayMs = INITIAL_DELAY_MS
    }

    private companion object {
        const val INITIAL_DELAY_MS = 500L
        const val MAX_DELAY_MS = 10_000L
    }
}
