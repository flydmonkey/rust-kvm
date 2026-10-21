package com.rustkvm.host.uvc

import kotlin.math.abs

/**
 * VS_PROBE_CONTROL / VS_COMMIT_CONTROL 的那块参数。主机先 SET_CUR 一份想要的，
 * 再 GET_CUR 读回设备认可的那份，原样 commit。
 *
 * 长度随 UVC 版本变：1.0 是 26 字节，1.1 是 34，1.5 是 48。发错长度有的设备会直接 STALL。
 */
data class UvcProbe(
    val formatIndex: Int,
    val frameIndex: Int,
    val frameInterval: Int,
    val maxVideoFrameSize: Int = 0,
    val maxPayloadTransferSize: Int = 0,
    val length: Int = 26,
) {
    fun encode(): ByteArray {
        val bytes = ByteArray(length)
        // bmHint：dwFrameInterval 固定，让设备在别的字段上让步
        bytes.put16(0, 0x0001)
        bytes[2] = formatIndex.toByte()
        bytes[3] = frameIndex.toByte()
        bytes.put32(4, frameInterval)
        bytes.put32(18, maxVideoFrameSize)
        bytes.put32(22, maxPayloadTransferSize)
        return bytes
    }

    companion object {
        fun lengthFor(uvcVersion: Int) = when {
            uvcVersion >= 0x0150 -> 48
            uvcVersion >= 0x0110 -> 34
            else -> 26
        }

        fun decode(bytes: ByteArray, length: Int = bytes.size): UvcProbe {
            require(length >= 26) { "probe 只有 $length 字节" }
            return UvcProbe(
                formatIndex = bytes[2].toInt() and 0xFF,
                frameIndex = bytes[3].toInt() and 0xFF,
                frameInterval = bytes.get32(4),
                maxVideoFrameSize = bytes.get32(18),
                maxPayloadTransferSize = bytes.get32(22),
                length = length,
            )
        }
    }
}

data class UvcSelection(
    val streaming: UvcStreamingInterface,
    val format: UvcFormat,
    val frame: UvcFrame,
    val interval: Int,
)

/**
 * 按配置挑分辨率和帧间隔，规则和 Windows 端 nokhwa 的 `Closest` 一致：分辨率挑面积最接近的，
 * 一样近时取大的；帧间隔挑最接近 1/fps 的。
 */
fun selectMjpeg(description: UvcDescription, width: Int, height: Int, fps: Int): UvcSelection? {
    val wantedArea = width.toLong() * height
    val wantedInterval = if (fps > 0) 10_000_000 / fps else 333_333
    return description.streaming.mapNotNull { streaming ->
        val format = streaming.mjpeg ?: return@mapNotNull null
        val frame = format.frames.minWithOrNull(
            compareBy<UvcFrame> { abs(it.width.toLong() * it.height - wantedArea) }
                .thenByDescending { it.width.toLong() * it.height },
        ) ?: return@mapNotNull null
        val candidates = frame.intervals.ifEmpty { listOf(frame.defaultInterval) }
        val interval = candidates.minBy { abs(it - wantedInterval) }
        UvcSelection(streaming, format, frame, interval)
    }.minByOrNull { abs(it.frame.width.toLong() * it.frame.height - wantedArea) }
}

/**
 * 挑 alternate setting。bulk 端点没有带宽预留，有就用；等时端点挑能装下
 * `dwMaxPayloadTransferSize` 的最小那个——挑大了会占满总线，同一个 Hub 上的 CH9329 都可能分不到带宽。
 * 一个都装不下（有的卡报的值离谱）就用最大的。
 */
fun pickAltSetting(alternates: List<UvcAltSetting>, maxPayloadTransferSize: Int): UvcAltSetting? {
    alternates.firstOrNull { it.transfer == UvcTransfer.BULK }?.let { return it }
    val iso = alternates.filter { it.transfer == UvcTransfer.ISOCHRONOUS && it.alternate > 0 && it.packetBytes > 0 }
        .sortedBy { it.packetBytes }
    return iso.firstOrNull { it.packetBytes >= maxPayloadTransferSize } ?: iso.lastOrNull()
}

private fun ByteArray.put16(i: Int, value: Int) {
    this[i] = value.toByte()
    this[i + 1] = (value shr 8).toByte()
}

private fun ByteArray.put32(i: Int, value: Int) {
    put16(i, value)
    put16(i + 2, value shr 16)
}

private fun ByteArray.get32(i: Int) =
    (this[i].toInt() and 0xFF) or
        ((this[i + 1].toInt() and 0xFF) shl 8) or
        ((this[i + 2].toInt() and 0xFF) shl 16) or
        ((this[i + 3].toInt() and 0xFF) shl 24)
