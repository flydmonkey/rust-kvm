package com.rustkvm.host.video

import com.rustkvm.protocol.VideoSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoHubTest {
    private val devices = FakeVideoDevices()
    private val hubs = mutableListOf<VideoHub>()

    @After
    fun tearDown() = hubs.forEach { it.close() }

    private fun hub(idleGraceMs: Long = 300, retryDelayMs: Long = 100) =
        VideoHub(devices, VideoSettings(width = 1920, height = 1080), idleGraceMs, retryDelayMs).also { hubs += it }

    @Test
    fun capturesOnlyWhileWatchedAndStopsAfterTheGracePeriod() = runBlocking {
        val hub = hub()
        delay(100)
        assertTrue("没人看不该开设备", devices.opened.isEmpty())

        val viewer = hub.subscribe()
        withTimeout(TIMEOUT_MS) { viewer.frames.receive() }
        assertEquals(1, devices.openStreams.get())
        assertTrue(hub.stats().capturing)
        assertEquals(1920 to 1080, hub.stats().resolution)

        viewer.close()
        delay(100)
        assertEquals("刷新页面的空档里不该关设备", 1, devices.openStreams.get())
        awaitCondition { devices.openStreams.get() == 0 }
        assertFalse(hub.stats().capturing)
        assertEquals(1, devices.opened.size)
    }

    @Test
    fun reconnectingWithinTheGracePeriodKeepsTheDeviceOpen() = runBlocking {
        val hub = hub(idleGraceMs = 1_000)
        hub.subscribe().use { withTimeout(TIMEOUT_MS) { it.frames.receive() } }
        hub.subscribe().use { withTimeout(TIMEOUT_MS) { it.frames.receive() } }
        assertEquals(1, devices.opened.size)
    }

    @Test
    fun changedSettingsReopenTheDeviceAndIdenticalOnesDoNot() = runBlocking {
        val hub = hub()
        val viewer = hub.subscribe()
        withTimeout(TIMEOUT_MS) { viewer.frames.receive() }

        hub.updateSettings(VideoSettings(width = 1920, height = 1080))
        delay(100)
        assertEquals(1, devices.opened.size)

        hub.updateSettings(VideoSettings(width = 1280, height = 720))
        awaitCondition { devices.opened.size == 2 }
        assertEquals(1280, devices.opened.last().width)
        awaitCondition { hub.stats().resolution == (1280 to 720) }
        assertEquals(1, devices.openStreams.get())
        viewer.close()
    }

    @Test
    fun openFailureIsReportedThenRetried() = runBlocking {
        devices.openError = "没有找到 USB 采集卡"
        val hub = hub()
        val viewer = hub.subscribe()
        awaitCondition { hub.stats().error == "没有找到 USB 采集卡" }
        assertFalse(hub.stats().capturing)

        devices.openError = null
        withTimeout(TIMEOUT_MS) { viewer.frames.receive() }
        awaitCondition { hub.stats().capturing }
        assertNull(hub.stats().error)
        viewer.close()
    }

    @Test
    fun aViewerThatNeverReadsDoesNotHoldBackTheOthers() = runBlocking {
        val hub = hub()
        val stuck = hub.subscribe()
        val reader = hub.subscribe()
        val seen = mutableSetOf<Int>()
        withTimeout(TIMEOUT_MS) {
            while (seen.size < 20) seen += reader.frames.receive()[3].toInt()
        }
        assertEquals(2, hub.stats().viewers)
        stuck.close()
        reader.close()
    }

    @Test
    fun statsMeasureFrameRateAndOutgoingTraffic() = runBlocking {
        val hub = hub()
        val viewer = hub.subscribe()
        withTimeout(TIMEOUT_MS) {
            while (hub.stats().fps == 0.0) {
                hub.recordSent(viewer.frames.receive().size)
            }
        }
        val stats = hub.stats()
        assertTrue("fps=${stats.fps}", stats.fps in 20.0..120.0)
        assertTrue(stats.mbps > 0 && stats.sentMbps > 0)
        assertEquals(6, stats.lastFrameBytes)
        viewer.close()
    }

    @Test
    fun closeStopsCaptureAndEndsSubscriptions() = runBlocking {
        val hub = hub()
        val viewer = hub.subscribe()
        withTimeout(TIMEOUT_MS) { viewer.frames.receive() }
        hub.close()
        assertEquals(0, devices.openStreams.get())
        withTimeout(TIMEOUT_MS) { for (frame in viewer.frames) Unit }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(10)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
