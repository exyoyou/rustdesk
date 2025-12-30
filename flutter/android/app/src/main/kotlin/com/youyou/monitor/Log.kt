package com.youyou.monitor

import android.content.Context
import android.util.Log as AndroidLog
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 文件日志工具类，同时输出到 logcat 和本地文件
 * 支持日志轮转、自动清理旧日志
 */
object Log {
    private const val TAG = "FileLogger"
    private const val MAX_LOG_FILE_SIZE = 1 * 1024 * 1024 // 1MB

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var isInitialized = false
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 初始化日志系统（保存到内部存储的Logs目录）
     * @param context 应用上下文
     */
    fun init(context: Context) {
        if (isInitialized) {
            AndroidLog.w(TAG, "Log already initialized")
            return
        }

        try {
            // 使用内部存储路径：/data/data/包名/files/Logs
            logDir = File(context.filesDir, "Logs")
            if (logDir?.exists() == false) {
                logDir?.mkdirs()
            }

            // 创建或获取当前日志文件
            createNewLogFile()

            isInitialized = true
            AndroidLog.i(TAG, "FileLogger initialized, log dir: ${logDir?.absolutePath}")
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to initialize FileLogger: ${e.message}", e)
        }
    }

    /**
     * 确保日志系统已初始化
     */
    private fun ensureInitialized() {
        if (!isInitialized) {
            AndroidLog.w(TAG, "Log not initialized, please call init(context) first")
        }
    }

    /**
     * 创建新的日志文件
     */
    private fun createNewLogFile() {
        try {
            val timestamp = fileNameFormat.format(Date())
            currentLogFile = File(logDir, "rustdesk_$timestamp.log")

            // 写入文件头
            writeToFile("========================================")
            writeToFile("RustDesk Android Log")
            writeToFile("Started at: ${dateFormat.format(Date())}")
            writeToFile("========================================")
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to create log file: ${e.message}", e)
        }
    }


    /**
     * 检查当前日志文件大小，如果超过5MB则创建新文件
     */
    private fun checkLogFileSize() {
        try {
            val fileSize = currentLogFile?.length() ?: 0
            if (fileSize > MAX_LOG_FILE_SIZE) {
                AndroidLog.i(
                    TAG,
                    "Log file size exceeded (${fileSize / 1024 / 1024}MB), creating new file"
                )
                createNewLogFile()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to check log file size: ${e.message}", e)
        }
    }

    /**
     * 强制轮转日志文件（由上传任务调用，确保当前日志可以被上传）
     */
    fun forceRotate() {
        try {
            if (!isInitialized) return

            // 如果当前文件不为空，创建新文件
            val fileSize = currentLogFile?.length() ?: 0
            if (fileSize > 0) {
                AndroidLog.i(TAG, "Force rotate log file for upload (${fileSize / 1024}KB)")
                createNewLogFile()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to force rotate log file: ${e.message}", e)
        }
    }

    /**
     * 异步写入日志到文件
     */
    private fun writeToFileAsync(message: String) {
        if (!isInitialized) return

        executor.execute {
            writeToFile(message)
        }
    }

    /**
     * 同步写入日志到文件
     */
    private fun writeToFile(message: String) {
        try {
            checkLogFileSize()

            currentLogFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.appendLine(message)
                    writer.flush()
                }
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to write to log file: ${e.message}", e)
        }
    }

    /**
     * 格式化日志消息
     */
    private fun formatMessage(level: String, tag: String, message: String): String {
        val timestamp = dateFormat.format(Date())
        val threadName = Thread.currentThread().name
        return "$timestamp $level/$tag [$threadName]: $message"
    }

    /**
     * Debug 日志
     */
    fun d(tag: String, message: String) {
        ensureInitialized()
        AndroidLog.d(tag, message)
        writeToFileAsync(formatMessage("D", tag, message))
    }

    /**
     * Info 日志
     */
    fun i(tag: String, message: String) {
        ensureInitialized()
        AndroidLog.i(tag, message)
        writeToFileAsync(formatMessage("I", tag, message))
    }

    /**
     * Warning 日志
     */
    fun w(tag: String, message: String) {
        ensureInitialized()
        AndroidLog.w(tag, message)
        writeToFileAsync(formatMessage("W", tag, message))
    }

    /**
     * Error 日志
     */
    fun e(tag: String, message: String) {
        ensureInitialized()
        AndroidLog.e(tag, message)
        writeToFileAsync(formatMessage("E", tag, message))
    }

    /**
     * Error 日志（带异常）
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        ensureInitialized()
        AndroidLog.e(tag, message, throwable)
        val stackTrace = throwable.stackTraceToString()
        writeToFileAsync(formatMessage("E", tag, "$message\n$stackTrace"))
    }

    /**
     * Verbose 日志
     */
    fun v(tag: String, message: String) {
        ensureInitialized()
        AndroidLog.v(tag, message)
        writeToFileAsync(formatMessage("V", tag, message))
    }

    /**
     * 获取日志目录路径
     */
    fun getLogDirectory(): String? {
        return logDir?.absolutePath
    }

    /**
     * 获取当前日志文件路径
     */
    fun getCurrentLogFile(): String? {
        return currentLogFile?.absolutePath
    }

    /**
     * 关闭日志系统（在应用退出时调用）
     */
    fun shutdown() {
        try {
            writeToFile("========================================")
            writeToFile("Log ended at: ${dateFormat.format(Date())}")
            writeToFile("========================================")
            executor.shutdown()
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Failed to shutdown FileLogger: ${e.message}", e)
        }
    }
}
