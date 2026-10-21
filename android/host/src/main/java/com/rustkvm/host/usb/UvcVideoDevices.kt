package com.rustkvm.host.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rustkvm.host.uvc.UvcCamera
import com.rustkvm.host.video.CaptureStream
import com.rustkvm.host.video.VideoDevices
import com.rustkvm.protocol.VideoDevice
import com.rustkvm.protocol.VideoSettings
import java.io.IOException

/**
 * OTG 上的 UVC 采集卡（MS2109 一类）。注意：Android 10 起给视频类 USB 设备授权要求 App
 * 已经拿到 CAMERA 运行时权限，否则系统直接拒绝、连弹框都没有——宿主机页面点「授权」前会先要这个。
 */
class UvcVideoDevices(context: Context) : VideoDevices {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    data class Entry(val device: VideoDevice, val hasPermission: Boolean)

    fun entries(): List<Entry> = cameras().map { Entry(describe(it), usbManager.hasPermission(it)) }

    override fun list(): List<VideoDevice> = entries().filter { it.hasPermission }.map { it.device }

    fun requestPermission(name: String) {
        val device = cameras().firstOrNull { it.deviceName == name } ?: return
        requestUsbPermission(appContext, usbManager, device)
    }

    override fun open(settings: VideoSettings): CaptureStream {
        val all = cameras()
        if (all.isEmpty()) throw IOException("没有找到 USB 采集卡")
        val permitted = all.filter(usbManager::hasPermission)
        if (permitted.isEmpty()) throw IOException("采集卡还没授权，请在宿主机页面点「授权」")
        val hint = settings.device
        val device = permitted.firstOrNull { hint.isNotEmpty() && describe(it).name.contains(hint) } ?: permitted.first()
        return UvcCamera.open(usbManager, device, settings)
    }

    private fun cameras(): List<UsbDevice> = usbManager.deviceList.values
        .filter { device ->
            (0 until device.interfaceCount).map(device::getInterface)
                .any { it.interfaceClass == CLASS_VIDEO && it.interfaceSubclass == SUBCLASS_STREAMING }
        }
        .sortedBy { it.deviceName }

    private fun describe(device: UsbDevice) = VideoDevice(
        index = device.deviceName,
        name = runCatching { device.productName }.getOrNull() ?: "USB 采集卡",
    )

    private companion object {
        const val CLASS_VIDEO = 0x0E
        const val SUBCLASS_STREAMING = 0x02
    }
}
