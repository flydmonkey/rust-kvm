package com.rustkvm.client.video

/**
 * 统计「连续解码失败、期间一帧都没画出来」的次数。偶尔失败靠重连画面就能自愈；
 * 每个 IDR 都失败多半是本机解码器不支持这路码流，重连再多也没用，得告诉用户改 MJPEG。
 *
 * 重连画面时服务端会重发 H.264 编码头，所以不能在编码头上清零，否则永远到不了阈值。
 */
class DecodeFailureTracker(private val threshold: Int = DEFAULT_THRESHOLD) {
    private var failures = 0

    val troubled: Boolean get() = failures >= threshold

    /** 返回 true 表示这一次刚好越过阈值，该提示用户了。 */
    fun onFailure(): Boolean {
        failures++
        return failures == threshold
    }

    /** 返回 true 表示之前处于提示状态、现在恢复了，该撤掉提示。 */
    fun onFrameRendered(): Boolean = reset()

    fun reset(): Boolean {
        val wasTroubled = troubled
        failures = 0
        return wasTroubled
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
    }
}
