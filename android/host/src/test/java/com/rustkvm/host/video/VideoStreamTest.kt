package com.rustkvm.host.video

import com.rustkvm.host.config.HostConfigStore
import com.rustkvm.host.serial.FakeSerialPorts
import com.rustkvm.host.serial.SerialHub
import com.rustkvm.host.server.HostDependencies
import com.rustkvm.host.server.hostModule
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.VideoSettings
import com.rustkvm.protocol.encodeConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val devices = FakeVideoDevices()
    private val hub = VideoHub(devices, VideoSettings(), idleGraceMs = 200, retryDelayMs = 100)

    @After
    fun tearDown() = hub.close()

    @Test
    fun headerComesFirstThenEachFrameAsBinary() = videoTest { client ->
        client.webSocket("/ws/video") {
            val header = nextText()
            assertEquals("codec", header.string("op"))
            assertEquals("mjpeg", header.string("codec"))
            assertFalse(header.containsKey("fallbackReason"))

            val first = nextBinary()
            val second = nextBinary()
            assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), first.copyOf(2))
            assertFalse(first.contentEquals(second))
        }
        awaitCondition { devices.openStreams.get() == 0 }
    }

    @Test
    fun duplicateFramesAreSkippedAndReportedAsIdle() = videoTest { client ->
        devices.frameAt = { if (it < 3) fakeJpeg(it) else fakeJpeg(99) }
        client.webSocket("/ws/video") {
            nextText()
            val distinct = mutableListOf<ByteArray>()
            var idle: JsonObject? = null
            withTimeout(IDLE_NOTICE_MS + TIMEOUT_MS) {
                while (idle == null) {
                    when (val frame = incoming.receive()) {
                        is Frame.Binary -> distinct += frame.readBytes()
                        is Frame.Text -> idle = Json.parseToJsonElement(frame.readText()).jsonObject
                        else -> Unit
                    }
                }
            }
            // 前几帧可能因为队列只留最新的而被跳过，但静止画面那一帧必须恰好发一次
            assertEquals(1, distinct.count { it.contentEquals(fakeJpeg(99)) })
            assertTrue(distinct.size <= 4)
            assertEquals("idle", idle!!.string("op"))
            assertTrue(idle!!["skipped"]!!.jsonPrimitive.long > 10)
        }
    }

    @Test
    fun h264ConfigFallsBackToMjpegWithAReason() = videoTest(KvmConfig(video = VideoSettings(codec = "h264"))) { client ->
        client.webSocket("/ws/video") {
            val header = nextText()
            assertEquals("mjpeg", header.string("codec"))
            assertTrue(header.string("fallbackReason")!!.contains("H.264"))
            nextBinary()
        }
    }

    @Test
    fun savingVideoSettingsResendsTheHeaderWithoutDisconnecting() = videoTest { client ->
        client.webSocket("/ws/video") {
            nextText()
            nextBinary()
            client.put("/api/config") {
                setBody(encodeConfig(KvmConfig(video = VideoSettings(width = 1280, height = 720, codec = "h264"))))
            }
            val header = nextText()
            assertEquals("codec", header.string("op"))
            assertTrue(header.containsKey("fallbackReason"))
            nextBinary()
            awaitCondition { devices.opened.lastOrNull()?.width == 1280 }
        }
    }

    @Test
    fun statsReflectAnActiveViewer() = videoTest { client ->
        client.webSocket("/ws/video") {
            nextText()
            repeat(3) { nextBinary() }
            val stats = Json.parseToJsonElement(client.get("/api/video/stats").bodyAsText()).jsonObject
            assertEquals("true", stats["capturing"]!!.jsonPrimitive.content)
            assertEquals(1, stats["viewers"]!!.jsonPrimitive.int)
            assertEquals(listOf(1920, 1080), stats["resolution"]!!.jsonArray.map { it.jsonPrimitive.int })
            assertEquals("null", stats["error"].toString())
        }
    }

    @Test
    fun captureErrorsShowUpInStats() = videoTest { client ->
        devices.openError = "没有已授权的 USB 采集卡"
        client.webSocket("/ws/video") {
            assertNull(nextText().string("fallbackReason"))
            awaitCondition {
                val stats = Json.parseToJsonElement(client.get("/api/video/stats").bodyAsText()).jsonObject
                stats["error"]!!.jsonPrimitive.content == "没有已授权的 USB 采集卡"
            }
        }
    }

    private fun videoTest(
        config: KvmConfig = KvmConfig.DEFAULT,
        block: suspend ApplicationTestBuilder.(HttpClient) -> Unit,
    ) = testApplication {
        val store = HostConfigStore(temp.newFile("config.json").also { it.delete() })
        store.save(config)
        hub.updateSettings(config.video)
        application {
            hostModule(
                HostDependencies(
                    config = store,
                    serialPorts = FakeSerialPorts(),
                    serialHub = SerialHub(),
                    certPem = { "" },
                    webAssets = { null },
                    video = hub,
                ),
            )
        }
        block(createClient { install(WebSockets) })
    }

    private suspend fun DefaultClientWebSocketSession.nextText(): JsonObject = withTimeout(TIMEOUT_MS) {
        var frame = incoming.receive()
        while (frame !is Frame.Text) frame = incoming.receive()
        Json.parseToJsonElement(frame.readText()).jsonObject
    }

    private suspend fun DefaultClientWebSocketSession.nextBinary(): ByteArray = withTimeout(TIMEOUT_MS) {
        var frame = incoming.receive()
        while (frame !is Frame.Binary) frame = incoming.receive()
        frame.readBytes()
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(10)
    }

    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.content

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
