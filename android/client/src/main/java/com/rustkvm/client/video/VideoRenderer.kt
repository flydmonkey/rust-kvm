package com.rustkvm.client.video

import android.graphics.Color
import android.graphics.Rect
import android.view.Surface
import com.rustkvm.client.net.KvmSession
import com.rustkvm.protocol.VideoText
import com.rustkvm.protocol.parseVideoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoPipeline(
    private val session: KvmSession,
    private val surface: Surface,
) {
    suspend fun run() = withContext(Dispatchers.Default) {
        coroutineScope {
            val codec = MutableStateFlow<String?>(null)
            launch {
                session.videoText.collect { text ->
                    when (val message = parseVideoText(text)) {
                        is VideoText.Codec -> codec.value = message.codec.lowercase()
                        is VideoText.Idle, VideoText.Unknown -> Unit
                    }
                }
            }
            session.videoBinary.collect { bytes ->
                if (codec.value == MJPEG_CODEC) {
                    render(bytes)
                }
            }
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
    }
}
