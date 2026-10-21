package com.rustkvm.host.serial

import com.rustkvm.protocol.SerialDevice
import java.io.Closeable
import java.io.IOException

/** 一个已打开的串口。读写都是阻塞的，由 [SerialLink] 的两个线程分别调用。 */
interface RawSerialPort : Closeable {
    /** 写完整个包才返回。 */
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /** 最多等 [timeoutMs]，返回读到的字节数，0 表示这段时间没有数据。 */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int
}

interface SerialPorts {
    fun list(): List<SerialDevice>

    @Throws(IOException::class)
    fun open(name: String, baudRate: Int): RawSerialPort
}

/**
 * 客户端请求的口不存在时，如果手上恰好只有一个串口就用它。
 *
 * Android 上的设备名（`/dev/bus/usb/001/002`）每次插拔都会变，Windows 那边的
 * `COM3` 则是稳定的；不放宽的话，配置里存的口名拔插一次就永远对不上了。
 */
fun resolvePortName(requested: String, available: List<SerialDevice>): String? =
    available.firstOrNull { it.name == requested }?.name
        ?: available.singleOrNull()?.name
