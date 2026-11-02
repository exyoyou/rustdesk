package com.carriez.flutter_hbb

import androidx.annotation.Keep
import java.nio.ByteBuffer

/** JNI entry used by Rust (`libs/scrap/src/android/camera.rs`) to receive camera frames. */
@Keep
object CameraNative {
    init {
        System.loadLibrary("rustdesk")
    }

    /** Direct YUV_420_888 plane buffers; Rust converts them to RGBA and rotates clockwise by [rotation] degrees. */
    @JvmStatic
    external fun onYuv(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yStride: Int, uStride: Int, vStride: Int, uvPixelStride: Int,
        width: Int, height: Int, rotation: Int
    )
}
