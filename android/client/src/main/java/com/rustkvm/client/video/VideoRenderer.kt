package com.rustkvm.client.video

import android.graphics.Color
import android.graphics.Rect
import android.view.Surface
import com.rustkvm.client.net.KvmSession
import com.rustkvm.protocol.VideoText
import com.rustkvm.protocol.parseVideoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VideoPipeline(
    private val session: KvmSession,
    private val surface: Surface,
) {
    suspend fun run() = withContext(Dispatchers.Default) {
        val stateLock = Mutex()
        var selection: CodecSelection? = null
        var h264Decoder: H264Decoder? = null
        try {
            coroutineScope {
                launch {
                    session.videoText.collect { text ->
                        when (val message = parseVideoText(text)) {
                            is VideoText.Codec -> {
                                val next = CodecSelection(
                                    codec = message.codec.lowercase(),
                                    avcCodec = message.avcCodec,
                                )
                                var previousDecoder: H264Decoder? = null
                                stateLock.withLock {
                                    if (next != selection) {
                                        previousDecoder = h264Decoder
                                        h264Decoder = if (
                                            next.codec == H264_CODEC &&
                                            next.avcCodec != null
                                        ) {
                                            H264Decoder(surface)
                                        } else {
                                            null
                                        }
                                        selection = next
                                    }
                                }
                                previousDecoder?.close()
                            }

                            is VideoText.Idle, VideoText.Unknown -> Unit
                        }
                    }
                }
                session.videoBinary.collect { bytes ->
                    val (codec, decoder) = stateLock.withLock {
                        selection?.codec to h264Decoder
                    }
                    when (codec) {
                        MJPEG_CODEC -> render(bytes)
                        H264_CODEC -> decoder?.queue(bytes)
                    }
                }
            }
        } finally {
            val decoder = stateLock.withLock {
                h264Decoder.also { h264Decoder = null }
            }
            decoder?.close()
        }
    }

    private fun render(bytes: ByteArray) {
        val bitmap = MjpegDecoder.decode(bytes) ?: return
        try {
            if (!surface.isValid) return
            val canvas = runCatching { surface.lockCanvas(null) }.getOrNull() ?: return
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
        const val MJPEG_CODEC = "mjpeg"
        const val H264_CODEC = "h264"
    }

    private data class CodecSelection(
        val codec: String,
        val avcCodec: String?,
    )
}
