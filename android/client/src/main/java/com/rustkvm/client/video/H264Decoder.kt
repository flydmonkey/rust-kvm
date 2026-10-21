package com.rustkvm.client.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer

class H264Decoder(
    private val surface: Surface,
    private val onOutputSize: (width: Int, height: Int) -> Unit = { _, _ -> },
) : Closeable {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var closed = false
    private var missingParameterSetsWarned = false

    /**
     * 返回 false 表示解码器已出错并重置，后续帧在下一个 IDR 之前都画不出来；
     * 调用方应当重连画面让服务端立刻补一个 IDR，而不是干等 10 秒的 GOP。
     */
    @Synchronized
    fun queue(accessUnit: ByteArray): Boolean {
        if (closed) return true
        val nals = AnnexBNalScanner.scan(accessUnit)
        rememberParameterSets(accessUnit, nals)

        return try {
            queueToCodec(accessUnit, nals)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Dropping H.264 access unit after MediaCodec failure", error)
            resetCodec()
            false
        }
    }

    private fun queueToCodec(
        accessUnit: ByteArray,
        nals: List<AnnexBNalUnit>,
    ) {
        var currentCodec = codec
        if (currentCodec == null) {
            if (nals.none { it.type == NAL_IDR }) return
            val currentSps = sps
            val currentPps = pps
            if (currentSps == null || currentPps == null) {
                if (!missingParameterSetsWarned) {
                    missingParameterSetsWarned = true
                    Log.w(TAG, "Ignoring IDR until both SPS/PPS have arrived")
                }
                return
            }
            currentCodec = createCodec(currentSps, currentPps)
            codec = currentCodec
        }

        // H.264 不能丢帧：丢一个 P 帧，后面直到下一个 IDR 全是花屏。所以拿不到输入
        // 缓冲就边取输出边等，等太久才当作解码器卡死，交给上层重连。
        val deadline = System.nanoTime() + INPUT_WAIT_LIMIT_NS
        var inputIndex: Int
        while (true) {
            drainOutput(currentCodec)
            inputIndex = currentCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex >= 0) break
            check(System.nanoTime() < deadline) { "MediaCodec input buffer unavailable" }
        }
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

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        resetCodec()
    }

    private fun resetCodec() {
        val currentCodec = codec ?: return
        codec = null
        runCatching { currentCodec.stop() }
        runCatching { currentCodec.release() }
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
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> reportOutputSize(decoder.outputFormat)
                else -> {
                    if (outputIndex < 0) return
                    decoder.releaseOutputBuffer(outputIndex, true)
                }
            }
        }
    }

    /** 1080p 编码时宽高按宏块对齐成 1088，真实画面要看 crop 矩形。 */
    private fun reportOutputSize(format: MediaFormat) {
        val width = if (format.containsKey(KEY_CROP_RIGHT) && format.containsKey(KEY_CROP_LEFT)) {
            format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1
        } else {
            format.getInteger(MediaFormat.KEY_WIDTH)
        }
        val height = if (format.containsKey(KEY_CROP_BOTTOM) && format.containsKey(KEY_CROP_TOP)) {
            format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1
        } else {
            format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        if (width > 0 && height > 0) onOutputSize(width, height)
    }

    private companion object {
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"
        const val MIME_AVC = "video/avc"
        const val NAL_IDR = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_WAIT_LIMIT_NS = 1_000_000_000L
        const val NO_WAIT = 0L
        const val NANOS_PER_MICROSECOND = 1_000L
        const val TAG = "H264Decoder"
    }
}
