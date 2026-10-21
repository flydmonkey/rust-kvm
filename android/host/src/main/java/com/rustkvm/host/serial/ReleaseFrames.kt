package com.rustkvm.host.serial

/**
 * 断线松键的兜底，与 `src/serial_bridge.rs` 的 `release_all` 一致。
 *
 * 正常路径是客户端自己先发全释放，但网络断了、页面崩了就没人发，被控机上卡住一个
 * Ctrl 恰恰是远程最修不了的故障。这是宿主机唯一需要知道的一点 CH9329 协议。
 */
object ReleaseFrames {
    private val KEYBOARD_RELEASE = ByteArray(8)

    // 用相对而不是绝对：绝对包必须带坐标，填 0 会把光标甩到屏幕左上角
    private val MOUSE_RELEASE = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00)

    val ALL: List<ByteArray> = listOf(frame(0x02, KEYBOARD_RELEASE), frame(0x05, MOUSE_RELEASE))

    private fun frame(command: Int, data: ByteArray): ByteArray {
        val head = byteArrayOf(0x57, 0xAB.toByte(), 0x00, command.toByte(), data.size.toByte())
        val body = head + data
        val checksum = body.fold(0) { sum, byte -> sum + (byte.toInt() and 0xFF) } and 0xFF
        return body + checksum.toByte()
    }
}
