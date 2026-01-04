package com.carriez.flutter_hbb

import android.app.Application
import com.youyou.monitor.infra.logger.Log
import com.youyou.monitor.MonitorService
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"

        @JvmStatic
        lateinit var appContext: android.content.Context
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
    }
}
