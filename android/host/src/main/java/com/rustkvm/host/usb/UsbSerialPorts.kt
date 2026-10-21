package com.rustkvm.host.usb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rustkvm.host.serial.RawSerialPort
import com.rustkvm.host.serial.SerialPorts
import com.rustkvm.protocol.SerialDevice
import java.io.IOException

/** OTG 上的 USB 转串口（CH340、CP210x、FTDI、PL2303、CDC）。CH9329 模块就挂在它们上面。 */
class UsbSerialPorts(context: Context) : SerialPorts {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Entry(val device: SerialDevice, val hasPermission: Boolean)

    fun entries(): List<Entry> = drivers().map { driver ->
        Entry(describe(driver), usbManager.hasPermission(driver.device))
    }

    /** 列给控制端的只有已授权的口：没授权的打不开，列出来只会让客户端挑中它然后失败。 */
    override fun list(): List<SerialDevice> = entries().filter { it.hasPermission }.map { it.device }

    fun requestPermission(name: String) {
        val driver = drivers().firstOrNull { it.device.deviceName == name } ?: return
        requestUsbPermission(appContext, usbManager, driver.device)
    }

    override fun open(name: String, baudRate: Int): RawSerialPort {
        val driver = drivers().firstOrNull { it.device.deviceName == name }
            ?: throw IOException("设备已拔出")
        if (!usbManager.hasPermission(driver.device)) {
            throw IOException("没有 USB 权限，请在宿主机页面点「授权」")
        }
        val connection = usbManager.openDevice(driver.device)
            ?: throw IOException("系统拒绝打开 USB 设备")
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (error: Exception) {
            runCatching { port.close() }
            connection.close()
            throw IOException(error.message ?: "打开串口失败", error)
        }
        return UsbRawSerialPort(port, connection)
    }

    private fun drivers(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    private fun describe(driver: UsbSerialDriver): SerialDevice {
        val product = runCatching { driver.device.productName }.getOrNull()
        return SerialDevice(
            name = driver.device.deviceName,
            description = product ?: driver.javaClass.simpleName.removeSuffix("SerialDriver"),
        )
    }

    private class UsbRawSerialPort(
        private val port: UsbSerialPort,
        private val connection: UsbDeviceConnection,
    ) : RawSerialPort {
        override fun write(bytes: ByteArray) = port.write(bytes, WRITE_TIMEOUT_MS)

        override fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)

        override fun close() {
            runCatching { port.close() }
            connection.close()
        }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_000
    }
}
