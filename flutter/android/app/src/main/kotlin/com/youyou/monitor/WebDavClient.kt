package com.youyou.monitor

import com.youyou.monitor.Log
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import ffi.FFI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端（基于 Sardine 库）
 * 优势：成熟稳定、原生支持所有 WebDAV 方法、无需反射、基于 OkHttp
 */
class WebDavClient(
    val webdavUrl: String,
    val username: String,
    val password: String,
    val remoteUploadDir: String,
    val defaultTimeout: Int = DEFAULT_TIMEOUT_MS
) {

    companion object {
        const val TAG = "WebDavClient"
        const val DEFAULT_TIMEOUT_MS = 120000 // 2分钟
        const val DEFAULT_MAX_RETRY = 3
        const val DEFAULT_RETRY_DELAY_MS = 2000L
        const val BUFFER_SIZE = 8192
        const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024 // 10MB
    }

    // 存储 deviceId 的私有字段
    private var _deviceId: String = ""

    /**
     * 设备 ID 属性，带自动获取功能
     * 如果未设置或为空，则自动调用 FFI.getMyId() 获取
     */
    var deviceId: String
        get() {
            return if (_deviceId.isBlank()) {
                try {
                    val id = FFI.getMyId()
                    if (id.isNotBlank()) {
                        _deviceId = id
                        Log.d(TAG, "Auto-fetched deviceId from FFI: $id")
                    }
                    id
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get deviceId from FFI: ${e.message}")
                    ""
                }
            } else {
                _deviceId
            }
        }
        set(value) {
            _deviceId = value
            if (value.isNotBlank()) {
                Log.d(TAG, "DeviceId set to: $value")
            }
        }

    private val sardine: OkHttpSardine by lazy {
        // 预认证拦截器（所有请求第一次就带密码，避免 401 重传）
        val authInterceptor = okhttp3.Interceptor { chain ->
            val original = chain.request()
            val credentials = okhttp3.Credentials.basic(username, password)
            val authenticated = original.newBuilder()
                .header("Authorization", credentials)
                .build()
            chain.proceed(authenticated)
        }
        
        val okHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .addInterceptor(authInterceptor)
            .build()
            
        OkHttpSardine(okHttpClient)
    }

    /**
     * 测试连接是否可用
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing connection to: $webdavUrl")
            // 尝试列出根目录来测试连接（比 exists 更可靠）
            sardine.list(webdavUrl.trimEnd('/'))
            Log.d(TAG, "Connection test successful")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.javaClass.simpleName}: ${e.message}")
            return@withContext false
        }
    }

    /**
     * 流式上传文件到 WebDAV（支持大文件，如 1GB 视频）
     */
    suspend fun uploadFile(
        remotePath: String,
        fileName: String,
        file: File,
        overwrite: Boolean = true,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        delayMillis: Long = DEFAULT_RETRY_DELAY_MS
    ): Boolean = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastException: Exception? = null
        
        // 大文件不重试，避免浪费流量和时间，等待下次定时任务重新上传
        val isLargeFile = file.length() > LARGE_FILE_THRESHOLD
        val actualMaxRetry = if (isLargeFile) 1 else maxRetry
        
        if (isLargeFile) {
            Log.d(TAG, "Large file detected (${file.length() / 1024 / 1024}MB), will not retry on failure")
        }
        
        // 计算完整路径
        val baseDir = "/$remoteUploadDir".replace("//", "/")
        val baseDirWithDeviceId = if (deviceId.isNotBlank()) {
            baseDir.trimEnd('/') + "/" + deviceId
        } else {
            baseDir
        }
        val fullRemotePath = if (remotePath.isNullOrBlank()) {
            baseDirWithDeviceId
        } else {
            baseDirWithDeviceId.trimEnd('/') + "/" + remotePath.trim('/')
        }
        
        val fullUrl = webdavUrl.trimEnd('/') + fullRemotePath.trimEnd('/') + "/" + fileName
        val sizeMB = file.length() / 1024.0 / 1024.0
        Log.d(TAG, "uploadFile: $fullUrl, size: %.2fMB".format(sizeMB))
        
        while (attempt < actualMaxRetry) {
            try {
                val startTime = System.currentTimeMillis()
                // 使用 Sardine 的 File 上传方法，内部会创建 RequestBody 进行流式上传
                sardine.put(fullUrl, file, "application/octet-stream")
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                val speedMBps = sizeMB / elapsedSeconds
                
                Log.d(TAG, "uploadFile success: $fileName (%.2fMB in %.1fs, speed: %.1fMB/s)".format(sizeMB, elapsedSeconds, speedMBps))
                return@withContext true
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "uploadFile timeout: $fileName (attempt ${attempt + 1}/$actualMaxRetry)")
                lastException = e
                attempt++
                if (attempt < actualMaxRetry) {
                    kotlinx.coroutines.delay(delayMillis)
                }
            } catch (e: java.io.IOException) {
                Log.e(TAG, "uploadFile IO error: $fileName (attempt ${attempt + 1}/$actualMaxRetry) - ${e.message}", e)
                lastException = e
                attempt++
                if (attempt < actualMaxRetry) {
                    kotlinx.coroutines.delay(delayMillis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadFile error: $fileName - ${e.javaClass.simpleName}: ${e.message}", e)
                return@withContext false
            }
        }
        
        Log.e(TAG, "uploadFile failed after $actualMaxRetry attempts: $fileName, last error: $lastException")
        return@withContext false
    }

    /**
     * 下载文件
     */
    suspend fun downloadFile(
        remotePath: String,
        fileName: String,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        delayMillis: Long = DEFAULT_RETRY_DELAY_MS
    ): ByteArray = withContext(Dispatchers.IO) {
        var attempt = 0
        
        while (attempt < maxRetry) {
            try {
                val fullPath = remotePath.trimEnd('/') + "/" + fileName
                val fullUrl = webdavUrl.trimEnd('/') + fullPath
                
                // Use use() block to ensure InputStream is properly closed
                val bytes = sardine.get(fullUrl).use { it.readBytes() }
                Log.d(TAG, "downloadFile success: $fileName (${bytes.size} bytes)")
                return@withContext bytes
            } catch (e: Exception) {
                Log.e(TAG, "downloadFile error: $fileName (attempt ${attempt + 1}/$maxRetry) - ${e.message}")
                attempt++
                if (attempt < maxRetry) {
                    kotlinx.coroutines.delay(delayMillis)
                }
            }
        }
        
        Log.e(TAG, "downloadFile failed after $maxRetry attempts: $fileName")
        return@withContext ByteArray(0)
    }

    /**
     * 列出目录内容（使用 Sardine 的 list 方法，自动处理 PROPFIND）
     */
    suspend fun listDirectory(
        remotePath: String,
        maxRetry: Int = DEFAULT_MAX_RETRY
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val fullUrl = webdavUrl.trimEnd('/') + "/" + remotePath.trim('/')
            val resources = sardine.list(fullUrl)
            
            val fileNames = mutableListOf<String>()
            for (resource in resources) {
                val name = resource.name
                // 跳过目录本身和父目录
                if (name.isNotEmpty() && !resource.isDirectory &&
                    (name.endsWith(".png", ignoreCase = true) || 
                     name.endsWith(".jpg", ignoreCase = true) ||
                     name.endsWith(".jpeg", ignoreCase = true))) {
                    fileNames.add(name)
                }
            }
            
            Log.d(TAG, "listDirectory found ${fileNames.size} template files in $remotePath")
            return@withContext fileNames
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory error: $remotePath - ${e.javaClass.simpleName}: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * 删除文件
     */
    suspend fun deleteFile(
        remotePath: String,
        fileName: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = remotePath.trimEnd('/') + "/" + fileName
            val fullUrl = webdavUrl.trimEnd('/') + fullPath
            
            sardine.delete(fullUrl)
            Log.d(TAG, "deleteFile success: $fileName")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "deleteFile error: $fileName - ${e.javaClass.simpleName}: ${e.message}")
            return@withContext false
        }
    }
}
