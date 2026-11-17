package com.carriez.flutter_hbb

import android.app.Application
import android.util.Log
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)
        // [fork] begin: 启动常驻的监控层（配置同步、上传、定位）
        CaptureController.onAppStart(applicationContext)
        // [fork] end
    }
}
