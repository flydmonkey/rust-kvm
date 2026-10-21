package com.rustkvm.host.serial

import com.rustkvm.host.config.HostConfigStore
import com.rustkvm.host.server.HostDependencies
import com.rustkvm.host.server.hostModule
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SerialBridgeTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ports = FakeSerialPorts()
    private val hub = SerialHub(controlWaitMs = 200, takeoverWaitMs = 2_000)

    @Test
    fun openReportsStatusAndBytesPassThroughBothWays() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            val status = openPort(baudRate = 9600)
            assertEquals("status", status.op)
            assertEquals("true", status.field("open"))
            assertEquals("/dev/bus/usb/001/002", status.field("port"))
            assertEquals("9600", status.field("baudRate"))

            send(Frame.Binary(true, byteArrayOf(0x57, 0xAB.toByte(), 0x00)))
            awaitCondition { ports.last().writes.size == 1 }
            assertEquals(listOf(0x57, 0xAB, 0x00), ports.last().writtenBytes().single())

            ports.last().reply(byteArrayOf(0x57, 0xAB.toByte(), 0x00, 0x82.toByte()))
            val reply = withTimeout(TIMEOUT_MS) { incoming.receive() } as Frame.Binary
            assertEquals(listOf(0x57, 0xAB, 0x00, 0x82), reply.readBytes().map { it.toInt() and 0xFF })
        }
    }

    @Test
    fun writesKeepThePacketGapEvenWhenFramesArriveTogether() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            openPort()
            repeat(4) { send(Frame.Binary(true, byteArrayOf(it.toByte()))) }
            awaitCondition { ports.last().writes.size == 4 }
            val gapsMs = ports.last().writes.zipWithNext { a, b -> (b.atNanos - a.atNanos) / 1_000_000.0 }
            assertTrue("包间隔 $gapsMs", gapsMs.all { it >= SerialLink.PACKET_GAP_MS - 0.5 })
        }
    }

    @Test
    fun pongCarriesSerialRoundTripOnceTheChipHasReplied() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            send(Frame.Text("""{"op":"ping"}"""))
            assertNull(nextText().field("serialUs"))

            openPort()
            send(Frame.Binary(true, byteArrayOf(1)))
            awaitCondition { ports.last().writes.isNotEmpty() }
            ports.last().reply(byteArrayOf(2))
            withTimeout(TIMEOUT_MS) { incoming.receive() }

            send(Frame.Text("""{"op":"ping"}"""))
            val pong = nextText()
            assertEquals("pong", pong.op)
            assertNotNull(pong.json["serialUs"]?.jsonPrimitive?.longOrNull)
        }
    }

    @Test
    fun bytesBeforeOpenAndBadJsonAreReportedAsErrors() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            send(Frame.Binary(true, byteArrayOf(1)))
            assertEquals("串口未打开", nextText().field("message"))

            send(Frame.Text("not json"))
            val error = nextText()
            assertEquals("error", error.op)
            assertTrue(error.field("message")!!.startsWith("控制消息无法解析"))
        }
    }

    @Test
    fun openFallsBackToTheOnlyPortButFailsWhenThereAreNone() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            assertEquals("/dev/bus/usb/001/002", openPort(port = "COM3").field("port"))
        }
        ports.devices = emptyList()
        client.webSocket("/ws/serial") {
            val error = openPort()
            assertEquals("error", error.op)
            assertEquals("宿主机上没有 USB 串口设备", error.field("message"))
        }
    }

    @Test
    fun reopeningWithAnotherBaudRateClosesTheOldPortFirst() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            openPort(baudRate = 9600)
            assertEquals("115200", openPort(baudRate = 115200).field("baudRate"))
            assertEquals(listOf(9600, 115200), ports.opened.map { it.baudRate })
            assertTrue(ports.opened.first().closed)

            send(Frame.Text("""{"op":"close"}"""))
            assertEquals("false", nextText().field("open"))
            assertTrue(ports.last().closed)
        }
    }

    @Test
    fun disconnectingSendsReleaseFramesBeforeClosingThePort() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            openPort()
            close()
        }
        awaitCondition { ports.last().closed }
        assertEquals(
            listOf(
                listOf(0x57, 0xAB, 0x00, 0x02, 0x08, 0, 0, 0, 0, 0, 0, 0, 0, 0x0C),
                listOf(0x57, 0xAB, 0x00, 0x05, 0x05, 0x01, 0, 0, 0, 0, 0x0D),
            ),
            ports.last().writtenBytes(),
        )
    }

    @Test
    fun unpluggedPortIsReportedAndTheSessionStaysUsable() = bridgeTest { client ->
        client.webSocket("/ws/serial") {
            openPort()
            ports.last().unplug()
            assertEquals("串口连接已中断", nextText().field("message"))
            assertEquals("false", nextText().field("open"))

            assertEquals("true", openPort().field("open"))
        }
    }

    @Test
    fun secondControllerIsToldTheSeatIsBusy() = bridgeTest { client -> coroutineScope {
        val firstReady = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        launch {
            client.webSocket("/ws/serial") {
                openPort()
                firstReady.complete(Unit)
                release.await()
            }
        }
        firstReady.await()
        client.webSocket("/ws/serial") {
            val busy = nextText()
            assertEquals("busy", busy.field("code"))
            assertEquals("串口正被另一个控制端占用。", busy.field("message"))
        }
        release.complete(Unit)
    } }

    @Test
    fun takeoverEvictsTheCurrentControllerWithABusyNotice() = bridgeTest { client -> coroutineScope {
        val firstReady = CompletableDeferred<Unit>()
        val firstNotice = CompletableDeferred<Reply>()
        launch {
            client.webSocket("/ws/serial") {
                openPort()
                firstReady.complete(Unit)
                firstNotice.complete(nextText())
            }
        }
        firstReady.await()
        client.webSocket("/ws/serial?takeover=1") {
            assertEquals("true", openPort().field("open"))
        }
        val notice = firstNotice.await()
        assertEquals("busy", notice.field("code"))
        assertTrue(notice.field("message")!!.contains("接管"))
        // 被接管的一方也要补发全释放包
        awaitCondition { ports.opened.first().closed }
        assertEquals(2, ports.opened.first().writes.size)
    } }

    private fun bridgeTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
        application {
            hostModule(
                HostDependencies(
                    config = HostConfigStore(temp.newFile("config.json").also { it.delete() }),
                    serialPorts = ports,
                    serialHub = hub,
                    certPem = { "" },
                    webAssets = { null },
                ),
            )
        }
        val client = createClient { install(WebSockets) }
        block(client)
    }

    private suspend fun DefaultClientWebSocketSession.openPort(
        port: String = "/dev/bus/usb/001/002",
        baudRate: Int = 115200,
    ): Reply {
        send(Frame.Text("""{"op":"open","port":"$port","baudRate":$baudRate}"""))
        return nextText()
    }

    private suspend fun DefaultClientWebSocketSession.nextText(): Reply = withTimeout(TIMEOUT_MS) {
        var frame = incoming.receive()
        while (frame !is Frame.Text) frame = incoming.receive()
        Reply(Json.parseToJsonElement(frame.readText()).jsonObject)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(5)
    }

    class Reply(val json: JsonObject) {
        val op: String? get() = field("op")
        fun field(name: String): String? = json[name]?.jsonPrimitive?.let { if (it.isString) it.content else it.toString() }
            ?.takeIf { it != "null" }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
