package com.carriez.flutter_hbb

import android.app.Application
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.MonitorService
import ffi.FFI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"

        @JvmStatic
        lateinit var appContext: android.content.Context
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // 初始化日志系统（保存到内部存储）
        Log.init(this)
        Log.d(TAG, "App start")

        FFI.onAppStart(applicationContext)

        // 初始化 MonitorService（传入 deviceIdProvider 回调）
        Log.d(TAG, "Before MonitorService.init()")
        MonitorService.init(this) {
            try {
                val id = FFI.getMyId()
                Log.w(TAG, "[TRACE] FFI.getMyId() returned: '$id' (length=${id.length})")
                id
            } catch (e: Exception) {
                Log.e(TAG, "[TRACE] FFI.getMyId() threw exception: ${e.message}", e)
                ""
            }
        }
        Log.d(TAG, "After MonitorService.init()")
        if (BuildConfig.DEBUG) {
            startFdMonitor()
        }
    }

    private fun startFdMonitor() {
        applicationScope.launch {
            while (isActive) {
                try {
                    val fdFile = File("/proc/self/fd")
                    val fds = fdFile.listFiles()
                    val fdCount = fds?.size ?: 0

                    Log.d(
                        "DEBUG_FD",
                        "Current PID: ${android.os.Process.myPid()} | Global FD count: $fdCount"
                    )

                    // 如果 FD 数量异常，打印前 5 个 FD 具体是什么情况
                    if (fdCount > 800) {
                        Log.e("DEBUG_FD", "WARNING: High FD usage!")
                        fds?.take(5)?.forEach {
                            try {
                                val path = android.system.Os.readlink(it.absolutePath)
                                Log.e("DEBUG_FD", "FD ${it.name} -> $path")
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DEBUG_FD", "Monitor error: ${e.message}")
                }

                // 每隔 1000 毫秒执行一次
                delay(1000)
            }
        }
    }
}
