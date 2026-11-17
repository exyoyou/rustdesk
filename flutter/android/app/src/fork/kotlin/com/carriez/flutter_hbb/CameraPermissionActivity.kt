package com.carriez.flutter_hbb

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * 申请摄像头 + 麦克风权限的透明 Activity（[CameraCaptureService] 需要）。
 * 结果通过 CaptureChannel 通知 Flutter 界面刷新权限状态。
 */
class CameraPermissionActivity : Activity() {
    private val requestCode = 4711

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 一次性申请两个权限；用户拒绝麦克风时仍可只推摄像头画面
        requestPermissions(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            requestCode
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 以摄像头权限为准通知界面
        CaptureChannel.notifyState("camera", CameraHook.hasCameraPermission(this))
        if (CaptureChannel.isMainServiceRunning(this)) {
            CameraHook.prepare(this)
        }
        finish()
    }
}
