package com.rustkvm.client.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer

class H264Decoder(
    private val surface: Surface,
    val avcCodec: String,
) : Closeable {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun queue(accessUnit: ByteArray) {
        val nals = AnnexBNalScanner.scan(accessUnit)
        rememberParameterSets(accessUnit, nals)

        var currentCodec = codec
        if (currentCodec == null) {
            if (nals.none { it.type == NAL_IDR }) return
            currentCodec = createCodec(sps ?: return, pps ?: return)
            codec = currentCodec
        }

        drainOutput(currentCodec)
        val inputIndex = currentCodec.dequeueInputBuffer(WAIT_FOREVER)
        val input = requireNotNull(currentCodec.getInputBuffer(inputIndex)) {
            "MediaCodec returned no input buffer"
        }
        input.clear()
        check(input.remaining() >= accessUnit.size) {
            "H.264 access unit is larger than the MediaCodec input buffer"
        }
        input.put(accessUnit)
        val flags = if (nals.any { it.type == NAL_IDR }) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }
        currentCodec.queueInputBuffer(
            inputIndex,
            0,
            accessUnit.size,
            System.nanoTime() / NANOS_PER_MICROSECOND,
            flags,
        )
        drainOutput(currentCodec)
    }

    override fun close() {
        val currentCodec = codec ?: return
        codec = null
        runCatching { currentCodec.stop() }
        currentCodec.release()
    }

    private fun rememberParameterSets(
        accessUnit: ByteArray,
        nals: List<AnnexBNalUnit>,
    ) {
        nals.forEach { nal ->
            when (nal.type) {
                NAL_SPS -> sps = accessUnit.copyOfRange(nal.startCodeOffset, nal.endOffset)
                NAL_PPS -> pps = accessUnit.copyOfRange(nal.startCodeOffset, nal.endOffset)
            }
        }
    }

    private fun createCodec(sps: ByteArray, pps: ByteArray): MediaCodec {
        val format = MediaFormat.createVideoFormat(MIME_AVC, DEFAULT_WIDTH, DEFAULT_HEIGHT).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }
        return MediaCodec.createDecoderByType(MIME_AVC).also { decoder ->
            try {
                decoder.configure(format, surface, null, 0)
                decoder.start()
            } catch (error: Throwable) {
                decoder.release()
                throw error
            }
        }
    }

    private fun drainOutput(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, NO_WAIT)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> {
                    if (outputIndex < 0) return
                    decoder.releaseOutputBuffer(outputIndex, true)
                }
            }
        }
    }

    private companion object {
        const val MIME_AVC = "video/avc"
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val WAIT_FOREVER = -1L
        const val NO_WAIT = 0L
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}
