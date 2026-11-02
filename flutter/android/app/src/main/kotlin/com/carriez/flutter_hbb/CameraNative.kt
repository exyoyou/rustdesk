package com.carriez.flutter_hbb

import androidx.annotation.Keep
import java.nio.ByteBuffer

/**
 * JNI 入口：Rust（libs/scrap/src/android/camera.rs）实现 onYuv，Kotlin 每帧调用把摄像头画面传过去。
 * 独立于屏幕录制的 FFI.kt，避免修改上游文件。
 */
@Keep
object CameraNative {
    init {
        System.loadLibrary("rustdesk")
    }

    /**
     * y/u/v 是 YUV_420_888 三个平面的 direct buffer，stride 为行跨度，uvPixelStride 为色度平面的像素跨度（区分 I420/NV12/NV21）。
     * Rust 把它们转成 RGBA 并顺时针旋转 [rotation] 度。
     */
    @JvmStatic
    external fun onYuv(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yStride: Int, uStride: Int, vStride: Int, uvPixelStride: Int,
        width: Int, height: Int, rotation: Int
    )
}
