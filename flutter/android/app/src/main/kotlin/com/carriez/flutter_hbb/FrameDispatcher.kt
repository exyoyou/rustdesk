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
        if (listeners.isEmpty()) {
            return
        }
        for (listener in listeners) {
            try {
                listener.onFrame(buffer.asReadOnlyBuffer(), width, height, rowStride)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "frame listener failed", e)
            }
        }
    }
}
