package com.carriez.flutter_hbb

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/** Asks for the camera and microphone permissions used by [CameraCaptureService]. */
class CameraPermissionActivity : Activity() {
    private val requestCode = 4711

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        CaptureChannel.notifyState("camera", CameraHook.hasCameraPermission(this))
        if (CaptureChannel.isMainServiceRunning(this)) {
            CameraHook.prepare(this)
        }
        finish()
    }
}
