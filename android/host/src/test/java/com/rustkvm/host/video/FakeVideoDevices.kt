package com.rustkvm.host.video

import com.rustkvm.protocol.VideoDevice
import com.rustkvm.protocol.VideoSettings
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 一张假的采集卡：每 [frameIntervalMs] 吐一帧，内容由 [frameAt] 决定（默认每帧都不同）。 */
class FakeVideoDevices : VideoDevices {
    @Volatile
    var devices = listOf(VideoDevice(index = "/dev/bus/usb/001/003", name = "USB Video"))

    @Volatile
    var openError: String? = null

    @Volatile
    var frameIntervalMs = 10L

    @Volatile
    var frameAt: (Int) -> ByteArray = { fakeJpeg(it) }

    val opened = CopyOnWriteArrayList<VideoSettings>()
    val openStreams = AtomicInteger()

    override fun list(): List<VideoDevice> = devices

    override fun open(settings: VideoSettings): CaptureStream {
        openError?.let { throw IOException(it) }
        opened += settings
        openStreams.incrementAndGet()
        return Stream(settings.width, settings.height)
    }

    private inner class Stream(override val width: Int, override val height: Int) : CaptureStream {
        private var next = 0

        @Volatile
        private var closed = false

        override fun readFrame(): ByteArray {
            Thread.sleep(frameIntervalMs)
            if (closed) throw IOException("closed")
            return frameAt(next++)
        }

        override fun close() {
            if (closed) return
            closed = true
            openStreams.decrementAndGet()
        }
    }
}

fun fakeJpeg(seed: Int): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), (seed shr 8).toByte(), seed.toByte(), 0xFF.toByte(), 0xD9.toByte())

/** 不关心视频的测试用：没人订阅，采集线程只是挂着等。 */
fun idleVideoHub() = VideoHub(FakeVideoDevices(), VideoSettings())
