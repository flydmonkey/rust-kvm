package com.rustkvm.client.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory

object MjpegDecoder {
    fun decode(bytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
