package com.rustkvm.host.server

import com.rustkvm.host.config.HostConfigStore
import com.rustkvm.host.serial.FakeSerialPorts
import com.rustkvm.host.serial.SerialHub
import com.rustkvm.protocol.KvmConfig
import com.rustkvm.protocol.SerialSettings
import com.rustkvm.protocol.encodeConfig
import com.rustkvm.protocol.parseConfig
import com.rustkvm.protocol.parseDevices
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HostRoutesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ports = FakeSerialPorts()
    private val assets = mapOf(
        "index.html" to "<html>kvm</html>",
        "input.js" to "console.log(1)",
    )

    @Test
    fun configDefaultsThenPersistsAcrossRestarts() = routesTest { configFile ->
        assertEquals(KvmConfig.DEFAULT, parseConfig(client.get("/api/config").bodyAsText()))

        val changed = KvmConfig(serial = SerialSettings(port = "/dev/bus/usb/001/002", baudRate = 9600))
        val response = client.put("/api/config") {
            contentType(ContentType.Application.Json)
            setBody(encodeConfig(changed))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(changed, parseConfig(response.bodyAsText()))
        assertEquals(changed, HostConfigStore(configFile).get())
    }

    @Test
    fun malformedConfigIsRejectedAndNotSaved() = routesTest { configFile ->
        val response = client.put("/api/config") { setBody("{oops") }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertFalse(configFile.exists())
    }

    @Test
    fun devicesListSerialPortsAndExplainMissingCapture() = routesTest {
        val devices = parseDevices(client.get("/api/devices").bodyAsText())
        assertEquals(listOf("/dev/bus/usb/001/002"), devices.serial.items.map { it.name })
        assertTrue(devices.video.items.isEmpty() && devices.video.error != null)
        assertTrue(devices.audio.items.isEmpty() && devices.audio.error != null)

        ports.devices = emptyList()
        val empty = parseDevices(client.get("/api/devices").bodyAsText())
        assertTrue(empty.serial.error!!.contains("OTG"))
    }

    @Test
    fun serialPortsAndStatsHaveTheWindowsShape() = routesTest {
        val portsJson = Json.parseToJsonElement(client.get("/api/serial/ports").bodyAsText()).jsonArray
        assertEquals("/dev/bus/usb/001/002", portsJson.single().jsonObject["name"]!!.jsonPrimitive.content)

        val video = Json.parseToJsonElement(client.get("/api/video/stats").bodyAsText()).jsonObject
        assertEquals(
            setOf("capturing", "viewers", "fps", "mbps", "sentMbps", "frames", "lastFrameBytes", "resolution", "error"),
            video.keys,
        )
        val audio = Json.parseToJsonElement(client.get("/api/audio/stats").bodyAsText()).jsonObject
        assertEquals(setOf("capturing", "listeners", "format", "kbps", "chunks", "error"), audio.keys)
    }

    @Test
    fun webAssetsAreServedWithIndexAndNoCache() = routesTest {
        val index = client.get("/")
        assertEquals("<html>kvm</html>", index.bodyAsText())
        assertTrue(index.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertEquals("no-cache", index.headers[HttpHeaders.CacheControl])

        val script = client.get("/input.js?v=2")
        assertEquals("console.log(1)", script.bodyAsText())
        assertTrue(script.headers[HttpHeaders.ContentType]!!.startsWith("text/javascript"))

        assertEquals(HttpStatusCode.NotFound, client.get("/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/../secret").status)
    }

    @Test
    fun certificateIsDownloadable() = routesTest {
        val response = client.get("/cert.crt")
        assertEquals("PEM", response.bodyAsText())
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains("rust-kvm.crt"))
    }

    @Test
    fun assetPathsCannotEscapeTheWebDirectory() {
        assertEquals("index.html", webAssetPath("/"))
        assertEquals("lib/a.js", webAssetPath("/lib/a.js"))
        assertEquals(null, webAssetPath("/lib/../../etc/passwd"))
        assertEquals(null, webAssetPath("/a\\b"))
    }

    private fun routesTest(block: suspend ApplicationTestBuilder.(configFile: File) -> Unit) {
        val configFile = File(temp.root, "config.json")
        testApplication {
            application {
                hostModule(
                    HostDependencies(
                        config = HostConfigStore(configFile),
                        serialPorts = ports,
                        serialHub = SerialHub(),
                        certPem = { "PEM" },
                        webAssets = { path -> assets[path]?.toByteArray() },
                    ),
                )
            }
            block(configFile)
        }
    }
}
