package com.rustkvm.host.video

import com.rustkvm.protocol.VideoSettings
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 画面静止时隔这么久发一条 `{"op":"idle"}`，告诉页面「没新帧是因为画面没变，不是卡住了」。 */
const val IDLE_NOTICE_MS = 2_000L

internal const val H264_UNSUPPORTED = "Android 宿主机还不支持 H.264 编码（二期 2d），先用 MJPEG"

/**
 * `/ws/video`，和 `src/main.rs` 的 `stream_video` 同形：先一条 Text 头说明编码，随后每条
 * Binary 是一帧 JPEG。改了配置就重发一条头而不断开连接。
 */
suspend fun DefaultWebSocketSession.runVideoStream(hub: VideoHub, idleNoticeMs: Long = IDLE_NOTICE_MS) {
    val subscription = hub.subscribe()
    try {
        coroutineScope {
            val pump = launch {
                try {
                    hub.settings.collectLatest { settings ->
                        send(Frame.Text(codecHeader(settings)))
                        pumpMjpeg(subscription.frames, hub, idleNoticeMs)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // 发不出去就是对端走了，下面读 incoming 的循环会跟着结束
                }
            }
            // 收方向必须有人读：不读的话对端的 close 不会被处理，采集也就一直开着
            for (frame in incoming) Unit
            pump.cancel()
        }
    } finally {
        subscription.close()
    }
}

internal fun codecHeader(settings: VideoSettings): String = buildJsonObject {
    put("op", "codec")
    put("codec", "mjpeg")
    if (settings.codec.equals("h264", ignoreCase = true)) put("fallbackReason", H264_UNSUPPORTED)
}.toString()

private suspend fun DefaultWebSocketSession.pumpMjpeg(
    frames: ReceiveChannel<ByteArray>,
    hub: VideoHub,
    idleNoticeMs: Long,
) {
    // 重复帧不再发：采集卡对着静止画面吐的是逐字节相同的 JPEG，客户端画布上已经是这一帧了。
    // 每次发头都从空开始，所以新连接和改配置后的第一帧一定会发出去。
    var last: ByteArray? = null
    var skipped = 0L
    var nextNotice = nowMs() + idleNoticeMs
    while (true) {
        val remaining = nextNotice - nowMs()
        val frame = if (remaining > 0) {
            try {
                withTimeoutOrNull(remaining) { frames.receive() }
            } catch (_: ClosedReceiveChannelException) {
                return
            }
        } else {
            null
        }
        if (frame == null) {
            send(Frame.Text(buildJsonObject { put("op", "idle"); put("skipped", skipped) }.toString()))
            nextNotice = nowMs() + idleNoticeMs
            continue
        }
        // 重复帧不重置计时器：静止多久就该照着节奏报多久
        if (last != null && last.contentEquals(frame)) {
            skipped++
            continue
        }
        last = frame
        nextNotice = nowMs() + idleNoticeMs
        hub.recordSent(frame.size)
        send(Frame.Binary(true, frame))
    }
}

private fun nowMs() = System.nanoTime() / 1_000_000
