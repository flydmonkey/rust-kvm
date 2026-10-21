package com.rustkvm.client.video

import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.rustkvm.client.net.KvmSession
import com.rustkvm.client.net.StreamMessage
import com.rustkvm.client.net.receiveBatch
import com.rustkvm.protocol.VideoText
import com.rustkvm.protocol.parseVideoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * [onVideoSize] 报的是实际解码出的画面尺寸，不是配置里请求的尺寸——采集卡按「最接近」
 * 协商，两者可能不同，而触摸坐标换算和画面比例都得以实际为准。
 */
class VideoPipeline(
    private val session: KvmSession,
    private val surface: Surface,
    private val onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    private val onCodec: (codec: String, fallbackReason: String?) -> Unit = { _, _ -> },
    /** 非空表示 H.264 反复解码失败、该建议用户改 MJPEG；恢复出画面后回调 null。 */
    private val onDecodeProblem: (message: String?) -> Unit = {},
) {
    private var reportedSize = 0 to 0
    private val decodeFailures = DecodeFailureTracker()

    private fun onH264FrameRendered() {
        if (decodeFailures.onFrameRendered()) onDecodeProblem(null)
    }

    private fun reportSize(width: Int, height: Int) {
        if (reportedSize == width to height) return
        reportedSize = width to height
        onVideoSize(width, height)
    }

    suspend fun run() = withContext(Dispatchers.Default) {
        var codec: String? = null
        var h264Decoder: H264Decoder? = null
        var lastReopenAt = 0L

        suspend fun reopenVideo(reason: String) {
            Log.w(TAG, "Reopening video stream: $reason")
            h264Decoder?.close()
            h264Decoder = null
            codec = null
            // 解码器若是每个 IDR 都失败（比如机型不支持该 profile），别以满速反复重连
            val sinceLast = SystemClock.elapsedRealtime() - lastReopenAt
            if (sinceLast < MIN_REOPEN_INTERVAL_MS) delay(MIN_REOPEN_INTERVAL_MS - sinceLast)
            lastReopenAt = SystemClock.elapsedRealtime()
            session.reopenVideo()
        }

        try {
            while (true) {
                val batch = session.videoMessages.receiveBatch() ?: return@withContext
                val frames = batch.count { it is StreamMessage.Binary }
                // H.264 积压了就不能靠丢帧追上（会花屏），只能整段丢掉、从新的 IDR 重来
                if (codec == H264_CODEC && frames > MAX_H264_BACKLOG) {
                    reopenVideo("H.264 backlog of $frames frames")
                    continue
                }
                // MJPEG 每帧独立：一批里只画最新的一帧，跟不上就丢旧帧，延迟不会越积越大
                var latestJpeg: ByteArray? = null
                batch@ for (message in batch) {
                    when (message) {
                        is StreamMessage.Text -> when (val parsed = parseVideoText(message.text)) {
                            // 每条编码头都重建解码器：服务端改分辨率时也只是重发头加 IDR，
                            // avcCodec 可能不变，沿用旧解码器在部分机型上换不了尺寸
                            is VideoText.Codec -> {
                                latestJpeg = null
                                h264Decoder?.close()
                                codec = parsed.codec.lowercase()
                                h264Decoder = if (codec == H264_CODEC && parsed.avcCodec != null) {
                                    H264Decoder(surface, ::reportSize, ::onH264FrameRendered)
                                } else {
                                    null
                                }
                                if (codec != H264_CODEC && decodeFailures.reset()) onDecodeProblem(null)
                                onCodec(codec ?: parsed.codec, parsed.fallbackReason)
                            }

                            is VideoText.Idle, VideoText.Unknown -> Unit
                        }

                        is StreamMessage.Binary -> when (codec) {
                            MJPEG_CODEC -> latestJpeg = message.data
                            H264_CODEC -> if (h264Decoder?.queue(message.data) == false) {
                                if (decodeFailures.onFailure()) {
                                    onDecodeProblem("本机 H.264 解码反复失败，可在设置里把编码改成 MJPEG")
                                }
                                reopenVideo("H.264 decoder failed")
                                break@batch
                            }
                        }
                    }
                }
                latestJpeg?.let(::render)
            }
        } finally {
            h264Decoder?.close()
        }
    }

    private fun render(bytes: ByteArray) {
        val bitmap = MjpegDecoder.decode(bytes) ?: return
        reportSize(bitmap.width, bitmap.height)
        try {
            if (!surface.isValid) return
            val canvas = try {
                surface.lockCanvas(null)
            } catch (error: Exception) {
                Log.w(TAG, "MJPEG Surface lockCanvas failed", error)
                return
            }
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    destinationRect(
                        sourceWidth = bitmap.width,
                        sourceHeight = bitmap.height,
                        targetWidth = canvas.width,
                        targetHeight = canvas.height,
                    ),
                    null,
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun destinationRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Rect {
        val scale = minOf(
            targetWidth.toFloat() / sourceWidth,
            targetHeight.toFloat() / sourceHeight,
        )
        val width = (sourceWidth * scale).toInt()
        val height = (sourceHeight * scale).toInt()
        val left = (targetWidth - width) / 2
        val top = (targetHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private companion object {
        const val TAG = "VideoPipeline"
        const val MJPEG_CODEC = "mjpeg"
        const val H264_CODEC = "h264"
        const val MAX_H264_BACKLOG = 15
        const val MIN_REOPEN_INTERVAL_MS = 1_000L
    }
}
