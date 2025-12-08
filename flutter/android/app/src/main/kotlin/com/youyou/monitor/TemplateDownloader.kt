package com.youyou.monitor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object TemplateDownloader {
    /**
     * 从指定目录批量加载模板图片为 Bitmap 列表
     */
    fun loadTemplatesFromDir(dir: File): List<Bitmap> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.mapNotNull { file ->
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()
    }
    private const val TAG = "TemplateDownloader"

    /**
     * 下载模板图片到本地目录（filesDir/templates），返回保存的文件路径
     */
    fun downloadTemplate(context: Context, url: String, fileName: String): String? {
        return try {
            val dir = File(context.filesDir, "templates")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Downloaded template: ${file.absolutePath}")
                file.absolutePath
            } else {
                Log.e(TAG, "Download failed: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: $e")
            null
        }
    }

    /**
     * 批量加载本地模板图片为 Bitmap 列表（filesDir/templates 下所有图片）
     */
    fun loadLocalTemplates(context: Context): List<Bitmap> {
        val dir = File(context.filesDir, "templates")
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles()?.mapNotNull { file ->
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Exception) {
                null
            }
        } ?: emptyList()
    }
}
