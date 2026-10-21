package com.rustkvm.host.uvc

/** 一个 MJPEG 分辨率。`intervals` 单位是 100ns（UVC 的惯例），30fps 就是 333333。 */
data class UvcFrame(
    val index: Int,
    val width: Int,
    val height: Int,
    val defaultInterval: Int,
    val intervals: List<Int>,
    val maxFrameBytes: Int,
)

data class UvcFormat(val index: Int, val frames: List<UvcFrame>)

enum class UvcTransfer { ISOCHRONOUS, BULK }

/** 视频流接口的一个 alternate setting 和它的那个 IN 端点。 */
data class UvcAltSetting(
    val alternate: Int,
    val endpointAddress: Int,
    val transfer: UvcTransfer,
    /** 每个（微）帧能搬的字节：高带宽端点和 USB 3 的 burst 都已经乘进去了。 */
    val packetBytes: Int,
)

data class UvcStreamingInterface(
    val number: Int,
    val mjpeg: UvcFormat?,
    val alternates: List<UvcAltSetting>,
)

data class UvcDescription(
    /** bcdUVC，例如 0x0100、0x0110。probe 结构的长度看它。 */
    val uvcVersion: Int,
    val streaming: List<UvcStreamingInterface>,
)

private const val DESC_CONFIGURATION = 0x02
private const val DESC_INTERFACE = 0x04
private const val DESC_ENDPOINT = 0x05
private const val DESC_CS_INTERFACE = 0x24
private const val DESC_SS_ENDPOINT_COMPANION = 0x30

private const val CLASS_VIDEO = 0x0E
private const val SUBCLASS_CONTROL = 0x01
private const val SUBCLASS_STREAMING = 0x02

private const val VC_HEADER = 0x01
private const val VS_FORMAT_MJPEG = 0x06
private const val VS_FRAME_MJPEG = 0x07

/**
 * 解析 `UsbDeviceConnection.getRawDescriptors()` 那串字节（设备描述符后面跟着配置描述符）。
 * 只看第一个配置；采集卡都只有一个。不是 UVC 设备时返回 null。
 *
 * 只收 MJPEG：采集卡的 YUY2 在 1080p 下只有 5fps（见 h264 设计文档），而 MJPEG 可以
 * 原样转发、服务端一次都不解码。
 */
fun parseUvcDescriptors(raw: ByteArray): UvcDescription? {
    var uvcVersion = 0
    val streaming = linkedMapOf<Int, StreamingBuilder>()
    var currentInterface: StreamingBuilder? = null
    var currentAlternate = 0
    var inControl = false
    var formatIndex = -1
    var lastAlt: UvcAltSetting? = null
    var configurations = 0

    var offset = 0
    while (offset + 2 <= raw.size) {
        val length = raw.u8(offset)
        if (length < 2 || offset + length > raw.size) break
        val type = raw.u8(offset + 1)
        val d = Desc(raw, offset, length)
        when (type) {
            DESC_CONFIGURATION -> if (++configurations > 1) break
            DESC_INTERFACE -> if (length >= 9) {
                val number = d.u8(2)
                currentAlternate = d.u8(3)
                val isVideo = d.u8(5) == CLASS_VIDEO
                inControl = isVideo && d.u8(6) == SUBCLASS_CONTROL
                currentInterface = if (isVideo && d.u8(6) == SUBCLASS_STREAMING) {
                    streaming.getOrPut(number) { StreamingBuilder(number) }
                } else {
                    null
                }
                formatIndex = -1
                lastAlt = null
            }
            DESC_CS_INTERFACE -> if (length >= 3) {
                val subtype = d.u8(2)
                val target = currentInterface
                when {
                    inControl && subtype == VC_HEADER && length >= 5 -> uvcVersion = d.u16(3)
                    // 罕见的第二个 MJPEG 格式不收，否则两组帧会混进同一个格式
                    target != null && subtype == VS_FORMAT_MJPEG && length >= 4 -> {
                        val index = d.u8(3)
                        formatIndex = if (target.formatIndex < 0 || target.formatIndex == index) index else -1
                        if (formatIndex >= 0) target.formatIndex = formatIndex
                    }
                    // 帧描述符紧跟在所属格式后面；跟在 YUY2 格式后面的那些 formatIndex 是 -1，不收
                    target != null && subtype == VS_FRAME_MJPEG && formatIndex >= 0 &&
                        target.formatIndex == formatIndex && length >= 26 -> target.frames += parseFrame(d)
                    target != null && subtype != VS_FRAME_MJPEG -> formatIndex = -1
                }
            }
            DESC_ENDPOINT -> {
                val target = currentInterface
                if (target != null && length >= 7 && d.u8(2) and 0x80 != 0) {
                    val attributes = d.u8(3) and 0x03
                    val transfer = when (attributes) {
                        1 -> UvcTransfer.ISOCHRONOUS
                        2 -> UvcTransfer.BULK
                        else -> null
                    }
                    if (transfer != null) {
                        val packet = d.u16(4)
                        val base = packet and 0x7FF
                        val extra = (packet shr 11) and 0x03
                        val alt = UvcAltSetting(currentAlternate, d.u8(2), transfer, base * (1 + extra))
                        target.alternates += alt
                        lastAlt = alt
                    }
                }
            }
            // USB 3 端点：每个服务间隔能搬 (bMaxBurst+1) × (Mult+1) 个包
            DESC_SS_ENDPOINT_COMPANION -> {
                val target = currentInterface
                val alt = lastAlt
                if (target != null && alt != null && length >= 4 && alt.transfer == UvcTransfer.ISOCHRONOUS) {
                    val burst = d.u8(2) + 1
                    val mult = (d.u8(3) and 0x03) + 1
                    val widened = alt.copy(packetBytes = alt.packetBytes * burst * mult)
                    target.alternates[target.alternates.lastIndex] = widened
                    lastAlt = widened
                }
            }
        }
        offset += length
    }

    if (streaming.isEmpty()) return null
    return UvcDescription(uvcVersion, streaming.values.map { it.build() })
}

private fun parseFrame(d: Desc): UvcFrame {
    val intervalType = d.u8(25)
    val intervals = if (intervalType == 0) {
        // 连续区间：给出两端就够选了
        listOfNotNull(d.u32OrNull(26), d.u32OrNull(30))
    } else {
        (0 until intervalType).mapNotNull { d.u32OrNull(26 + it * 4) }
    }
    return UvcFrame(
        index = d.u8(3),
        width = d.u16(5),
        height = d.u16(7),
        defaultInterval = d.u32(21),
        intervals = intervals,
        maxFrameBytes = d.u32(17),
    )
}

private class StreamingBuilder(val number: Int) {
    var formatIndex = -1
    val frames = mutableListOf<UvcFrame>()
    val alternates = mutableListOf<UvcAltSetting>()

    fun build() = UvcStreamingInterface(
        number = number,
        mjpeg = if (formatIndex >= 0 && frames.isNotEmpty()) UvcFormat(formatIndex, frames.toList()) else null,
        alternates = alternates.toList(),
    )
}

private class Desc(val raw: ByteArray, val offset: Int, val length: Int) {
    fun u8(i: Int) = raw.u8(offset + i)
    fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
    fun u32OrNull(i: Int) = if (i + 4 <= length) u32(i) else null
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
