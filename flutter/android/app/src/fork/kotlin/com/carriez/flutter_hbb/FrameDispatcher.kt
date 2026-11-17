package com.carriez.flutter_hbb

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Local consumer of captured screen frames (RGBA_8888).
 * The buffer is only valid during the callback and must not be retained.
 * `rowStride` is the number of bytes per row, which may exceed `width * 4`.
 */
interface FrameListener {
    fun onFrame(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int)
}

/**
 * 把录屏帧分发给本地的多个监听者（预留的扩展点，没有监听者时几乎零开销）。
 * 发给远程端的帧不走这里，ScreenCaptureService 直接调用 FFI.onVideoFrameUpdate。
 */
object FrameDispatcher {
    private const val LOG_TAG = "FrameDispatcher"
    private val listeners = CopyOnWriteArrayList<FrameListener>()

    fun addListener(listener: FrameListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: FrameListener) {
        listeners.remove(listener)
    }

    fun dispatch(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        // 没有监听者直接返回，避免每帧做无用功
        if (listeners.isEmpty()) {
            return
        }
        for (listener in listeners) {
            try {
                // 给只读副本，各监听者互不影响读指针，也不会改动原始缓冲区
                listener.onFrame(buffer.asReadOnlyBuffer(), width, height, rowStride)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "frame listener failed", e)
            }
        }
    }
}
