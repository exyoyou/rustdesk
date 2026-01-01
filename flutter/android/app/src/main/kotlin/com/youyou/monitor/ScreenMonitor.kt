package com.youyou.monitor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import com.youyou.monitor.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
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
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var running = true
    @Volatile
    private var isProcessing = false  // 防止队列堆积
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert bitmap to Mat: $e")
                null
            }
        }
        
        // 释放旧的模板Mat对象
        templateGrays.forEach { it.release() }
        templateGrays = newTemplateGrays
        templateNames = names
        
        // 重要：回收所有Bitmap，防止内存泄漏
        bitmaps.forEach { it.recycle() }
        
        Log.d(TAG, "Loaded ${templateGrays.size} templates from preferred directory: $templateNames")
    }

    fun reloadTemplates() {
        loadTemplates()
    }

    @Volatile private var lastForceSaveTime: Long = 0L
    @Volatile private var lastFrameSignature: Long = 0L  // 使用更精确的帧签名
    @Volatile private var frameCallCount: Long = 0L  // 调用计数器
    @Volatile private var lastLogTime: Long = 0L  // 上次日志时间
    
    // 性能优化：SimpleDateFormat 创建开销大，复用实例（线程不安全，仅在单线程 executor 中使用）
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * 帧回调入口 - 高性能设计
     * 1. 快速频率限制
     * 2. 轻量级帧去重（避免重复处理相同画面）
     * 3. 异步处理，不阻塞视频流
     * 
     * 性能优化：
     * - 使用绝对位置访问ByteBuffer，无需rewind()
     * - 预分配采样点数组，减少GC
     * - 只在必要时复制buffer数据
     */
    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int) {
        frameCallCount++
        
        // 1. 队列堆积检查（最优先，避免所有后续操作）
        if (isProcessing) {
            if (frameCallCount % 50 == 0L) {
                Log.d(TAG, "[Skip] Previous frame still processing")
            }
            return
        }
        
        val now = System.currentTimeMillis()
        
        // 每10秒输出一次统计信息
        if (now - lastLogTime > 10000) {
            Log.i(TAG, "[Stats] Total calls: $frameCallCount, running: $running, isProcessing: $isProcessing, templates: ${templateGrays.size}")
            lastLogTime = now
        }
        
        // 2. 频率限制
        val interval = if (config.detectPerSecond > 0) 1000 / config.detectPerSecond else 500
        if (now - lastDetectTime < interval) {
            if (frameCallCount % 100 == 0L) {
                Log.d(TAG, "[Skip] Rate limit: ${now - lastDetectTime}ms < ${interval}ms")
            }
            return
        }
        if (!running) {
            Log.w(TAG, "[Skip] Monitor not running")
            return
        }
        lastDetectTime = now
        
        // 3. 快速帧签名计算（采样9个点：四角+四边中点+中心）
        // 使用绝对索引访问，不改变buffer的position状态
        val signature = try {
            val stride = width * 4
            val halfWidth = width / 2
            val halfHeight = height / 2
            val lastRow = height - 1
            val lastCol = width - 1
            
            var sig = 0L
            // 按顺序计算9个采样点：上(左中右) 中(左中右) 下(左中右)
            val pixels = intArrayOf(
                0, halfWidth, lastCol,                          // 上排
                halfHeight * width, halfHeight * width + halfWidth, halfHeight * width + lastCol,  // 中排
                lastRow * width, lastRow * width + halfWidth, lastRow * width + lastCol  // 下排
            )
            
            for (i in pixels.indices) {
                val offset = pixels[i] * 4
                if (offset + 2 < buffer.capacity()) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val gray = (r + g + b) / 3
                    sig = sig or (gray.toLong() shl (i * 7))  // 每个点7bit
                }
            }
            sig
        } catch (e: Exception) {
            Log.e(TAG, "[Skip] Signature calculation failed: $e")
            return
        }
        
        // 4. 帧去重：签名相同则跳过
        if (signature == lastFrameSignature) {
            if (frameCallCount % 50 == 0L) {
                Log.d(TAG, "[Skip] Duplicate frame (signature: $signature)")
            }
            return
        }
        
        // 5. 复制帧数据（使用capacity确保完整复制）
        val frameData = try {
            val expectedSize = width * height * 4
            val actualSize = buffer.capacity()
            if (actualSize < expectedSize) {
                Log.e(TAG, "[Skip] Buffer too small: expected=$expectedSize, actual=$actualSize, size=${width}x${height}")
                return
            }
            val arr = ByteArray(expectedSize)
            buffer.position(0)  // 重置到起始位置
            buffer.get(arr, 0, expectedSize)
            arr
        } catch (e: Exception) {
            Log.e(TAG, "[Skip] Buffer copy failed: $e")
            return
        }
        
        Log.d(TAG, "[Process] Frame accepted: ${width}x${height}, signature=$signature")
        
        // 5. 更新签名（确认要处理后才更新，避免队列堆积时误更新）
        lastFrameSignature = signature
        
        // 6. 异步处理
        isProcessing = true
        exec.execute {
            try {
                processFrame(frameData, width, height, now)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * 异步帧处理
     * 1. 模板匹配
     * 2. 定期强制保存（带图像质量检测）
     */
    private fun processFrame(byteArray: ByteArray, width: Int, height: Int, now: Long) {
        var bmp: Bitmap? = null
        var mat: Mat? = null
        try {
            if (templateGrays.isEmpty()) {
                Log.w(TAG, "No templates loaded, skipping frame")
                return
            }
            
            // 创建 Bitmap 和 Mat
            bmp = createBitmap(width, height)
            val buf = ByteBuffer.wrap(byteArray)
            bmp.copyPixelsFromBuffer(buf)
            mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            
            // 优先检查图像质量：跳过黑屏/纯色画面，节省后续处理
            if (!isValidImage(mat)) {
                Log.w(TAG, "Frame is blank/monochrome, skipping all processing")
                return
            }
            
            // 定期强制保存：每30分钟保存一张有效图像
            val FORCE_INTERVAL = 30 * 60 * 1000L  // 30分钟
            if (now - lastForceSaveTime > FORCE_INTERVAL) {
                saveBitmap(bmp, "forced")
                lastForceSaveTime = now
                Log.i(TAG, "Force saved valid screenshot")
            }
            
            // 模板匹配（仅对有效图像执行）
            val matchVals = mutableListOf<Double>()
            for ((idx, tmpl) in templateGrays.withIndex()) {
                if (mat.cols() < tmpl.cols() || mat.rows() < tmpl.rows()) {
                    Log.w(TAG, "Mat too small for template[$idx]: mat=${mat.cols()}x${mat.rows()}, tmpl=${tmpl.cols()}x${tmpl.rows()}")
                    matchVals.add(Double.NEGATIVE_INFINITY)
                    continue
                }
                val resultCols = mat.cols() - tmpl.cols() + 1
                val resultRows = mat.rows() - tmpl.rows() + 1
                var result: Mat? = null
                try {
                    result = Mat(resultRows, resultCols, CvType.CV_32FC1)
                    Imgproc.matchTemplate(mat, tmpl, result, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(result)
                    val maxVal = mm.maxVal
                    matchVals.add(maxVal)
                    if (maxVal >= matchThreshold) {
                        val matchedName = templateNames.getOrNull(idx) ?: "template$idx"
                        Log.i(TAG, "✓ Matched: $matchedName (score=$maxVal)")
                        if (saveMatched) saveBitmap(bmp, matchedName)
                        break
                    }
                } finally {
                    result?.release()
                }
            }
            // 如果没有匹配，记录最高分数（用于调试）
            if (matchVals.maxOrNull()?.let { it < matchThreshold } == true) {
                Log.d(TAG, "No match. Best scores: ${matchVals.take(3)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: $e")
        } finally {
            mat?.release()
            bmp?.recycle()
        }
    }

    /**
     * 图像质量检测：避免保存黑屏、花屏、纯色画面
     * 
     * 检测策略：
     * 1. 采样中心区域 (避免状态栏/导航栏干扰)
     * 2. 计算亮度分布 (stdDev < 5 = 几乎纯色/黑屏)
     * 3. 轻量级算法，适合高频调用
     */
    private fun isValidImage(grayMat: Mat): Boolean {
        var roi: Mat? = null
        var mean: MatOfDouble? = null
        var stddev: MatOfDouble? = null
        return try {
            // 采样中心80%区域
            val startX = (grayMat.cols() * 0.1).toInt()
            val startY = (grayMat.rows() * 0.1).toInt()
            roi = grayMat.submat(
                startY, (grayMat.rows() * 0.9).toInt(),
                startX, (grayMat.cols() * 0.9).toInt()
            )
            
            mean = MatOfDouble()
            stddev = MatOfDouble()
            Core.meanStdDev(roi, mean, stddev)
            
            val stdVal = stddev.get(0, 0)[0]
            val isValid = stdVal >= 5.0  // 标准差阈值：< 5 = 几乎纯色
            
            if (!isValid) {
                Log.d(TAG, "Invalid frame: stdDev=$stdVal (too low)")
            }
            isValid
        } catch (e: Exception) {
            Log.e(TAG, "isValidImage error: $e")
            false
        } finally {
            // 释放所有OpenCV对象，防止内存泄漏
            roi?.release()
            mean?.release()
            stddev?.release()
        }
    }

    private fun saveBitmap(bmp: Bitmap, templateName: String) {
        try {
            val dateStr = dateFormat.format(Date())
            val baseDir = config.getScreenshotDir()
            val dir = File(baseDir, dateStr)
            if (!dir.exists()) dir.mkdirs()
            val ts = timestampFormat.format(Date())
            val nameNoExt = templateName.substringBeforeLast('.')
            val f = File(dir, "capture_${nameNoExt}_$ts.png")
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            Log.i(TAG, "Saved: ${f.name}")
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
