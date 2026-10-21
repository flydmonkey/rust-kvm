package com.rustkvm.host.serial

import com.rustkvm.protocol.SerialDevice
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class FakeSerialPorts(
    var devices: List<SerialDevice> = listOf(SerialDevice("/dev/bus/usb/001/002", "USB Serial")),
) : SerialPorts {
    val opened = CopyOnWriteArrayList<FakeRawSerialPort>()

    override fun list(): List<SerialDevice> = devices

    override fun open(name: String, baudRate: Int): RawSerialPort {
        if (devices.none { it.name == name }) throw IOException("no such device")
        return FakeRawSerialPort(name, baudRate).also { opened += it }
    }

    fun last(): FakeRawSerialPort = opened.last()
}

class FakeRawSerialPort(val name: String, val baudRate: Int) : RawSerialPort {
    class Write(val bytes: ByteArray, val atNanos: Long)

    val writes = CopyOnWriteArrayList<Write>()
    private val pendingReads = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var closed = false
        private set

    @Volatile
    private var unplugged = false

    fun reply(bytes: ByteArray) {
        pendingReads += bytes
    }

    fun unplug() {
        unplugged = true
    }

    fun writtenBytes(): List<List<Int>> = writes.map { write -> write.bytes.map { it.toInt() and 0xFF } }

    override fun write(bytes: ByteArray) {
        if (closed || unplugged) throw IOException("port closed")
        writes += Write(bytes.copyOf(), System.nanoTime())
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed || unplugged) throw IOException("port closed")
        val next = pendingReads.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        next.copyInto(buffer)
        return next.size
    }

    override fun close() {
        closed = true
    }
}
