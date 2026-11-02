package com.carriez.flutter_hbb

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter channel that controls [MainService] (device online) and
 * [ScreenCaptureService] (screen capture) independently of each other.
 */
object CaptureChannel {
    private const val CHANNEL = "mChannelCapture"
    private var channel: MethodChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun register(activity: Activity, messenger: BinaryMessenger) {
        val methodChannel = MethodChannel(messenger, CHANNEL)
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "start_service" -> {
                    val intent = Intent(activity, MainService::class.java).apply {
                        action = ACT_START_SERVICE_ONLY
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        activity.startForegroundService(intent)
                    } else {
                        activity.startService(intent)
                    }
                    notifyState("service", true)
                    result.success(true)
                }
                "stop_service" -> {
                    activity.stopService(Intent(activity, MainService::class.java))
                    notifyState("service", false)
                    result.success(true)
                }
                "start_capture" -> {
                    if (!ScreenCaptureService.isStart) {
                        activity.startActivity(
                            Intent(activity, CapturePermissionActivity::class.java)
                        )
                    }
                    result.success(true)
                }
                "stop_capture" -> {
                    activity.stopService(Intent(activity, ScreenCaptureService::class.java))
                    result.success(true)
                }
                "get_state" -> {
                    result.success(
                        mapOf(
                            "service" to isMainServiceRunning(activity),
                            "capture" to ScreenCaptureService.isStart
                        )
                    )
                }
                else -> result.notImplemented()
            }
        }
        channel = methodChannel
    }

    fun notifyState(name: String, value: Boolean) {
        mainHandler.post {
            channel?.invokeMethod("on_state", mapOf("name" to name, "value" to value))
        }
    }

    @Suppress("DEPRECATION")
    private fun isMainServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == MainService::class.java.name
        }
    }
}
