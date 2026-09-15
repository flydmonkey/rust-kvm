package com.rustkvm.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.rustkvm.protocol.AudioFormatHeader
import java.io.Closeable

internal fun pcmBufferSize(
    sampleRate: Int,
    channels: Int,
    minimumBufferSize: Int,
): Int {
    require(sampleRate > 0) { "采样率必须大于 0" }
    require(channels == 1 || channels == 2) { "仅支持单声道或双声道 PCM" }
    require(minimumBufferSize > 0) { "AudioTrack 缓冲区不可用" }
    val threeChunks = sampleRate * channels * PCM_BYTES_PER_SAMPLE * JITTER_CHUNKS / 100
    return maxOf(minimumBufferSize, threeChunks)
}

class PcmPlayer : Closeable {
    private val lock = Any()
    private var track: AudioTrack? = null

    @Volatile
    var muted: Boolean = false

    fun onFormat(header: AudioFormatHeader) {
        require(header.format.equals("s16le", ignoreCase = true)) {
            "不支持的音频格式：${header.format}"
        }
        val channelMask = when (header.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw IllegalArgumentException("仅支持单声道或双声道 PCM")
        }
        val minimumBufferSize = AudioTrack.getMinBufferSize(
            header.sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = pcmBufferSize(
            sampleRate = header.sampleRate,
            channels = header.channels,
            minimumBufferSize = minimumBufferSize,
        )
        val next = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(header.sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
        check(next.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
        try {
            next.play()
        } catch (error: Exception) {
            next.release()
            throw error
        }
        synchronized(lock) {
            track?.release()
            track = next
        }
    }

    fun write(bytes: ByteArray) {
        if (muted || bytes.isEmpty()) return
        synchronized(lock) {
            if (muted) return
            track?.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
        }
    }

    override fun close() {
        synchronized(lock) {
            track?.release()
            track = null
        }
    }
}

private const val PCM_BYTES_PER_SAMPLE = 2
private const val JITTER_CHUNKS = 3
