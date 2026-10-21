package com.rustkvm.host.uvc

/**
 * 把 UVC 负载拼成整帧 JPEG。每个负载以一个头开头：`bHeaderLength`，然后 `bmHeaderInfo`
 * （bit0 FID 每帧翻转一次，bit1 EOF 本帧结束，bit6 ERR 本负载出错）。
 *
 * 帧结束有两种信号，都得认：EOF 位，或者 FID 变了。不少采集卡从不置 EOF，只翻 FID。
 * 出过错的帧整帧丢掉：残缺的 JPEG 在浏览器那边会画成半张灰图，比少一帧难看得多。
 */
class UvcFrameAssembler(
    private val maxFrameBytes: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    private var buffer = ByteArray(minOf(maxFrameBytes, INITIAL_CAPACITY))
    private var size = 0
    private var lastFid = -1
    private var corrupt = false

    var droppedFrames = 0L
        private set

    fun onPayload(data: ByteArray, offset: Int, length: Int) {
        if (length < 2) return
        val headerLength = data[offset].toInt() and 0xFF
        if (headerLength < 2 || headerLength > length) return
        val info = data[offset + 1].toInt() and 0xFF
        val fid = info and FID

        if (lastFid >= 0 && fid != lastFid && (size > 0 || corrupt)) finishFrame()
        lastFid = fid
        if (info and ERR != 0) corrupt = true

        append(data, offset + headerLength, length - headerLength)
        if (info and EOF != 0) finishFrame()
    }

    private fun append(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || corrupt) return
        if (size + length > maxFrameBytes) {
            corrupt = true
            return
        }
        if (size + length > buffer.size) {
            buffer = buffer.copyOf(minOf(maxFrameBytes, maxOf(buffer.size * 2, size + length)))
        }
        System.arraycopy(data, offset, buffer, size, length)
        size += length
    }

    private fun finishFrame() {
        val good = !corrupt && size >= 4 &&
            buffer[0] == 0xFF.toByte() && buffer[1] == 0xD8.toByte()
        if (good) {
            onFrame(buffer.copyOf(size))
        } else if (size > 0 || corrupt) {
            droppedFrames++
        }
        size = 0
        corrupt = false
    }

    private companion object {
        const val FID = 0x01
        const val EOF = 0x02
        const val ERR = 0x40
        const val INITIAL_CAPACITY = 512 * 1024
    }
}
