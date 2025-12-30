package com.carriez.flutter_hbb

import android.app.Application
import com.youyou.monitor.Log
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
        
        // 初始化 MonitorConfig（会自动启动定时任务）
        com.youyou.monitor.MonitorConfig.getInstance()
        
        FFI.onAppStart(applicationContext)
    }
}
