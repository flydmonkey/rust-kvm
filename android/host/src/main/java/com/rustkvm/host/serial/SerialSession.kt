package com.rustkvm.host.serial

import android.util.Log
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * `/ws/serial` 的一次连接，流程与 `src/serial_bridge.rs` 的 `handle` 一一对应。
 *
 * 这里刻意不认识 CH9329 协议：组帧、校验、波特率探测全在客户端。宿主机只保证口是我开的、
 * 字节原样进出，唯一例外是断线时的全释放包。
 */
suspend fun DefaultWebSocketSession.runSerialBridge(
    hub: SerialHub,
    ports: SerialPorts,
    takeover: Boolean,
    openTimeoutMs: Long = OPEN_TIMEOUT_MS,
) {
    val seat = hub.claim(takeover)
    if (seat == null) {
        Log.i(TAG, "已有控制端，拒绝新的 /ws/serial 连接（takeover=$takeover）")
        // 只陈述事实：这条会被原样显示，接下来怎么做由各自的页面去说
        send(
            Frame.Text(
                SerialServerOp.busy(
                    if (takeover) {
                        "接管失败：现任控制端没有在 5 秒内退位，它可能卡住了。"
                    } else {
                        "串口正被另一个控制端占用。"
                    },
                ),
            ),
        )
        close(CloseReason(CloseReason.Codes.NORMAL, "串口被占用"))
        return
    }
    Log.i(TAG, "控制端已接入 /ws/serial")

    var preempted = false
    var link: SerialLink? = null
    try {
        // 不主动推「当前是关着的」：客户端把 status 当作 open/close 的应答来配对
        loop@ while (true) {
            val event: Event = select {
                seat.evicted.onAwait { Event.Evicted }
                link?.reads?.onReceiveCatching { Event.SerialData(it.getOrNull()) }
                incoming.onReceiveCatching { Event.Incoming(it.getOrNull()) }
            }
            when (event) {
                Event.Evicted -> {
                    preempted = true
                    break@loop
                }

                is Event.SerialData -> {
                    val bytes = event.bytes
                    if (bytes != null) {
                        send(Frame.Binary(true, bytes))
                    } else {
                        // 线程自己退了（拔线、写失败），告诉客户端别再发了
                        link?.let { closed -> withContext(Dispatchers.IO) { closed.shutdown() } }
                        link = null
                        send(Frame.Text(SerialServerOp.error("串口连接已中断")))
                        send(Frame.Text(SerialServerOp.closed()))
                    }
                }

                is Event.Incoming -> when (val frame = event.frame) {
                    null -> break@loop
                    is Frame.Binary -> {
                        val active = link
                        if (active == null) {
                            send(Frame.Text(SerialServerOp.error("串口未打开")))
                        } else {
                            // 写不进去就等读通道关闭那条分支去报错收尾
                            active.write(frame.data)
                        }
                    }

                    is Frame.Text -> {
                        val op = try {
                            parseSerialClientOp(frame.readText())
                        } catch (error: Exception) {
                            send(Frame.Text(SerialServerOp.error("控制消息无法解析：${error.message}")))
                            continue@loop
                        }
                        val reply = when (op) {
                            is SerialClientOp.Open -> {
                                link?.let { old -> withContext(Dispatchers.IO) { old.shutdown() } }
                                link = null
                                try {
                                    val opened = openLink(ports, op, openTimeoutMs)
                                    link = opened
                                    SerialServerOp.status(true, opened.port, opened.baudRate)
                                } catch (error: Exception) {
                                    SerialServerOp.error(error.message ?: "打开串口失败")
                                }
                            }

                            SerialClientOp.Close -> {
                                link?.let { old -> withContext(Dispatchers.IO) { old.shutdown() } }
                                link = null
                                SerialServerOp.closed()
                            }

                            SerialClientOp.Ping -> SerialServerOp.pong(link?.serialRoundTripUs)
                        }
                        send(Frame.Text(reply))
                    }

                    else -> Unit
                }
            }
        }
    } catch (error: Exception) {
        Log.i(TAG, "控制端连接中断：${error.message}")
    } finally {
        withContext(NonCancellable) {
            // 先把话说出去再收串口：被踢的一方必须知道原因，否则它只看到连接断了
            if (preempted) {
                runCatching {
                    send(Frame.Text(SerialServerOp.busy("控制权已被另一个客户端接管。点键鼠图标可以抢回来。")))
                    close(CloseReason(CloseReason.Codes.NORMAL, "控制权已被接管"))
                }
            }
            link?.let { active ->
                ReleaseFrames.ALL.forEach(active::write)
                withContext(Dispatchers.IO) { active.shutdown() }
                Log.i(TAG, "控制端断开，已下发全释放包")
            }
            seat.release()
            Log.i(TAG, "控制端已离开 /ws/serial（preempted=$preempted）")
        }
    }
}

private suspend fun openLink(
    ports: SerialPorts,
    op: SerialClientOp.Open,
    timeoutMs: Long,
): SerialLink = withTimeout(timeoutMs) {
    withContext(Dispatchers.IO) {
        val available = ports.list()
        val name = resolvePortName(op.port, available)
            ?: throw IllegalStateException(
                if (available.isEmpty()) "宿主机上没有 USB 串口设备" else "找不到串口 ${op.port}",
            )
        val raw = try {
            ports.open(name, op.baudRate)
        } catch (error: Exception) {
            throw IllegalStateException("打开 $name 失败：${error.message}", error)
        }
        SerialLink.start(name, op.baudRate, raw)
    }
}

private sealed interface Event {
    data object Evicted : Event
    class SerialData(val bytes: ByteArray?) : Event
    class Incoming(val frame: Frame?) : Event
}

private const val TAG = "SerialSession"
const val OPEN_TIMEOUT_MS = 3_000L
