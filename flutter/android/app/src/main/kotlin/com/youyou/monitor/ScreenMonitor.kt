package com.youyou.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.youyou.monitor.MonitorConfig
import androidx.core.graphics.createBitmap

/**
 * 屏幕监控器，自动加载本地模板图片，支持多个模板
 */
class ScreenMonitor(
    private val context: Context, private val matchThreshold: Double = 0.95, // 0..1
    private val saveMatched: Boolean = true
) {
    private val TAG = "ScreenMonitor"
    private val config = MonitorConfig.getInstance()
    @Volatile
    private var lastDetectTime: Long = 0L
    @Volatile
    private var lastFrameHash: Int? = null
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = true
    @Volatile
    private var templateGrays: List<Mat> = emptyList()
    @Volatile
    private var templateNames: List<String> = emptyList()

    init {
        if (!OpenCVLoader.initDebug()) {
            Log.w(TAG, "OpenCV init failed. Ensure OpenCV is available.")
        }
        // 异步加载模板，避免主线程阻塞
        exec.execute {
            loadTemplates()
        }
        // 注册模板热更新回调，异步执行
        config.onTemplatesUpdated = { exec.execute { reloadTemplates() } }
    }

    private fun loadTemplates() {
        val templateDir = config.getTemplateDir()
        Log.d(TAG, "Using template directory: ${templateDir.absolutePath}")
        val files = templateDir.listFiles { f -> f.isFile && (f.name.endsWith(".png") || f.name.endsWith(".jpg")) } ?: emptyArray()
        val bitmaps = mutableListOf<Bitmap>()
        val names = mutableListOf<String>()
        for (file in files) {
            try {
                val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    bitmaps.add(bmp)
                    names.add(file.name)
                }
            } catch (_: Exception) {
            }
        }
        val newTemplateGrays = bitmaps.mapNotNull { bmp ->
            try {
                val tmp = Mat()
                Utils.bitmapToMat(bmp, tmp)
                Imgproc.cvtColor(tmp, tmp, Imgproc.COLOR_RGBA2GRAY)
                tmp
            } catch (_: Exception) {
                null
            }
        }
        templateGrays.forEach { it.release() }
        templateGrays = newTemplateGrays
        templateNames = names
        Log.d(TAG, "Loaded ${templateGrays.size} templates from preferred directory: $templateNames")
    }

    fun reloadTemplates() {
        loadTemplates()
    }

    // 上次强制保存截图的时间戳
    @Volatile
    private var lastForceSaveTime: Long = 0L

    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int) {
        val now = System.currentTimeMillis()
        val detectInterval = if (config.detectPerSecond > 0) 1000 / config.detectPerSecond else 500
        if (now - lastDetectTime < detectInterval) return
        lastDetectTime = now
        if (!running) return
        val hash = try {
            val bytesPerPixel = 4
            val startY = height / 3
            val endY = startY * 2
            val regionHeight = endY - startY
            val regionWidth = width
            val oldPos = buffer.position()
            buffer.rewind()
            val bmp = Bitmap.createBitmap(width, height, Config.ARGB_8888)
            buffer.rewind()
            bmp.copyPixelsFromBuffer(buffer)
            buffer.position(oldPos)
            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            var sum = 0L
            var count = 0
            for (y in startY until endY) {
                for (x in 0 until regionWidth) {
                    sum += mat.get(y, x)[0].toInt()
                    count++
                }
            }
            mat.release()
            bmp.recycle()
            (sum / count).toInt()
        } catch (_: Exception) {
            null
        }
        if (lastFrameHash != null && hash == lastFrameHash) return
        lastFrameHash = hash
        // 安全拷贝 buffer 内容到 ByteArray，异常时丢弃本帧
        val byteArray = try {
            val arr = ByteArray(buffer.remaining())
            buffer.rewind()
            buffer.get(arr)
            arr
        } catch (e: Exception) {
            Log.e(TAG, "buffer copy error: $e")
            return
        }

        // 每隔30分钟强制保存一张截图（不做AI校验）
        val FORCE_INTERVAL = 30 * 60 * 1000L // 30分钟
        if (now - lastForceSaveTime > FORCE_INTERVAL) {
            lastForceSaveTime = now
            try {
                val bmp = createBitmap(width, height)
                val buf = ByteBuffer.wrap(byteArray)
                bmp.copyPixelsFromBuffer(buf)
                saveBitmap(bmp, "forced")
                bmp.recycle()
                Log.d(TAG, "Force saved screenshot at ${Date(now)}")
            } catch (e: Exception) {
                Log.e(TAG, "Force save screenshot error: $e")
            }
        }
        exec.execute { processFrame(byteArray, width, height) }
    }

    private fun processFrame(byteArray: ByteArray, width: Int, height: Int) {
        try {
            if (templateGrays.isEmpty()) return
            val bmp = createBitmap(width, height)
            val buf = ByteBuffer.wrap(byteArray)
            bmp.copyPixelsFromBuffer(buf)
            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            val matchVals = mutableListOf<Double>()
            for ((idx, tmpl) in templateGrays.withIndex()) {
                if (mat.cols() < tmpl.cols() || mat.rows() < tmpl.rows()) {
                    matchVals.add(Double.NEGATIVE_INFINITY)
                    continue
                }
                val resultCols = mat.cols() - tmpl.cols() + 1
                val resultRows = mat.rows() - tmpl.rows() + 1
                val result = Mat(resultRows, resultCols, CvType.CV_32FC1)
                Imgproc.matchTemplate(mat, tmpl, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                val maxVal = mm.maxVal
                val maxLoc = mm.maxLoc
                matchVals.add(maxVal)
                Log.d(
                    TAG,
                    "matchTemplate maxVal=$maxVal at $maxLoc (templateIdx=$idx, name=${
                        templateNames.getOrNull(idx) ?: "unknown"
                    })"
                )
                if (maxVal >= matchThreshold) {
                    val matchRect = Rect(
                        Point(maxLoc.x, maxLoc.y),
                        Size(tmpl.cols().toDouble(), tmpl.rows().toDouble())
                    )
                    val matchedName = templateNames.getOrNull(idx) ?: "template$idx"
                    Log.d(
                        TAG,
                        "Matched rect: $matchRect val=$maxVal templateIdx=$idx name=$matchedName"
                    )
                    if (saveMatched) saveBitmap(bmp, matchedName)
                    break
                }
                result.release()
            }
            Log.d(TAG, "All template maxVals: $matchVals")
            mat.release()
            bmp.recycle()
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: $e")
        }
    }

    private fun saveBitmap(bmp: Bitmap, templateName: String) {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd").format(Date())
            val baseDir = config.getScreenshotDir()
            val dir = File(baseDir, dateStr)
            if (!dir.exists()) dir.mkdirs()
            Log.d(TAG, "saveBitmap: target dir = ${dir.absolutePath}")
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val nameNoExt = templateName.substringBeforeLast('.')
            val f = File(dir, "capture_${nameNoExt}_$ts.png")
            Log.d(TAG, "saveBitmap: file = ${f.absolutePath}")
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            Log.d(TAG, "Saved matched screenshot: ${f.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmap fail: $e")
        }
    }

    fun shutdown() {
        running = false
        try {
            exec.shutdownNow()
        } catch (_: Exception) {
        }
        templateGrays.forEach { it.release() }
        templateGrays = emptyList()
    }
}
