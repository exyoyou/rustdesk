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
 *
 * 通道 mChannelCapture 支持的方法（Dart 侧见 capture_model.dart）：
 *  - start_service / stop_service：只启停“服务”（设备上线），不影响录屏
 *  - start_capture / stop_capture：只启停“录屏”，不影响服务
 *  - get_state：返回 服务/录屏/摄像头权限 当前状态
 *  - 状态变化时通过 [notifyState] 主动通知 Dart（on_state）
 */
object CaptureChannel {
    private const val CHANNEL = "mChannelCapture"
    private var channel: MethodChannel? = null
    // Flutter 回调必须在主线程调用，其他线程通知状态时要切回主线程
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
                    CameraHook.prepare(activity)
                    result.success(true)
                }
                "stop_service" -> {
                    // 只停服务；ScreenCaptureService 不受影响，录屏仍可继续
                    activity.stopService(Intent(activity, MainService::class.java))
                    CameraHook.shutdown(activity)
                    notifyState("service", false)
                    result.success(true)
                }
                "start_capture" -> {
                    // 录屏需要用户授权 MediaProjection，所以拉起透明 Activity，授权后由它启动 ScreenCaptureService
                    if (!ScreenCaptureService.isStart) {
                        activity.startActivity(
                            Intent(activity, CapturePermissionActivity::class.java)
                        )
                    }
                    result.success(true)
                }
                "stop_capture" -> {
                    // 只停录屏服务；MainService 不受影响
                    activity.stopService(Intent(activity, ScreenCaptureService::class.java))
                    result.success(true)
                }
                "get_state" -> {
                    val serviceOn = isMainServiceRunning(activity)
                    // 服务在运行且有摄像头权限时，趁用户在前台把摄像头服务空转起来，后台才能使用摄像头
                    if (serviceOn) {
                        CameraHook.prepare(activity)
                    }
                    result.success(
                        mapOf(
                            "service" to serviceOn,
                            "capture" to ScreenCaptureService.isStart,
                            "camera" to CameraHook.hasCameraPermission(activity),
                            "camera_height" to CameraHook.maxHeight(activity)
                        )
                    )
                }
                "set_camera_height" -> {
                    // 设置摄像头“最高分辨率”（720/1080/2160），下次连接生效
                    val height = call.arguments as? Int
                    if (height != null && height > 0) {
                        CameraHook.setMaxHeight(activity, height)
                    }
                    result.success(true)
                }
                // 弹出摄像头/麦克风权限申请
                "request_camera_permission" -> {
                    activity.startActivity(Intent(activity, CameraPermissionActivity::class.java))
                    result.success(true)
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
    fun isMainServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == MainService::class.java.name
        }
    }
}
