package com.rustkvm.client.net

import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.ProtocolPaths
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class KvmSessionTest {
    private val server = MockWebServer()
    private val serverSockets = mutableMapOf<String, LinkedBlockingQueue<WebSocket>>()
    private val requestPaths = LinkedBlockingQueue<String>()
    private val trustStore = MemoryTrustStore()

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requestPaths += path
                if (path == ProtocolPaths.API_DEVICES) {
                    return MockResponse().setBody("""{"devices":[]}""")
                }
                val queue = synchronized(serverSockets) {
                    serverSockets.getOrPut(path.substringBefore('?')) { LinkedBlockingQueue() }
                }
                return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        queue += webSocket
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }
                })
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun firstContactAsksOnceAndPinsTheCertificate() = runBlocking {
        val decisions = mutableListOf<CertDecision>()
        val first = client { decisions += it; true }

        assertEquals("""{"devices":[]}""", first.getDevicesJson())
        assertEquals(1, decisions.size)
        assertTrue(decisions.single() is CertDecision.NeedsUserTrust)
        assertNotNull(trustStore.trustedFingerprint(first.hostPort))

        client { decisions += it; true }.getDevicesJson()
        assertEquals(1, decisions.size)
    }

    @Test
    fun rejectedCertificateFailsTheRequest() = runBlocking {
        val error = runCatching { client { false }.getDevicesJson() }.exceptionOrNull()

        assertNotNull(error)
        assertNull(trustStore.trustedFingerprint("localhost:${server.port}"))
    }

    @Test
    fun connectOpensAllStreamsAndTakeoverOnlyTouchesSerial() = runBlocking {
        val session = client { true }.newSession(takeoverSerial = true)
        withTimeout(TIMEOUT_MS) { session.connect() }

        val paths = List(3) { requestPaths.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS) }.toSet()
        assertEquals(
            setOf(ProtocolPaths.WS_VIDEO, ProtocolPaths.WS_AUDIO, "${ProtocolPaths.WS_SERIAL}?takeover=1"),
            paths,
        )
        session.disconnect()
    }

    @Test
    fun videoTextAndBinaryArriveInOrder() = runBlocking {
        val session = connectedSession()
        val video = serverSocket(ProtocolPaths.WS_VIDEO)

        video.send("""{"op":"codec","codec":"h264"}""")
        video.send(byteArrayOf(0, 0, 0, 1).toByteString())

        val received = receiveVideo(session, 2)
        assertEquals("""{"op":"codec","codec":"h264"}""", (received[0] as StreamMessage.Text).text)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), (received[1] as StreamMessage.Binary).data)
        session.disconnect()
    }

    @Test
    fun reopenVideoDropsTheOldStreamAndKeepsTheSession() = runBlocking {
        val session = connectedSession()
        val oldVideo = serverSocket(ProtocolPaths.WS_VIDEO)
        oldVideo.send("old-before-reopen")
        assertEquals("old-before-reopen", (receiveVideo(session, 1).single() as StreamMessage.Text).text)

        oldVideo.send("old-still-queued")
        withTimeout(TIMEOUT_MS) { session.reopenVideo() }
        val newVideo = serverSocket(ProtocolPaths.WS_VIDEO)
        oldVideo.send("old-after-reopen")
        newVideo.send("fresh")

        assertEquals("fresh", (receiveVideo(session, 1).single() as StreamMessage.Text).text)
        assertTrue(session.sendSerialText("""{"op":"ping"}"""))
        session.disconnect()
        assertNull(withTimeout(TIMEOUT_MS) { session.awaitDisconnect() })
    }

    @Test
    fun serverClosingVideoTerminatesTheSession() = runBlocking {
        val session = connectedSession()

        serverSocket(ProtocolPaths.WS_VIDEO).close(1001, "going away")

        val error = withTimeout(TIMEOUT_MS) { session.awaitDisconnect() }
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("video"))
    }

    @Test
    fun serverClosingAudioOnlyEndsTheAudioStream() = runBlocking {
        val session = connectedSession()
        val video = serverSocket(ProtocolPaths.WS_VIDEO)

        serverSocket(ProtocolPaths.WS_AUDIO).close(1000, "audio off")
        assertNull(withTimeout(TIMEOUT_MS) { session.audioMessages.receiveBatch() })

        video.send("still-streaming")
        assertEquals("still-streaming", (receiveVideo(session, 1).single() as StreamMessage.Text).text)
        assertTrue(session.sendSerialText("""{"op":"ping"}"""))
        session.disconnect()
    }

    private fun client(onCertDecision: suspend (CertDecision) -> Boolean) = KvmHttpClient(
        baseUrl = "https://localhost:${server.port}/",
        trustStore = trustStore,
        onCertDecision = onCertDecision,
    )

    private suspend fun connectedSession(): KvmSession {
        val session = client { true }.newSession()
        withTimeout(TIMEOUT_MS) { session.connect() }
        return session
    }

    private fun serverSocket(path: String): WebSocket {
        val queue = synchronized(serverSockets) { serverSockets.getOrPut(path) { LinkedBlockingQueue() } }
        return checkNotNull(queue.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "$path 没有建立连接" }
    }

    private suspend fun receiveVideo(session: KvmSession, count: Int): List<StreamMessage> =
        withTimeout(TIMEOUT_MS) {
            buildList {
                while (size < count) addAll(checkNotNull(session.videoMessages.receiveBatch()))
            }
        }

    private class MemoryTrustStore : CertTrustStore {
        private val pins = mutableMapOf<String, String>()

        @Synchronized
        override fun trustedFingerprint(hostPort: String): String? = pins[hostPort]

        @Synchronized
        override fun trust(hostPort: String, sha256Hex: String) {
            pins[hostPort] = sha256Hex
        }

        @Synchronized
        override fun clear(hostPort: String) {
            pins.remove(hostPort)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
