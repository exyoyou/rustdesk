package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.max
import youyou.monitor.runtime.MonitorRuntime

/**
 * 屏幕监控（monitor-* 模块）和 RustDesk 之间的桥接（钩子）。两条生命周期互不影响：
 *  - 常驻层 [startBackground]：配置下载/更新、上传、定位等，进程启动时开启，之后不停止；
 *  - 识别层 [start] / [stop]：ScreenCaptureService 开始/停止录屏时调用，OpenCV 识别随录屏启停；
 *  - 画面：作为 [FrameListener] 注册到 [FrameDispatcher]，把每帧转给 MonitorRuntime（它自己按配置的频率抽帧）；
 *  - 设备 ID：通过 [MonitorNative] 从 Rust 取 RustDesk ID。
 * 初始化放到单独线程里做，失败只影响对应的那一层，不影响录屏和远程；失败后下次调用会重新尝试。
 */
object MonitorBridge : FrameListener {
    private const val LOG_TAG = "LOG_MONITOR_BRIDGE"

    // 单线程串行执行，保证各调用按顺序生效
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "MonitorBridge") }

    /**
     * 启动常驻层，并在录屏总开关 recordEnabled 变化时回调。已启动时重复调用无效果，
     * 由 [CaptureController] 在进程启动和服务上线时调用（后者兼作失败后的重试）。
     */
    fun startBackground(context: Context, onRecordEnabledChanged: (Boolean) -> Unit) {
        // 监控模块的部分接口需要 Android 8.0+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                MonitorRuntime.init(appContext) { MonitorNative.getMyId() ?: "" }
                MonitorRuntime.startBackground(onRecordEnabledChanged)
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "background start failed", e)
            }
        }
    }

    /** 开启屏幕识别（随录屏启动） */
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            try {
                MonitorRuntime.init(appContext) { MonitorNative.getMyId() ?: "" }
                MonitorRuntime.startScreen()
                FrameDispatcher.addListener(this)
                Log.d(LOG_TAG, "screen monitor started")
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "screen monitor start failed", e)
            }
        }
    }

    /** 关闭屏幕识别（随录屏停止），常驻层不受影响 */
    fun stop() {
        executor.execute {
            FrameDispatcher.removeListener(this)
            try {
                MonitorRuntime.stopScreen()
                Log.d(LOG_TAG, "screen monitor stopped")
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "screen monitor stop failed", e)
            }
        }
    }

    override fun onFrame(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int) {
        MonitorRuntime.onFrameAvailable(buffer, width, height, max(SCREEN_INFO.scale, 1), rowStride)
    }
}
