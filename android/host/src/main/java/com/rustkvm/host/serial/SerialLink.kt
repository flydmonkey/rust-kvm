package com.rustkvm.host.serial

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.trySendBlocking
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 打开的串口：一个写线程、一个读线程。
 *
 * 和 Windows 端（`src/serial_bridge.rs`）不同，这里不用单线程轮询：USB 串口的读写走
 * 两个独立的 bulk 端点，阻塞读不会挡住写。
 *
 * 串口线程退出（拔线、写失败）时 [reads] 会被关闭，会话靠这个知道口没了。
 */
class SerialLink private constructor(
    val port: String,
    val baudRate: Int,
    private val raw: RawSerialPort,
    private val packetGapNs: Long,
) {
    private val writes = LinkedBlockingQueue<ByteArray>()
    private val readChannel = Channel<ByteArray>(READ_CAPACITY)
    val reads: ReceiveChannel<ByteArray> = readChannel

    /** 串口段最近一次往返（微秒），0 表示还没测到。CH9329 一问一答，只有这里配得上对。 */
    private val roundTripUs = AtomicLong(0)
    private val awaitingReplySince = AtomicLong(0)

    @Volatile
    private var stopping = false

    private val writer = Thread({ pumpWrites() }, "serial-write-$port")
    private val reader = Thread({ pumpReads() }, "serial-read-$port")

    val serialRoundTripUs: Long? get() = roundTripUs.get().takeIf { it > 0 }

    /** 返回 false 表示串口已经不在了。 */
    fun write(bytes: ByteArray): Boolean {
        if (stopping) return false
        return writes.offer(bytes)
    }

    /**
     * 先把已排队的包（包括全释放包）写完再关口，并等两个线程真的退出：
     * 探测波特率时会立刻用新速率重开同一个口。
     */
    fun shutdown() {
        stopping = true
        writes.offer(POISON)
        writer.join(SHUTDOWN_WAIT_MS)
        runCatching { raw.close() }
        reader.join(SHUTDOWN_WAIT_MS)
        readChannel.close()
    }

    private fun start() {
        writer.isDaemon = true
        reader.isDaemon = true
        writer.start()
        reader.start()
    }

    private fun pumpWrites() {
        var lastWriteAt = 0L
        try {
            while (true) {
                val bytes = writes.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (bytes === POISON) return
                // 芯片按空闲超过 3ms 分包，网络抖动会把两包挤到一起到，出口前再兜一次底
                val since = System.nanoTime() - lastWriteAt
                if (lastWriteAt != 0L && since < packetGapNs) {
                    sleepNanos(packetGapNs - since)
                }
                raw.write(bytes)
                lastWriteAt = System.nanoTime()
                awaitingReplySince.set(lastWriteAt)
            }
        } catch (error: Exception) {
            if (!stopping) Log.w(TAG, "串口 $port 写失败", error)
            failed()
        }
    }

    private fun pumpReads() {
        val buffer = ByteArray(READ_CHUNK)
        try {
            while (!stopping) {
                val read = raw.read(buffer, READ_TIMEOUT_MS)
                if (read <= 0) continue
                val since = awaitingReplySince.getAndSet(0)
                if (since != 0L) roundTripUs.set((System.nanoTime() - since) / 1_000)
                if (readChannel.trySendBlocking(buffer.copyOf(read)).isFailure) return
            }
        } catch (error: Exception) {
            if (!stopping) Log.w(TAG, "串口 $port 读失败", error)
            failed()
        }
    }

    private fun failed() {
        stopping = true
        runCatching { raw.close() }
        readChannel.close()
    }

    companion object {
        private const val TAG = "SerialLink"
        private val POISON = ByteArray(0)
        const val PACKET_GAP_MS = 4L
        private const val POLL_MS = 50L
        private const val READ_TIMEOUT_MS = 50
        private const val READ_CHUNK = 256
        private const val READ_CAPACITY = 64
        private const val SHUTDOWN_WAIT_MS = 2_000L

        fun start(
            port: String,
            baudRate: Int,
            raw: RawSerialPort,
            packetGapMs: Long = PACKET_GAP_MS,
        ): SerialLink = SerialLink(port, baudRate, raw, packetGapMs * 1_000_000).also { it.start() }

        private fun sleepNanos(nanos: Long) {
            Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
        }
    }
}
