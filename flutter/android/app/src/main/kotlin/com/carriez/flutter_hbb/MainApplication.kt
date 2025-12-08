package com.carriez.flutter_hbb

import android.app.Application
import android.util.Log
import ffi.FFI

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"

        @JvmStatic
        lateinit var appContext: android.content.Context
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "App start")
        FFI.onAppStart(applicationContext)
        appContext = applicationContext
        com.youyou.monitor.MonitorConfig.getInstance()
    }
}
