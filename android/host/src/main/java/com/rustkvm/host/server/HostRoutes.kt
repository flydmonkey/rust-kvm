package com.rustkvm.host.server

import com.rustkvm.host.config.HostConfigStore
import com.rustkvm.host.serial.SerialHub
import com.rustkvm.host.serial.SerialPorts
import com.rustkvm.host.serial.runSerialBridge
import com.rustkvm.host.video.VideoHub
import com.rustkvm.host.video.VideoStats
import com.rustkvm.host.video.runVideoStream
import com.rustkvm.protocol.DeviceList
import com.rustkvm.protocol.KvmDevices
import com.rustkvm.protocol.encodeConfig
import com.rustkvm.protocol.encodeDevices
import com.rustkvm.protocol.encodeSerialDevices
import com.rustkvm.protocol.parseConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

class HostDependencies(
    val config: HostConfigStore,
    val serialPorts: SerialPorts,
    val serialHub: SerialHub,
    val certPem: () -> String,
    val webAssets: WebAssets,
    val video: VideoHub,
)

internal const val NO_CAPTURE_DEVICE = "没有找到 USB 采集卡。用 OTG 接上采集卡后，在宿主机页面点「授权」。"
internal const val AUDIO_UNAVAILABLE = "Android 宿主机还不支持音频（二期 2c）"

/** 与 `src/main.rs` 的路由同形，浏览器 `web/` 和 Android 客户端都不需要为它开特例。 */
fun Application.hostModule(deps: HostDependencies) {
    install(WebSockets) {
        pingPeriod = 20.seconds
    }
    // 所有响应都带 no-cache：否则浏览器按启发式缓存 js，更新 APK 后页面还跑旧代码
    install(createApplicationPlugin("NoCache") {
        onCall { call -> call.response.header(HttpHeaders.CacheControl, "no-cache") }
    })

    routing {
        webSocket("/ws/serial") {
            val takeover = call.request.queryParameters["takeover"].let { it == "1" || it == "true" }
            runSerialBridge(deps.serialHub, deps.serialPorts, takeover)
        }
        webSocket("/ws/video") { runVideoStream(deps.video) }
        webSocket("/ws/audio") {
            send(Frame.Text(buildJsonObject { put("op", "error"); put("message", AUDIO_UNAVAILABLE) }.toString()))
            for (frame in incoming) Unit
        }

        get("/api/config") { call.respondJson(encodeConfig(deps.config.get())) }
        put("/api/config") {
            val config = try {
                parseConfig(call.receiveText())
            } catch (error: Exception) {
                call.respondText("配置格式不对：${error.message}", status = HttpStatusCode.BadRequest)
                return@put
            }
            try {
                deps.config.save(config)
            } catch (error: Exception) {
                call.respondText(error.message ?: "保存配置失败", status = HttpStatusCode.InternalServerError)
                return@put
            }
            // 串口不在这儿热切换：口由控制端通过 /ws/serial 的 open 打开，配置只是默认值
            deps.video.updateSettings(config.video)
            call.respondJson(encodeConfig(config))
        }
        // 每次请求都重新枚举：USB 设备会被插拔，列表过期了就选不到设备
        get("/api/devices") {
            call.respondJson(
                encodeDevices(
                    KvmDevices(
                        video = videoDeviceList(deps.video),
                        audio = DeviceList(error = AUDIO_UNAVAILABLE),
                        serial = serialDeviceList(deps.serialPorts),
                    ),
                ),
            )
        }
        get("/api/serial/ports") {
            call.respondJson(encodeSerialDevices(runCatching { deps.serialPorts.list() }.getOrDefault(emptyList())))
        }
        get("/api/video/stats") { call.respondJson(videoStatsJson(deps.video.stats())) }
        get("/api/audio/stats") { call.respondJson(audioStatsJson()) }
        get("/cert.crt") {
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"rust-kvm.crt\"")
            call.respondText(deps.certPem(), ContentType("application", "x-x509-ca-cert"))
        }
        get("/{path...}") {
            val path = webAssetPath(call.request.local.uri.substringBefore('?'))
            val bytes = path?.let(deps.webAssets::read)
            if (path == null || bytes == null) {
                call.respondText("Not Found", status = HttpStatusCode.NotFound)
            } else {
                call.respondBytes(bytes, contentTypeFor(path))
            }
        }
    }
}

private fun serialDeviceList(ports: SerialPorts) = runCatching { ports.list() }.fold(
    onSuccess = { items ->
        if (items.isEmpty()) {
            DeviceList(error = "没有找到 USB 串口设备。用 OTG 线接上 CH9329 后，在宿主机页面点「授权」。")
        } else {
            DeviceList(items = items)
        }
    },
    onFailure = { DeviceList(error = "枚举 USB 串口失败：${it.message}") },
)

private fun videoDeviceList(hub: VideoHub) = runCatching { hub.listDevices() }.fold(
    onSuccess = { items -> if (items.isEmpty()) DeviceList(error = NO_CAPTURE_DEVICE) else DeviceList(items = items) },
    onFailure = { DeviceList(error = "枚举采集卡失败：${it.message}") },
)

// 字段与 src/video.rs 的 VideoStats、src/audio.rs 的 AudioStats 一致，网页直接读这些字段
private fun videoStatsJson(stats: VideoStats): String = buildJsonObject {
    put("capturing", stats.capturing)
    put("viewers", stats.viewers)
    put("fps", stats.fps)
    put("mbps", stats.mbps)
    put("sentMbps", stats.sentMbps)
    put("frames", stats.frames)
    put("lastFrameBytes", stats.lastFrameBytes)
    val resolution = stats.resolution
    if (resolution == null) {
        put("resolution", JsonNull)
    } else {
        put("resolution", buildJsonArray { add(resolution.first); add(resolution.second) })
    }
    if (stats.error == null) put("error", JsonNull) else put("error", stats.error)
}.toString()

private fun audioStatsJson(): String = buildJsonObject {
    put("capturing", false)
    put("listeners", 0)
    put("format", JsonNull)
    put("kbps", 0)
    put("chunks", 0)
    put("error", AUDIO_UNAVAILABLE)
}.toString()

private suspend fun ApplicationCall.respondJson(text: String) =
    respondText(text, ContentType.Application.Json)
