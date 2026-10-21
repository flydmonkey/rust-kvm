package com.rustkvm.host.uvc

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException

internal object UsbIsoNative {
    init {
        System.loadLibrary("rustkvm_usbiso")
    }

    /** 返回句柄；负数是 -errno。 */
    @JvmStatic
    external fun start(fd: Int, endpoint: Int, packetSize: Int, packets: Int, urbCount: Int): Long

    /** 返回包数；0 是超时；负数是 -errno。 */
    @JvmStatic
    external fun reap(handle: Long, out: ByteArray, lengths: IntArray, timeoutMs: Int): Int

    @JvmStatic
    external fun stop(handle: Long)
}

/**
 * 等时端点。在途 URB 开 [URB_COUNT] 个、每个 [PACKETS_PER_URB] 包：高速设备每 125µs 一包，
 * 32 包是 4ms，8 个 URB 能扛住 32ms 的调度抖动——libuvc 的默认值也在这个量级。
 */
internal class IsoPayloadReader(
    connection: UsbDeviceConnection,
    endpointAddress: Int,
    private val packetBytes: Int,
) : PayloadReader {
    private val buffer = ByteArray(packetBytes * PACKETS_PER_URB)
    private val lengths = IntArray(PACKETS_PER_URB)
    private var handle: Long

    init {
        handle = UsbIsoNative.start(connection.fileDescriptor, endpointAddress, packetBytes, PACKETS_PER_URB, URB_COUNT)
        if (handle < 0) throw IOException("启动等时传输失败（errno ${-handle}）")
    }

    // reap 和 stop 不能并发：关闭可能来自别的线程，而 reap 每次最多阻塞 timeoutMs
    @Synchronized
    override fun read(timeoutMs: Int, onPayload: (ByteArray, Int, Int) -> Unit) {
        if (handle == 0L) throw IOException("采集已关闭")
        val packets = UsbIsoNative.reap(handle, buffer, lengths, timeoutMs)
        if (packets < 0) throw IOException("采集卡连接中断（errno ${-packets}）")
        for (i in 0 until packets) {
            if (lengths[i] > 0) onPayload(buffer, i * packetBytes, lengths[i])
        }
    }

    @Synchronized
    override fun close() {
        if (handle == 0L) return
        UsbIsoNative.stop(handle)
        handle = 0L
    }

    private companion object {
        const val PACKETS_PER_URB = 32
        const val URB_COUNT = 8
    }
}

/** bulk 端点：一次传输就是一个负载（以短包结束），Java API 自己就能做。 */
internal class BulkPayloadReader(
    private val connection: UsbDeviceConnection,
    private val endpoint: UsbEndpoint,
    maxPayloadBytes: Int,
) : PayloadReader {
    private val buffer = ByteArray(maxPayloadBytes.coerceIn(endpoint.maxPacketSize, MAX_BULK_BYTES))

    @Volatile
    private var closed = false

    // bulkTransfer 超时和出错都返回负数，分不开；设备真没了的话上层会因为一直不出帧而报错
    override fun read(timeoutMs: Int, onPayload: (ByteArray, Int, Int) -> Unit) {
        if (closed) throw IOException("采集已关闭")
        val length = connection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        if (length > 0) onPayload(buffer, 0, length)
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val MAX_BULK_BYTES = 1 shl 20
    }
}
