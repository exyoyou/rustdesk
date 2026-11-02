package com.carriez.flutter_hbb

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/** Requests the MediaProjection permission and starts [ScreenCaptureService] only. */
class CapturePermissionActivity : Activity() {
    private val logTag = "CapturePermission"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 【摄像头钩子】开机自启时只有这个 Activity 会被拉到前台。Android 12+/15+ 不允许从后台或
        // BOOT_COMPLETED 直接启动摄像头/麦克风类型的前台服务，所以趁 Activity 在前台时把摄像头服务空转起来，
        // 之后控制端查看摄像头才能在后台打开摄像头。
        CameraHook.prepare(this)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQ_REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(logTag, "Launch ScreenCaptureService")
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ACT_START_SCREEN_CAPTURE
                    putExtra(EXT_CAPTURE_RESULT_INTENT, data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                CaptureChannel.notifyState("capture", false)
            }
        }
        finish()
    }
}
