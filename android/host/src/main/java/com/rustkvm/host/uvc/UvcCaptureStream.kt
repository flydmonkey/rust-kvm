package com.rustkvm.host.uvc

import com.rustkvm.host.video.CaptureStream
import java.io.IOException

/** 一次搬一批 UVC 负载上来。等时端点一批是一个 URB 里的若干包，bulk 端点一批就是一次传输。 */
interface PayloadReader : AutoCloseable {
    /**
     * 最多等 [timeoutMs]，把这期间到的负载逐个交给 [onPayload]。超时不算错，直接返回；
     * 设备拔掉或者传输出错才抛 [IOException]。
     */
    fun read(timeoutMs: Int, onPayload: (ByteArray, Int, Int) -> Unit)

    override fun close()
}

/**
 * [readFrame] 一直拉负载直到拼出一帧。连续 [frameTimeoutMs] 没出帧就当设备坏了抛错——
 * 不这样的话卡死的采集卡会让采集线程永远阻塞，「没人看就关设备」也就永远等不到。
 */
class UvcCaptureStream(
    private val reader: PayloadReader,
    override val width: Int,
    override val height: Int,
    maxFrameBytes: Int,
    private val frameTimeoutMs: Long = FRAME_TIMEOUT_MS,
    private val onClose: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : CaptureStream {
    private val pending = ArrayDeque<ByteArray>()
    private val assembler = UvcFrameAssembler(maxFrameBytes) { frame ->
        // 只留最新的：拼出来的帧比取走的快时，旧帧已经没有意义
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(frame)
    }

    @Volatile
    private var closed = false

    override fun readFrame(): ByteArray {
        val deadline = clock() + frameTimeoutMs
        while (pending.isEmpty()) {
            if (closed) throw IOException("采集已关闭")
            if (clock() >= deadline) {
                throw IOException("采集卡 ${frameTimeoutMs / 1000} 秒没有出画面（HDMI 线没接，或者 USB 供电不足？）")
            }
            reader.read(READ_SLICE_MS, assembler::onPayload)
        }
        return pending.removeFirst()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { reader.close() }
        onClose()
    }

    companion object {
        const val FRAME_TIMEOUT_MS = 3_000L
        private const val READ_SLICE_MS = 100
        private const val MAX_PENDING = 2
    }
}
