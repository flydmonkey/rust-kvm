package com.rustkvm.client.video

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class H264DecoderTest {
    @Test
    fun logsOnlyOnceWhenIdrArrivesWithoutParameterSets() {
        val surfaceTexture = SurfaceTexture(0)
        val surface = Surface(surfaceTexture)
        val decoder = H264Decoder(surface)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)

        try {
            decoder.queue(idr)
            decoder.queue(idr)

            val warnings = ShadowLog.getLogs().filter {
                it.type == Log.WARN &&
                    it.tag == "H264Decoder" &&
                    it.msg.contains("SPS/PPS")
            }
            assertEquals(1, warnings.size)
        } finally {
            decoder.close()
            surface.release()
            surfaceTexture.release()
        }
    }
}
