package com.rustkvm.host.uvc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UvcDescriptorsTest {
    @Test
    fun parsesMjpegFramesAndIsoAlternatesOfACaptureCard() {
        val uvc = parseUvcDescriptors(captureCard())!!
        assertEquals(0x0100, uvc.uvcVersion)
        val streaming = uvc.streaming.single()
        assertEquals(1, streaming.number)

        val mjpeg = streaming.mjpeg!!
        assertEquals("YUY2 那组不能混进来", 2, mjpeg.index)
        assertEquals(listOf(1920 to 1080, 1280 to 720), mjpeg.frames.map { it.width to it.height })
        assertEquals(listOf(333_333, 666_666), mjpeg.frames[0].intervals)
        assertEquals(4_147_200, mjpeg.frames[0].maxFrameBytes)
        assertEquals("连续区间只留两端", listOf(166_666, 333_333), mjpeg.frames[1].intervals)

        assertEquals(
            listOf(
                UvcAltSetting(1, 0x81, UvcTransfer.ISOCHRONOUS, 1024),
                UvcAltSetting(2, 0x81, UvcTransfer.ISOCHRONOUS, 3 * 1024),
            ),
            streaming.alternates,
        )
    }

    @Test
    fun nonVideoDevicesAreNotUvc() {
        val serial = device(
            desc(0x02, 0, 0, 1, 1, 0, 0x80, 50),
            desc(0x04, 0, 0, 2, 0xFF, 1, 2, 0),
            desc(0x05, 0x82, 2, *le16(32), 0),
        )
        assertNull(parseUvcDescriptors(serial))
    }

    @Test
    fun superSpeedCompanionWidensTheIsoPacket() {
        val raw = device(
            desc(0x02, 0, 0, 1, 1, 0, 0x80, 50),
            desc(0x04, 1, 1, 1, 0x0E, 2, 0, 0),
            desc(0x05, 0x81, 0x05, *le16(1024), 1),
            desc(0x30, 3, 1, *le16(8192)),
        )
        val alt = parseUvcDescriptors(raw)!!.streaming.single().alternates.single()
        assertEquals(1024 * 4 * 2, alt.packetBytes)
    }

    @Test
    fun picksTheClosestResolutionAndFrameInterval() {
        val uvc = parseUvcDescriptors(captureCard())!!
        selectMjpeg(uvc, 1920, 1080, 30)!!.let {
            assertEquals(1, it.frame.index)
            assertEquals(333_333, it.interval)
            assertEquals(2, it.format.index)
        }
        selectMjpeg(uvc, 1280, 720, 60)!!.let {
            assertEquals(2, it.frame.index)
            assertEquals(166_666, it.interval)
        }
        assertEquals(2, selectMjpeg(uvc, 1600, 900, 30)!!.frame.index)
        assertEquals(666_666, selectMjpeg(uvc, 1920, 1080, 15)!!.interval)
    }

    @Test
    fun picksTheSmallestIsoAlternateThatFitsThePayload() {
        val alts = parseUvcDescriptors(captureCard())!!.streaming.single().alternates
        assertEquals(1, pickAltSetting(alts, 1000)!!.alternate)
        assertEquals(2, pickAltSetting(alts, 2000)!!.alternate)
        assertEquals("都装不下就用最大的", 2, pickAltSetting(alts, 999_999)!!.alternate)
        val bulk = UvcAltSetting(0, 0x82, UvcTransfer.BULK, 512)
        assertEquals(bulk, pickAltSetting(alts + bulk, 2000))
    }

    @Test
    fun probeLayoutFollowsTheUvcVersion() {
        assertEquals(26, UvcProbe.lengthFor(0x0100))
        assertEquals(34, UvcProbe.lengthFor(0x0110))
        assertEquals(48, UvcProbe.lengthFor(0x0150))

        val probe = UvcProbe(formatIndex = 2, frameIndex = 1, frameInterval = 333_333, length = 34)
        val bytes = probe.encode()
        assertEquals(34, bytes.size)
        assertArrayEquals(byteArrayOf(1, 0, 2, 1, 0x15, 0x16, 0x05, 0), bytes.copyOf(8))

        bytes[18] = 0x00; bytes[19] = 0x40; bytes[20] = 0x3F; bytes[21] = 0x00
        bytes[22] = 0x00; bytes[23] = 0x0C; bytes[24] = 0x00; bytes[25] = 0x00
        val negotiated = UvcProbe.decode(bytes)
        assertEquals(0x3F4000, negotiated.maxVideoFrameSize)
        assertEquals(3072, negotiated.maxPayloadTransferSize)
        assertEquals(probe.copy(maxVideoFrameSize = 0x3F4000, maxPayloadTransferSize = 3072), negotiated)
    }

    /** MS2109 类采集卡的样子：UVC 1.0，YUY2 + MJPEG 两个格式，两个等时 alt，后面跟着音频接口。 */
    private fun captureCard(): ByteArray = device(
        desc(0x02, 0, 0, 4, 1, 0, 0x80, 250),
        desc(0x0B, 0, 2, 0x0E, 3, 0, 0),
        desc(0x04, 0, 0, 1, 0x0E, 1, 0, 0),
        desc(0x24, 1, *le16(0x0100), *le16(0x4D), *le32(48_000_000), 1, 1),
        desc(0x05, 0x83, 3, *le16(16), 8),
        desc(0x04, 1, 0, 0, 0x0E, 2, 0, 0),
        desc(0x24, 1, 2, *le16(0x200), 0x81, 0, 2, 0, 0, 0, 1, 0, 0),
        desc(0x24, 4, 1, 1, *IntArray(16), 16, 1, 0, 0, 0, 0),
        desc(0x24, 5, 1, 0, *le16(1920), *le16(1080), *le32(1), *le32(2), *le32(3), *le32(2_000_000), 1, *le32(2_000_000)),
        desc(0x24, 6, 2, 2, 0, 1, 0, 0, 0, 0),
        desc(0x24, 7, 1, 0, *le16(1920), *le16(1080), *le32(1), *le32(2), *le32(4_147_200), *le32(333_333), 2, *le32(333_333), *le32(666_666)),
        desc(0x24, 7, 2, 0, *le16(1280), *le16(720), *le32(1), *le32(2), *le32(1_843_200), *le32(333_333), 0, *le32(166_666), *le32(333_333), *le32(166_667)),
        desc(0x24, 0x0D, 1, 1, 4),
        desc(0x04, 1, 1, 1, 0x0E, 2, 0, 0),
        desc(0x05, 0x81, 0x05, *le16(0x0400), 1),
        desc(0x04, 1, 2, 1, 0x0E, 2, 0, 0),
        desc(0x05, 0x81, 0x05, *le16(0x1400), 1),
        desc(0x04, 2, 0, 0, 0x01, 1, 0, 0),
        desc(0x04, 3, 1, 1, 0x01, 2, 0, 0),
        desc(0x05, 0x84, 0x05, *le16(200), 4),
    )
}

internal fun device(vararg descriptors: ByteArray): ByteArray =
    desc(0x01, 0x00, 0x02, 0xEF, 2, 1, 64, *le16(0x534D), *le16(0x2109), 0, 1, 1, 2, 0, 1) +
        descriptors.fold(ByteArray(0)) { acc, d -> acc + d }

internal fun desc(type: Int, vararg body: Int): ByteArray =
    byteArrayOf((body.size + 2).toByte(), type.toByte()) + ByteArray(body.size) { body[it].toByte() }

internal fun le16(value: Int) = intArrayOf(value and 0xFF, (value shr 8) and 0xFF)

internal fun le32(value: Int) = le16(value) + le16(value shr 16)
