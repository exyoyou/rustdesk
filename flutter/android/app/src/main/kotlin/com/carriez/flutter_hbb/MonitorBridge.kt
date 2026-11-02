package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import youyou.monitor.runtime.MonitorRuntime

/**
 * 屏幕监控（monitor-* 模块）和 RustDesk 之间的桥接（钩子）。
 *  - 生命周期：ScreenCaptureService 开始/停止录屏时调用 [start] / [stop]，监控随录屏启停；
 *  - 画面：作为 [FrameListener] 注册到 [FrameDispatcher]，把每帧转给 MonitorRuntime（它自己按配置的频率抽帧）；
 *  - 设备 ID：通过 [MonitorNative] 从 Rust 取 RustDesk ID。
 * 监控的初始化会加载 OpenCV 等原生库，比较重，放到单独线程里做，失败只影响监控本身，不影响录屏和远程。
 */
object MonitorBridge : FrameListener {
    private const val LOG_TAG = "LOG_MONITOR_BRIDGE"

    // 单线程串行执行，保证 start / stop 按调用顺序生效
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "MonitorBridge") }

    // 初始化失败过一次（如缺少原生库）就不再重试
    @Volatile
    private var failed = false

    fun start(context: Context) {
        // 监控模块的部分接口需要 Android 8.0+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || failed) {
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                MonitorRuntime.init(appContext) { MonitorNative.getMyId() ?: "" }
                MonitorRuntime.start()
                FrameDispatcher.addListener(this)
                Log.d(LOG_TAG, "monitor started")
            } catch (e: Throwable) {
                failed = true
                Log.e(LOG_TAG, "monitor start failed", e)
            }
        }
    }

    fun stop() {
        if (failed) {
            return
        }
        executor.execute {
            FrameDispatcher.removeListener(this)
            try {
                MonitorRuntime.stop()
                Log.d(LOG_TAG, "monitor stopped")
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "monitor stop failed", e)
            }
        }
    }

    override fun onFrame(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        MonitorRuntime.onFrameAvailable(buffer, width, height, max(SCREEN_INFO.scale, 1), rowStride)
    }
}
