package com.rustkvm.host.video

import com.rustkvm.protocol.VideoDevice
import com.rustkvm.protocol.VideoSettings

/** 一路已经开着的采集。[readFrame] 阻塞到下一帧完整 JPEG；设备出错、拔掉或长时间不出帧时抛异常。 */
interface CaptureStream : AutoCloseable {
    val width: Int
    val height: Int

    fun readFrame(): ByteArray

    /** 可以从别的线程调，用来打断阻塞中的 [readFrame]。 */
    override fun close()
}

/** 采集后端。UVC 走 [com.rustkvm.host.uvc]；以后要接 Camera2 外接摄像头也是实现这个。 */
interface VideoDevices {
    /** 给 `/api/devices` 用，只列打得开的（已授权的）。 */
    fun list(): List<VideoDevice>

    /** 按 `settings.device` 挑设备（名字包含即可，挑不中用第一个），按分辨率/fps 协商最接近的格式。 */
    fun open(settings: VideoSettings): CaptureStream
}
