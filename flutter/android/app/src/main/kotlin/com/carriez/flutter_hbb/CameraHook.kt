package com.carriez.flutter_hbb

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between Rust and the camera feature. [MainService] forwards unknown
 * `rustGetByName` / `rustSetByName` calls here, so upstream code stays untouched.
 */
object CameraHook {
    private const val LOG_TAG = "LOG_CAMERA_HOOK"
    private const val PREFS = "camera_hook"
    private const val KEY_MAX_HEIGHT = "max_height"
    private const val DEFAULT_MAX_HEIGHT = 1080

    /** Longest-side-by-shortest-side cap, e.g. 1080 -> 1920x1080. Applied the next time the camera list is read. */
    fun maxHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAX_HEIGHT, DEFAULT_MAX_HEIGHT)

    fun setMaxHeight(context: Context, height: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MAX_HEIGHT, height).apply()
    }

    class Choice(val id: String, val name: String, val srcWidth: Int, val srcHeight: Int, val rotation: Int) {
        val outWidth: Int get() = if (rotation % 180 == 0) srcWidth else srcHeight
        val outHeight: Int get() = if (rotation % 180 == 0) srcHeight else srcWidth
    }

    fun hasCameraPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** All usable cameras, each with a fixed output size, upright in portrait. The list index is the Rust display index. */
    fun list(context: Context): List<Choice> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = ArrayList<Choice>()
        val maxHeight = maxHeight(context)
        val maxPixels = maxHeight * (maxHeight * 16 / 9)
        try {
            for (id in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(id)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: continue
                val size = map.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.filter { it.width * it.height <= maxPixels }
                    ?.maxByOrNull { it.width * it.height }
                    ?: continue
                val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val name = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front camera"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back camera"
                    else -> "External camera"
                }
                val same = result.count { it.name.startsWith(name) }
                val unique = if (same == 0) name else "$name ${same + 1}"
                result.add(Choice(id, unique, size.width, size.height, rotation))
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "list cameras failed", e)
        }
        return result
    }

    fun choose(context: Context, index: Int): Choice? = list(context).getOrNull(index)

    /**
     * Starts the camera service idle (no camera opened) while the app is visible. Android only
     * grants camera/microphone access to a foreground service that was started from the foreground.
     */
    fun prepare(context: Context) {
        if (!hasCameraPermission(context) || CameraCaptureService.instance != null) {
            return
        }
        startService(context, null)
    }

    fun shutdown(context: Context) {
        context.stopService(Intent(context, CameraCaptureService::class.java))
    }

    private fun startService(context: Context, index: Int?) {
        val intent = Intent(context, CameraCaptureService::class.java)
        if (index != null) {
            intent.putExtra(CameraCaptureService.EXTRA_INDEX, index)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "start camera service failed", e)
        }
    }

    fun get(context: Context, name: String): String {
        return when (name) {
            "camera_info" -> {
                if (!hasCameraPermission(context)) {
                    return ""
                }
                val array = JSONArray()
                for (choice in list(context)) {
                    array.put(JSONObject().apply {
                        put("name", choice.name)
                        put("width", choice.outWidth)
                        put("height", choice.outHeight)
                    })
                }
                if (array.length() == 0) "" else array.toString()
            }
            else -> ""
        }
    }

    fun set(context: Context, name: String, arg1: String = "") {
        when (name) {
            "start_camera" -> {
                val index = arg1.toIntOrNull() ?: 0
                val running = CameraCaptureService.instance
                if (running != null) {
                    running.startStreaming(index)
                } else {
                    startService(context, index)
                }
            }
            "stop_camera" -> {
                val running = CameraCaptureService.instance
                if (running != null) {
                    running.stopStreaming()
                } else {
                    shutdown(context)
                }
            }
        }
    }
}
