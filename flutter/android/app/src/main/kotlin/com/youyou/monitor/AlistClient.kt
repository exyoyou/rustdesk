package com.youyou.monitor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

class AlistClient(
    val apiUrl: String,
    val user: String,
    val password: String,
    val monitorDir: String,
    val remoteUploadDir: String,
    val templateDir: String,
    val defaultTimeout: Int = DEFAULT_TIMEOUT_MS
) {

    companion object {
        const val TAG = "AlistClient"
        const val DEFAULT_TIMEOUT_MS = 60000 // 1分钟超时
        const val DEFAULT_MAX_RETRY = 3      // 默认重试次数
        const val DEFAULT_RETRY_DELAY_MS = 2000L // 默认重试间隔（毫秒）
    }

    @Volatile
    var token: String? = null

    @Volatile
    private var tokenRetryCount = 0

    // 不再支持 setConfig，所有参数初始化时传入且不可为空

    init {
        // fetchToken() 不能在主线程直接调用，需在协程或子线程中调用
    }

    suspend fun fetchToken(maxRetry: Int = DEFAULT_MAX_RETRY, delayMillis: Long = DEFAULT_RETRY_DELAY_MS) =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl.trimEnd('/') + "/api/auth/login"
                val json = """{"username":"$user","password":"$password"}"""
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = defaultTimeout
                conn.readTimeout = defaultTimeout
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray()) }
                val code = conn.responseCode
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (code == 200) {
                    val obj = JSONObject(resp)
                    val dataObj = obj.optJSONObject("data")
                    val t = dataObj?.optString("token", null)
                    if (!t.isNullOrBlank()) {
                        token = t
                        tokenRetryCount = 0
                        Log.d(TAG, "Token fetched: $token")
                    } else {
                        Log.e(TAG, "Token fetch failed: $resp")
                        retryTokenSuspend(maxRetry, delayMillis)
                    }
                } else {
                    Log.e(TAG, "Token fetch failed: $code $resp")
                    retryTokenSuspend(maxRetry, delayMillis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token fetch error: $e")
                retryTokenSuspend(maxRetry, delayMillis)
            }
        }

    private suspend fun retryTokenSuspend(maxRetry: Int, delayMillis: Long) {
        if (tokenRetryCount < maxRetry) {
            tokenRetryCount++
            kotlinx.coroutines.delay(delayMillis)
            fetchToken(maxRetry, delayMillis)
        } else {
            Log.e(TAG, "Token fetch failed after $maxRetry retries.")
            tokenRetryCount = 0
        }
    }

    suspend fun apiList(path: String, retry: Boolean = true, maxRetry: Int = DEFAULT_MAX_RETRY, delayMillis: Long = DEFAULT_RETRY_DELAY_MS): String = withContext(Dispatchers.IO) {
        val url = apiUrl.trimEnd('/') + "/api/fs/list"
        val json = """{"path":"$path"}"""
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = defaultTimeout
        conn.readTimeout = defaultTimeout
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "$token")
        }
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        if (code == 401 && retry) {
        Log.w(TAG, "apiList token expired, refreshing token...")
            fetchToken(maxRetry, delayMillis)
            kotlinx.coroutines.delay(delayMillis)
            return@withContext apiList(path, false, maxRetry, delayMillis)
        }
        if (code != 200) throw Exception("apiList failed: $code $resp")
        return@withContext resp
    }

    suspend fun apiGet(path: String, retry: Boolean = true, maxRetry: Int = DEFAULT_MAX_RETRY, delayMillis: Long = DEFAULT_RETRY_DELAY_MS): String = withContext(Dispatchers.IO) {
        try {
            val url = apiUrl.trimEnd('/') + "/api/fs/get"
            val json = """{"path":"$path"}"""
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = defaultTimeout
            conn.readTimeout = defaultTimeout
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) {
                conn.setRequestProperty("Authorization", "$token")
            }
            conn.outputStream.use { it.write(json.toByteArray()) }
            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (code == 401 && retry) {
            Log.w(TAG, "apiGet token expired, refreshing token...")
                fetchToken(maxRetry, delayMillis)
                kotlinx.coroutines.delay(delayMillis)
                return@withContext apiGet(path, false, maxRetry, delayMillis)
            }
            if (code != 200) return@withContext ""
            val obj = JSONObject(resp)
            val data = obj.optJSONObject("data")
            val rawUrl = data?.optString("raw_url", null)
            if (rawUrl.isNullOrBlank()) return@withContext ""
            val fileBytes = URL(rawUrl).openStream().readBytes()
            return@withContext String(fileBytes)
        } catch (e: Exception) {
            Log.e(TAG, "apiGet error: $e")
            return@withContext ""
        }
    }

    suspend fun apiGetBytes(path: String, retry: Boolean = true, maxRetry: Int = DEFAULT_MAX_RETRY, delayMillis: Long = DEFAULT_RETRY_DELAY_MS): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                val url = apiUrl.trimEnd('/') + "/api/fs/get"
                val json = """{"path":"$path"}"""
                val conn = URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = defaultTimeout
                conn.readTimeout = defaultTimeout
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (!token.isNullOrBlank()) {
                    conn.setRequestProperty("Authorization", "$token")
                }
                conn.outputStream.use { it.write(json.toByteArray()) }
                val code = conn.responseCode
                val resp = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                if (code == 401 && retry) {
                Log.w(TAG, "apiGetBytes token expired, refreshing token...")
                    fetchToken(maxRetry, delayMillis)
                    kotlinx.coroutines.delay(delayMillis)
                    return@withContext apiGetBytes(path, false, maxRetry, delayMillis)
                }
                if (code != 200) return@withContext ByteArray(0)
                val obj = JSONObject(resp)
                val data = obj.optJSONObject("data")
                val rawUrl = data?.optString("raw_url", null)
                if (rawUrl.isNullOrBlank()) return@withContext ByteArray(0)
                return@withContext URL(rawUrl).openStream().readBytes()
            } catch (e: Exception) {
                Log.e(TAG, "apiGetBytes error: $e")
                return@withContext ByteArray(0)
            }
        }

    suspend fun apiPut(
        path: String,
        name: String,
        bytes: ByteArray,
        overwrite: Boolean = true,
        retry: Boolean = true,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        delayMillis: Long = DEFAULT_RETRY_DELAY_MS
    ): Boolean = withContext(Dispatchers.IO) {
        val url = apiUrl.trimEnd('/') + "/api/fs/put"
        // File-Path: 完整路径+文件名，需URL编码
        val fullPath = if (path.endsWith("/")) path + name else "$path/$name"
        val encodedPath = java.net.URLEncoder.encode(fullPath, "UTF-8")
        try {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = defaultTimeout
            conn.readTimeout = defaultTimeout
            conn.requestMethod = "PUT"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", token ?: "")
            conn.setRequestProperty("File-Path", encodedPath)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", bytes.size.toString())
            conn.setRequestProperty("Overwrite", overwrite.toString())
            // body为二进制
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            if (code == 401 && retry) {
            Log.w(TAG, "apiPut token expired, refreshing token...")
                fetchToken(maxRetry, delayMillis)
                kotlinx.coroutines.delay(delayMillis)
                return@withContext apiPut(path, name, bytes, overwrite, false, maxRetry, delayMillis)
            }
            if (code != 200) {
                Log.e(TAG, "apiPut failed: $code $resp")
                return@withContext false
            }
            // 解析resp，只有message==success才算成功
            return@withContext try {
                val obj = JSONObject(resp)
                val msg = obj.optString("message", "")
                val ok = msg == "success"
                Log.d(TAG, "apiPut result: $msg resp=$resp")
                ok
            } catch (e: Exception) {
                Log.e(TAG, "apiPut parse resp error: $e $resp")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "apiPut error: $name $e")
            return@withContext false
        }
    }

    /**
     * 获取 alist 根目录下 config.json 文件内容
     */
    suspend fun getConfigJson(maxRetry: Int = DEFAULT_MAX_RETRY, delayMillis: Long = DEFAULT_RETRY_DELAY_MS): String {
        val configPath = "/$monitorDir/config.json".replace("//", "/")
        return apiGet(configPath, true, maxRetry, delayMillis)
    }

    /**
     * 获取 templateDir 下所有模板图片内容，返回 Map<文件名, 图片二进制内容>
     */
    suspend fun getAllTemplateImages(maxRetry: Int = DEFAULT_MAX_RETRY, delayMillis: Long = DEFAULT_RETRY_DELAY_MS): Map<String, ByteArray> {
        val listJson = apiList("/$templateDir".replace("//", "/"), true, maxRetry, delayMillis)
        val result = mutableMapOf<String, ByteArray>()
        try {
            val obj = JSONObject(listJson)
            val dataArr = obj.optJSONObject("data")?.optJSONArray("content")
            if (dataArr != null) {
                for (i in 0 until dataArr.length()) {
                    val f = dataArr.getJSONObject(i)
                    val name = f.optString("name", "")
                    val isFile = !f.optBoolean("is_dir", false)
                    if (isFile && (name.endsWith(".png") || name.endsWith(".jpg"))) {
                        try {
                            val bytes = apiGetBytes("/$templateDir/".replace("//", "/") + name, true, maxRetry, delayMillis)
                            result[name] = bytes
                        } catch (e: Exception) {
                        Log.e(TAG, "getAllTemplateImages: failed to get $name: $e")
                        }
                    }
                }
            }
        } catch (e: Exception) {
        Log.e(TAG, "getAllTemplateImages parse error: $e")
        }
        return result
    }

    /**
     * 上传图片到 remoteUploadDir 下指定路径
     * @param subPath 远程子路径（可为空，表示直接上传到 remoteUploadDir 根目录）
     * @param fileName 文件名
     * @param bytes 图片二进制内容
     * @param overwrite 是否覆盖同名文件，默认 true
     * @return 上传成功返回 true，否则 false
     */
    suspend fun uploadImageToRemoteDir(
        subPath: String?,
        fileName: String,
        bytes: ByteArray,
        overwrite: Boolean = true,
        maxRetry: Int = DEFAULT_MAX_RETRY,
        delayMillis: Long = DEFAULT_RETRY_DELAY_MS
    ): Boolean {
        // 计算远程路径
        val baseDir = "/$remoteUploadDir".replace("//", "/")
        val path =
            if (subPath.isNullOrBlank()) baseDir else (baseDir.trimEnd('/') + "/" + subPath.trim('/'))
        return apiPut(path, fileName, bytes, overwrite, true, maxRetry, delayMillis)
    }
}
