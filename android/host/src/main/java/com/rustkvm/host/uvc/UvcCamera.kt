package com.rustkvm.host.uvc

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.rustkvm.host.video.CaptureStream
import com.rustkvm.protocol.VideoSettings
import java.io.IOException

/** 打开一张 UVC 采集卡的 MJPEG 流：解析描述符 → 选格式 → probe/commit → 切 alternate setting → 开始搬负载。 */
internal object UvcCamera {
    fun open(manager: UsbManager, device: UsbDevice, settings: VideoSettings): CaptureStream {
        val connection = manager.openDevice(device) ?: throw IOException("系统拒绝打开采集卡")
        try {
            val uvc = parseUvcDescriptors(connection.rawDescriptors ?: ByteArray(0))
                ?: throw IOException("${device.productName ?: device.deviceName} 不是 UVC 采集卡")
            val selection = selectMjpeg(uvc, settings.width, settings.height, settings.fps)
                ?: throw IOException("采集卡不支持 MJPEG，透传方案不成立")
            val number = selection.streaming.number
            val idle = device.findInterface(number, 0) ?: throw IOException("找不到视频流接口 $number")
            if (!connection.claimInterface(idle, true)) throw IOException("无法占用采集卡（被别的 App 占着？）")
            // 有的设备只在 alt 0 下才接受 probe，先切回去
            connection.setInterface(idle)

            val negotiated = negotiate(connection, uvc, selection)
            val alt = pickAltSetting(selection.streaming.alternates, negotiated.maxPayloadTransferSize)
                ?: throw IOException("采集卡没有可用的视频端点")
            val streamInterface = device.findInterface(number, alt.alternate)
                ?: throw IOException("找不到 alternate setting ${alt.alternate}")
            val reader = when (alt.transfer) {
                UvcTransfer.ISOCHRONOUS -> {
                    if (!connection.setInterface(streamInterface)) throw IOException("采集卡分不到 USB 带宽（同一 Hub 上设备太多？）")
                    IsoPayloadReader(connection, alt.endpointAddress, alt.packetBytes)
                }
                UvcTransfer.BULK -> {
                    val endpoint = (0 until streamInterface.endpointCount).map(streamInterface::getEndpoint)
                        .firstOrNull { it.address == alt.endpointAddress && it.direction == UsbConstants.USB_DIR_IN }
                        ?: throw IOException("找不到视频端点")
                    BulkPayloadReader(connection, endpoint, negotiated.maxPayloadTransferSize)
                }
            }
            val frame = selection.frame
            val maxFrameBytes = listOf(negotiated.maxVideoFrameSize, frame.maxFrameBytes, frame.width * frame.height * 2)
                .first { it > 0 }
                .coerceIn(MIN_FRAME_BYTES, MAX_FRAME_BYTES)
            return UvcCaptureStream(reader, frame.width, frame.height, maxFrameBytes, onClose = {
                runCatching { connection.setInterface(idle) }
                runCatching { connection.releaseInterface(idle) }
                connection.close()
            })
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    /**
     * SET_CUR 想要的 → GET_CUR 设备认可的 → 原样 COMMIT。描述符里声明 1.1 却只认 26 字节
     * 的设备不少，所以按版本长度失败后再用 26 字节试一次。
     */
    private fun negotiate(connection: UsbDeviceConnection, uvc: UvcDescription, selection: UvcSelection): UvcProbe {
        val number = selection.streaming.number
        val lengths = listOf(UvcProbe.lengthFor(uvc.uvcVersion), 26).distinct()
        var lastError = "采集卡不接受这个格式"
        for (length in lengths) {
            val wanted = UvcProbe(selection.format.index, selection.frame.index, selection.interval, length = length)
            if (setCur(connection, number, VS_PROBE_CONTROL, wanted.encode()) < 0) {
                lastError = "采集卡拒绝了格式协商（probe）"
                continue
            }
            val current = ByteArray(length)
            val read = connection.controlTransfer(GET_REQUEST, GET_CUR, VS_PROBE_CONTROL shl 8, number, current, length, TIMEOUT_MS)
            if (read < 26) {
                lastError = "读不回采集卡的协商结果"
                continue
            }
            if (setCur(connection, number, VS_COMMIT_CONTROL, current.copyOf(length)) < 0) {
                lastError = "采集卡拒绝了格式提交（commit）"
                continue
            }
            return UvcProbe.decode(current, read)
        }
        throw IOException(lastError)
    }

    private fun setCur(connection: UsbDeviceConnection, number: Int, control: Int, data: ByteArray) =
        connection.controlTransfer(SET_REQUEST, SET_CUR, control shl 8, number, data, data.size, TIMEOUT_MS)

    private fun UsbDevice.findInterface(number: Int, alternate: Int): UsbInterface? =
        (0 until interfaceCount).map(::getInterface).firstOrNull { it.id == number && it.alternateSetting == alternate }

    private const val SET_REQUEST = 0x21
    private const val GET_REQUEST = 0xA1
    private const val SET_CUR = 0x01
    private const val GET_CUR = 0x81
    private const val VS_PROBE_CONTROL = 0x01
    private const val VS_COMMIT_CONTROL = 0x02
    private const val TIMEOUT_MS = 1_000
    private const val MIN_FRAME_BYTES = 64 * 1024
    private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
}
