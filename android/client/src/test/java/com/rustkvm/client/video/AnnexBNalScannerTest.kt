package com.rustkvm.client.video

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnexBNalScannerTest {
    @Test
    fun scansThreeAndFourByteStartCodes() {
        val bytes = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x4d, 0x40, 0x28,
            0, 0, 1, 0x68, 0x11,
        )

        val units = AnnexBNalScanner.scan(bytes)

        assertEquals(listOf(7, 8), units.map { it.type })
        assertEquals(listOf(0, 8), units.map { it.startCodeOffset })
        assertEquals(listOf(4, 3), units.map { it.startCodeLength })
        assertEquals(listOf(4, 11), units.map { it.payloadOffset })
        assertEquals(listOf(8, 13), units.map { it.endOffset })
    }

    @Test
    fun ignoresBytesBeforeFirstStartCode() {
        val bytes = byteArrayOf(0x55, 0x66, 0, 0, 1, 0x65, 0x22)

        val units = AnnexBNalScanner.scan(bytes)

        assertEquals(1, units.size)
        assertEquals(5, units.single().type)
        assertEquals(2, units.single().startCodeOffset)
        assertEquals(7, units.single().endOffset)
    }

    @Test
    fun doesNotCreateUnitForTrailingStartCode() {
        val bytes = byteArrayOf(0, 0, 1, 0x41, 0x22, 0, 0, 0, 1)

        val units = AnnexBNalScanner.scan(bytes)

        assertEquals(1, units.size)
        assertEquals(1, units.single().type)
        assertEquals(5, units.single().endOffset)
    }

    @Test
    fun handlesAdjacentStartCodesAndForbiddenBits() {
        val bytes = byteArrayOf(
            0, 0, 1,
            0, 0, 0, 1, 0xe5.toByte(), 0x33,
        )

        val units = AnnexBNalScanner.scan(bytes)

        assertEquals(1, units.size)
        assertEquals(5, units.single().type)
        assertEquals(3, units.single().startCodeOffset)
    }

    @Test
    fun returnsEmptyForDataWithoutCompleteNal() {
        assertEquals(emptyList<AnnexBNalUnit>(), AnnexBNalScanner.scan(byteArrayOf()))
        assertEquals(emptyList<AnnexBNalUnit>(), AnnexBNalScanner.scan(byteArrayOf(0, 0, 1)))
        assertEquals(
            emptyList<AnnexBNalUnit>(),
            AnnexBNalScanner.scan(byteArrayOf(1, 2, 3, 4)),
        )
    }
}
