package com.rustkvm.host.video

import com.rustkvm.protocol.VideoDevice
import com.rustkvm.protocol.VideoSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class VideoSubscription internal constructor(
    val frames: ReceiveChannel<ByteArray>,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}

data class VideoStats(
    val capturing: Boolean,
    val viewers: Int,
    val fps: Double,
    val mbps: Double,
    val sentMbps: Double,
    val frames: Long,
    val lastFrameBytes: Int,
    val resolution: Pair<Int, Int>?,
    val error: String?,
)

/**
 * 采集卡到观看者之间的那一段，规矩照搬 `src/video.rs`：设备独占，有人看才开，最后一个观看者
 * 走后再采 [idleGraceMs] 才关（刷新页面会断开重连，不留缓冲设备就会反复开关）；配置变了立刻
 * 按新配置重开；出错后等 [retryDelayMs] 再试，免得拔掉设备后疯转。
 *
 * 帧是采集卡直出的 JPEG，原样分发，一次都不解码。每个观看者一个只留最新几帧的队列：
 * 慢的观看者丢自己的帧，拖不慢别人。
 */
class VideoHub(
    private val devices: VideoDevices,
    initial: VideoSettings,
    private val idleGraceMs: Long = IDLE_GRACE_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
) : AutoCloseable {
    private val mutableSettings = MutableStateFlow(initial)
    val settings: StateFlow<VideoSettings> = mutableSettings.asStateFlow()

    private val subscribers = CopyOnWriteArrayList<Channel<ByteArray>>()
    private val lock = Object()
    private val sent = AtomicLong()
    private val stats = StatsWindow()

    @Volatile
    private var closed = false

    @Volatile
    private var current: CaptureStream? = null

    private val worker = thread(name = "video-capture", isDaemon = true) { supervise() }

    fun subscribe(): VideoSubscription {
        val channel = Channel<ByteArray>(FRAMES_PER_VIEWER, BufferOverflow.DROP_OLDEST)
        subscribers += channel
        wake()
        return VideoSubscription(channel) {
            subscribers.remove(channel)
            channel.close()
        }
    }

    /** 设置页保存时会把整份配置推过来；视频部分没变就什么都不做，只改了串口不该把画面掐断。 */
    fun updateSettings(settings: VideoSettings) {
        if (mutableSettings.value == settings) return
        mutableSettings.value = settings
        wake()
    }

    fun listDevices(): List<VideoDevice> = devices.list()

    /** 推流循环每发出一帧报一次。重复帧不发，所以真实出口流量只有这里数得到。 */
    fun recordSent(bytes: Int) {
        sent.addAndGet(bytes.toLong())
    }

    fun stats(): VideoStats = stats.snapshot(subscribers.size)

    override fun close() {
        closed = true
        wake()
        current?.close()
        worker.join(JOIN_TIMEOUT_MS)
        subscribers.forEach { it.close() }
        subscribers.clear()
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun supervise() {
        while (true) {
            synchronized(lock) {
                while (!closed && subscribers.isEmpty()) lock.wait()
            }
            if (closed) return
            val wanted = mutableSettings.value
            try {
                capture(devices.open(wanted), wanted)
            } catch (error: Exception) {
                if (closed) return
                stats.error(error.message ?: error.javaClass.simpleName)
                pauseAfterFailure(wanted)
            }
        }
    }

    private fun capture(stream: CaptureStream, wanted: VideoSettings) {
        current = stream
        try {
            if (closed) return
            stats.started(stream.width, stream.height)
            var idleSince = -1L
            while (!closed) {
                val frame = stream.readFrame()
                for (subscriber in subscribers) subscriber.trySend(frame)
                stats.frame(frame.size, sent.get())
                if (mutableSettings.value != wanted) return
                if (subscribers.isEmpty()) {
                    val now = nowMs()
                    if (idleSince < 0) idleSince = now
                    if (now - idleSince >= idleGraceMs) return
                } else {
                    idleSince = -1
                }
            }
        } finally {
            current = null
            stream.close()
            stats.stopped()
        }
    }

    // 只有关闭或者改了配置才提前醒：观看者断开重连不该让一个打不开的设备被连着重试
    private fun pauseAfterFailure(failed: VideoSettings) {
        val deadline = nowMs() + retryDelayMs
        synchronized(lock) {
            while (!closed && mutableSettings.value == failed) {
                val remaining = deadline - nowMs()
                if (remaining <= 0) break
                lock.wait(remaining)
            }
        }
    }

    private class StatsWindow {
        private var capturing = false
        private var frames = 0L
        private var lastFrameBytes = 0
        private var resolution: Pair<Int, Int>? = null
        private var error: String? = null
        private var fps = 0.0
        private var mbps = 0.0
        private var sentMbps = 0.0
        private var windowStart = -1L
        private var windowFrames = 0
        private var windowBytes = 0L
        private var windowSentStart = 0L

        @Synchronized
        fun started(width: Int, height: Int) {
            capturing = true
            error = null
            resolution = width to height
            windowStart = -1
        }

        @Synchronized
        fun stopped() {
            capturing = false
            clearRates()
        }

        @Synchronized
        fun error(message: String) {
            capturing = false
            error = message
            clearRates()
        }

        // 借采集的节奏分窗：采集一直在跑，哪怕一帧都没往外发，窗口也照常翻页
        @Synchronized
        fun frame(bytes: Int, sentTotal: Long) {
            frames++
            lastFrameBytes = bytes
            val now = nowMs()
            if (windowStart < 0) {
                windowStart = now
                windowFrames = 0
                windowBytes = 0
                windowSentStart = sentTotal
            }
            windowFrames++
            windowBytes += bytes
            val elapsed = now - windowStart
            if (elapsed >= 1_000) {
                val seconds = elapsed / 1_000.0
                fps = windowFrames / seconds
                mbps = windowBytes * 8 / seconds / 1_000_000
                sentMbps = (sentTotal - windowSentStart) * 8 / seconds / 1_000_000
                windowStart = -1
            }
        }

        @Synchronized
        fun snapshot(viewers: Int) = VideoStats(
            capturing = capturing,
            viewers = viewers,
            fps = fps,
            mbps = mbps,
            sentMbps = sentMbps,
            frames = frames,
            lastFrameBytes = lastFrameBytes,
            resolution = resolution,
            error = error,
        )

        private fun clearRates() {
            fps = 0.0
            mbps = 0.0
            sentMbps = 0.0
            windowStart = -1
        }
    }

    companion object {
        const val IDLE_GRACE_MS = 3_000L
        const val RETRY_DELAY_MS = 1_000L
        private const val FRAMES_PER_VIEWER = 2
        private const val JOIN_TIMEOUT_MS = 2_000L
    }
}

private fun nowMs() = System.nanoTime() / 1_000_000
