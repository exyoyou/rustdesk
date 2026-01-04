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
        
        // 初始化新的 MonitorService（传入 deviceId 获取函数）
        MonitorService.init(this) {
            FFI.getMyId()
        }
        Log.d(TAG, "MonitorService initialized")
        
        FFI.onAppStart(applicationContext)
    }
}
