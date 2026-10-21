package com.rustkvm.host.server

import com.rustkvm.client.hid.SerialControl
import com.rustkvm.client.net.CertDecision
import com.rustkvm.client.net.KvmHttpClient
import com.rustkvm.host.config.HostConfigStore
import com.rustkvm.host.serial.FakeSerialPorts
import com.rustkvm.host.serial.SerialHub
import com.rustkvm.host.tls.SelfSignedCert
import com.rustkvm.protocol.CertTrustStore
import com.rustkvm.protocol.SerialSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 真 Netty + TLS 起服务，用一期 Android 客户端的代码连进来：两端协议对得上才算数。 */
class HostServerEndToEndTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ports = FakeSerialPorts()
    private lateinit var server: HostServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    @Test
    fun androidClientTrustsTheHostAndTypesAKey() = runBlocking {
        val cert = SelfSignedCert.loadOrCreate(temp.newFolder("tls"), listOf("localhost", "127.0.0.1"))
        server = HostServer(
            deps = HostDependencies(
                config = HostConfigStore(temp.newFile("config.json").also { it.delete() }),
                serialPorts = ports,
                serialHub = SerialHub(),
                certPem = { cert.certPem },
                webAssets = { null },
            ),
            cert = cert,
            port = 0,
        )
        server.start()
        val port = server.boundPort()

        val decisions = mutableListOf<CertDecision>()
        val trustStore = MemoryTrustStore()
        val client = KvmHttpClient(
            baseUrl = "https://localhost:$port/",
            trustStore = trustStore,
            onCertDecision = { decisions += it; true },
        )
        assertEquals("absolute", client.getConfig().serial.mouseMode)
        assertEquals(cert.sha256Fingerprint, (decisions.single() as CertDecision.NeedsUserTrust).fingerprint)

        // 口名留空：Android 上设备名每次插拔都会变，宿主机在只有一个口时直接用它
        val control = SerialControl(
            sessionFactory = client::newSession,
            settings = SerialSettings(port = ""),
        )
        withTimeout(10_000) { control.open() }
        control.tapKey("KeyA")

        withTimeout(5_000) { while (ports.last().writes.size < 2) delay(10) }
        val frames = ports.last().writtenBytes()
        assertEquals(listOf(0x57, 0xAB, 0x00, 0x02, 0x08, 0x00, 0x00, 0x04), frames[0].take(8))
        assertEquals(listOf(0x57, 0xAB, 0x00, 0x02, 0x08, 0x00, 0x00, 0x00), frames[1].take(8))

        control.close()
        withTimeout(5_000) { while (!ports.last().closed) delay(10) }
        assertTrue(ports.last().writtenBytes().size >= 4)
    }

    @Test
    fun serverCanBeStoppedAndStartedAgainInTheSameProcess() = runBlocking {
        val cert = SelfSignedCert.loadOrCreate(temp.newFolder("tls"), listOf("localhost"))
        server = HostServer(
            deps = HostDependencies(
                config = HostConfigStore(temp.newFile("config.json").also { it.delete() }),
                serialPorts = ports,
                serialHub = SerialHub(),
                certPem = { cert.certPem },
                webAssets = { null },
            ),
            cert = cert,
            port = 0,
        )
        repeat(2) {
            server.start()
            val client = KvmHttpClient("https://localhost:${server.boundPort()}/", MemoryTrustStore()) { true }
            assertEquals("mjpeg", client.getConfig().video.codec)
            server.stop()
        }
    }

    private class MemoryTrustStore : CertTrustStore {
        private val pins = mutableMapOf<String, String>()
        override fun trustedFingerprint(hostPort: String): String? = pins[hostPort]
        override fun trust(hostPort: String, sha256Hex: String) {
            pins[hostPort] = sha256Hex
        }

        override fun clear(hostPort: String) {
            pins.remove(hostPort)
        }
    }
}
