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
 * Rust 与摄像头功能之间的桥接（钩子）。
 * [MainService] 的 rustGetByName / rustSetByName 遇到不认识的名字时会转发到这里，
 * 所以摄像头相关逻辑全部集中在本文件和 CameraCaptureService，上游代码几乎不用改。
 *
 * 支持的请求：
 *  - get  "camera_info"  → 摄像头列表 JSON（没有摄像头权限时返回空字符串）
 *  - set  "start_camera" → 按下标打开/切换摄像头（同时打开麦克风）
 *  - set  "stop_camera"  → 关闭摄像头和麦克风
 */
object CameraHook {
    private const val LOG_TAG = "LOG_CAMERA_HOOK"
    private const val PREFS = "camera_hook"
    private const val KEY_MAX_HEIGHT = "max_height"
    // 默认最高 1080p（设置里可选 720p / 1080p / 4K）
    private const val DEFAULT_MAX_HEIGHT = 1080

    /** 摄像头最高分辨率的“高”，如 1080 表示最大 1920x1080（按 16:9 折算成像素上限）。下次读取摄像头列表时生效。 */
    fun maxHeight(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAX_HEIGHT, DEFAULT_MAX_HEIGHT)

    fun setMaxHeight(context: Context, height: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_MAX_HEIGHT, height).apply()
    }

    /**
     * 一个可用摄像头的选择结果。src* 是传感器原始输出尺寸，out* 是按传感器方向旋转后
     * 的正向尺寸（旋转 90/270 度时宽高互换），发给控制端的就是 out* 。
     */
    class Choice(val id: String, val name: String, val srcWidth: Int, val srcHeight: Int, val rotation: Int) {
        val outWidth: Int get() = if (rotation % 180 == 0) srcWidth else srcHeight
        val outHeight: Int get() = if (rotation % 180 == 0) srcHeight else srcWidth
    }

    /** 系统是否已授予摄像头权限 */
    fun hasCameraPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** 系统是否已授予麦克风权限（没有时只推画面，不推声音） */
    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 列出所有可用摄像头（带缓存）。摄像头特性是固定的，而 Rust 每次连接/切换都会查询，
     * 每次都走 Camera2 binder 很慢，所以按“最高分辨率设置”缓存结果；设置变化时自动重新查询。
     */
    private var cachedHeight = -1
    private var cachedList: List<Choice> = emptyList()

    @Synchronized
    fun list(context: Context): List<Choice> {
        val height = maxHeight(context)
        if (height == cachedHeight && cachedList.isNotEmpty()) {
            return cachedList
        }
        val result = queryList(context, height)
        // 查询为空（如外接摄像头暂时不在）不缓存，下次再查
        if (result.isNotEmpty()) {
            cachedHeight = height
            cachedList = result
        }
        return result
    }

    /**
     * 实际查询：每个摄像头选一个不超过上限的最大 YUV 输出尺寸。
     * 列表下标就是 Rust 侧的“显示器下标”，控制端切换摄像头时传的就是它。
     */
    private fun queryList(context: Context, maxHeight: Int): List<Choice> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = ArrayList<Choice>()
        // 像素上限：高×(高×16/9)
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
                // 传感器方向（0/90/180/270），用于把画面转正
                val rotation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                // 给摄像头起名字，控制端会展示；同类多个时加序号区分
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

    /** 按下标取一个摄像头，不存在返回 null */
    fun choose(context: Context, index: Int): Choice? = list(context).getOrNull(index)

    /**
     * App 在前台时先把摄像头服务空转起来（不打开摄像头）。
     * Android 12+ 只允许“从前台启动的前台服务”在后台访问摄像头/麦克风，
     * 所以必须趁用户还在界面时把服务起好，之后按需打开摄像头才能在后台工作。
     */
    fun prepare(context: Context) {
        if (!hasCameraPermission(context) || CameraCaptureService.instance != null) {
            return
        }
        startService(context, null)
    }

    /** 彻底停掉摄像头服务 */
    fun shutdown(context: Context) {
        context.stopService(Intent(context, CameraCaptureService::class.java))
    }

    /** 启动摄像头前台服务；index 非空时一起告诉服务立即打开该下标的摄像头 */
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

    /** 处理 Rust 的 get 请求 */
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

    /** 处理 Rust 的 set 请求：服务已在运行则直接让它开始/停止，否则先把服务起起来 */
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
