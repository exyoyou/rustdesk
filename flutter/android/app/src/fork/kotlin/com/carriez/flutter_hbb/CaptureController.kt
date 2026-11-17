package com.carriez.flutter_hbb

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * 按需开关屏幕录制（ScreenCaptureService / MediaProjection）的控制器（钩子）。
 * 需要录屏的条件（满足任一就开，都不满足就关）：
 *  - 有远程连接在查看屏幕（[onViewerAdded] / [onViewerRemoved] 按连接计数）；
 *  - 远程配置里的总开关 recordEnabled 为 true（屏幕识别/自动截屏）。
 * 开启时如果还没有授权，会弹出授权界面；用户需要在手机上确认一次。
 * 触发时机：进程启动、服务上线/下线、连接增减、配置开关变化。
 * recordEnabled 来自常驻层，进程在就一直生效，不随服务下线清除。
 */
object CaptureController {
    private const val LOG_TAG = "LOG_CAPTURE_CTRL"

    // 授权界面弹出后用户来不及点时，短时间内不重复弹
    private const val REQUEST_COOLDOWN_MS = 30_000L

    private val lock = Any()
    private val viewers = HashSet<Int>()
    private val pending = HashSet<Int>()
    private var recordEnabled = false
    private var lastRequestAt = 0L
    @Volatile
    private var appContext: Context? = null

    /** 进程启动（MainApplication）时调用：启动常驻层（配置/上传/定位），之后一直运行 */
    fun onAppStart(context: Context) {
        appContext = context.applicationContext
        startBackground(context)
    }

    fun onServiceStart(context: Context) {
        appContext = context.applicationContext
        // [monitor-proxy] begin: 服务启动时启动应用内代理
        ProxyBridge.start(context)
        // [monitor-proxy] end
        // 进程启动时已经启动过；这里再调用一次，作为启动失败后的重试
        startBackground(context)
    }

    private fun startBackground(context: Context) {
        MonitorBridge.startBackground(context) { enabled ->
            synchronized(lock) { recordEnabled = enabled }
            Log.d(LOG_TAG, "recordEnabled=$enabled")
            evaluate()
        }
    }

    fun onServiceStop() {
        // [monitor-proxy] begin: 服务停止时停止应用内代理
        ProxyBridge.stop()
        // [monitor-proxy] end
        synchronized(lock) {
            viewers.clear()
            pending.clear()
        }
    }

    fun onViewerAdded(id: Int, authorized: Boolean) {
        synchronized(lock) {
            if (authorized) viewers.add(id) else pending.add(id)
        }
        if (authorized) {
            evaluate()
        }
    }

    /** 等待用户点击同意的连接被同意后，才算作查看者 */
    fun onViewerAuthorized(id: Int) {
        val moved = synchronized(lock) { pending.remove(id) && viewers.add(id) }
        if (moved) {
            evaluate()
        }
    }

    fun onViewerRemoved(id: Int) {
        val removed = synchronized(lock) {
            pending.remove(id)
            viewers.remove(id)
        }
        if (removed) {
            evaluate()
        }
    }

    private fun evaluate() {
        val context = appContext ?: return
        val wanted = synchronized(lock) { viewers.isNotEmpty() || recordEnabled }
        if (wanted && !ScreenCaptureService.isStart) {
            requestCapture(context)
        } else if (!wanted && ScreenCaptureService.isStart) {
            Log.d(LOG_TAG, "no viewer and recordEnabled=false, stop capture")
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    /** 授权界面（任何入口拉起的）打开时调用，避免控制器在用户还没点完时又弹一次 */
    fun markRequested() {
        synchronized(lock) { lastRequestAt = SystemClock.elapsedRealtime() }
    }

    private fun requestCapture(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (lastRequestAt != 0L && now - lastRequestAt < REQUEST_COOLDOWN_MS) {
                return
            }
            lastRequestAt = now
        }
        try {
            context.startActivity(Intent(context, CapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(LOG_TAG, "request capture failed", e)
        }
    }
}
