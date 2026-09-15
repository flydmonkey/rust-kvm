package com.rustkvm.client.video

data class AnnexBNalUnit(
    val type: Int,
    val startCodeOffset: Int,
    val startCodeLength: Int,
    val payloadOffset: Int,
    val endOffset: Int,
)

object AnnexBNalScanner {
    fun scan(bytes: ByteArray): List<AnnexBNalUnit> {
        val starts = mutableListOf<StartCode>()
        var offset = 0
        while (offset + 2 < bytes.size) {
            val length = when {
                offset + 3 < bytes.size &&
                    bytes[offset] == ZERO &&
                    bytes[offset + 1] == ZERO &&
                    bytes[offset + 2] == ZERO &&
                    bytes[offset + 3] == ONE -> 4

                bytes[offset] == ZERO &&
                    bytes[offset + 1] == ZERO &&
                    bytes[offset + 2] == ONE -> 3

                else -> 0
            }
            if (length == 0) {
                offset += 1
            } else {
                starts += StartCode(offset, length)
                offset += length
            }
        }

        return starts.mapIndexedNotNull { index, start ->
            val payloadOffset = start.offset + start.length
            val endOffset = starts.getOrNull(index + 1)?.offset ?: bytes.size
            if (payloadOffset >= endOffset) {
                null
            } else {
                AnnexBNalUnit(
                    type = bytes[payloadOffset].toInt() and NAL_TYPE_MASK,
                    startCodeOffset = start.offset,
                    startCodeLength = start.length,
                    payloadOffset = payloadOffset,
                    endOffset = endOffset,
                )
            }
        }
    }

    private data class StartCode(val offset: Int, val length: Int)

    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
    private const val NAL_TYPE_MASK = 0x1f
}
