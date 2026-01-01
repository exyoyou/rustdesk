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
    private val context: Context,
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
                
                // 模板也缩小到相同基准（与匹配图像一致）
                val MAX_DIMENSION = 3200
                if (tmp.cols() > MAX_DIMENSION || tmp.rows() > MAX_DIMENSION) {
                    val maxDim = maxOf(tmp.cols(), tmp.rows())
                    val scale = MAX_DIMENSION.toFloat() / maxDim
                    val resized = Mat()
                    val newSize = Size((tmp.cols() * scale).toDouble(), (tmp.rows() * scale).toDouble())
                    Imgproc.resize(tmp, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                    tmp.release()
                    Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2GRAY)
                    resized
                } else {
                    Imgproc.cvtColor(tmp, tmp, Imgproc.COLOR_RGBA2GRAY)
                    tmp
                }
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
    @Volatile private var lastMatchTime: Long = 0L  // 最后匹配成功时间
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
     * @param scale 屏幕缩放比例（1=原始，2=半分辨率）
     */
    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int, scale: Int = 1) {
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
                processFrame(frameData, width, height, now, scale)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * 异步帧处理
     * 1. 模板匹配
     * 2. 定期强制保存（带图像质量检测）
     * @param scale 屏幕缩放比例（1=原始分辨率，2=半分辨率）
     */
    private fun processFrame(byteArray: ByteArray, width: Int, height: Int, now: Long, scale: Int = 1) {
        // 早返回：无模板则无需创建任何对象
        if (templateGrays.isEmpty()) {
            Log.w(TAG, "No templates loaded, skipping frame")
            return
        }
        
        var bmp: Bitmap? = null
        var mat: Mat? = null
        try {
            
            // 创建 Bitmap 和 Mat
            bmp = createBitmap(width, height)
            val buf = ByteBuffer.wrap(byteArray)
            bmp.copyPixelsFromBuffer(buf)
            
            // 性能优化：对于超大图像，先缩小再匹配（金字塔策略）
            // 策略：如果scale=2（halfScale启用），图像已经是半分辨率，无需再缩小
            //      如果scale=1（原始分辨率），超过2160p才缩小以加速
            val MAX_DIMENSION = if (scale == 2) {
                Int.MAX_VALUE  // halfScale已启用，保持原样
            } else {
                2160  // 原始分辨率时，超过2160p才缩小
            }
            val needResize = width > MAX_DIMENSION || height > MAX_DIMENSION
            val resizeScale = if (needResize) {
                val maxDim = maxOf(width, height)
                MAX_DIMENSION.toFloat() / maxDim
            } else {
                1.0f
            }
            
            mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            
            // 如果需要缩小图像以加速匹配
            if (needResize) {
                val resized = Mat()
                val newSize = Size((mat.cols() * resizeScale).toDouble(), (mat.rows() * resizeScale).toDouble())
                Imgproc.resize(mat, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                mat.release()
                mat = resized
                Log.d(TAG, "Resized for matching: ${width}x${height} -> ${mat.cols()}x${mat.rows()} (scale=${String.format("%.2f", resizeScale)})")
            }
            
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
            
            // 模板匹配（仅对有效图像执行）- 灰度多尺度匹配
            val matchVals = mutableListOf<Pair<String, Double>>()
            
            // 匹配成功冷却期：避免重复截图同一界面（可通过配置调整）
            val timeSinceLastMatch = now - lastMatchTime
            if (lastMatchTime > 0 && timeSinceLastMatch < config.matchCooldownMs) {
                Log.d(TAG, "Skip matching: in cooldown period (${timeSinceLastMatch}ms / ${config.matchCooldownMs}ms since last match)")
                return
            }
            
            for ((idx, tmpl) in templateGrays.withIndex()) {
                val templateName = templateNames.getOrNull(idx) ?: "template$idx"
                val templateStartTime = System.currentTimeMillis()
                
                // 智能多尺度匹配：两阶段策略
                // 阶段1：粗搜索 - 快速定位大致范围 (3个点)
                // 阶段2：细搜索 - 在最佳点附近细化 (2-3个点)
                val coarseScales = floatArrayOf(1.0f, 0.7f, 0.5f)  // 粗粒度：覆盖全范围
                var bestScore = Double.NEGATIVE_INFINITY
                var bestScale = 1.0f
                val scaleScores = mutableListOf<Pair<Float, Double>>()
                
                // 阶段1：粗搜索
                for (scale in coarseScales) {
                    val scaledWidth = (tmpl.cols() * scale).toInt()
                    val scaledHeight = (tmpl.rows() * scale).toInt()
                    
                    if (scaledWidth > mat.cols() || scaledHeight > mat.rows()) continue
                    if (scaledWidth < 30 || scaledHeight < 30) continue
                    
                    val score = matchAtScale(tmpl, mat, scale)
                    scaleScores.add(Pair(scale, score))
                    if (score > bestScore) {
                        bestScore = score
                        bestScale = scale
                    }
                }
                
                // 提前退出优化：如果粗搜索分数很低，跳过细搜索
                val threshold = config.matchThreshold
                if (bestScore < threshold - 0.20) {
                    // 粗搜索最高分远低于阈值，不太可能匹配，跳过细搜索
                    matchVals.add(Pair(templateName, bestScore))
                    continue  // 跳到下一个模板
                }
                
                // 阶段2：细搜索 - 在最佳尺度附近细化
                val fineScales = when {
                    bestScale >= 0.9f -> floatArrayOf(0.95f, 0.9f, 0.85f)  // 接近原始尺寸
                    bestScale >= 0.65f -> floatArrayOf(bestScale + 0.05f, bestScale - 0.05f)  // 中间范围
                    else -> floatArrayOf(0.55f, 0.48f, 0.45f)  // 接近50%
                }.filter { it != bestScale }  // 排除已测试的
                
                for (scale in fineScales) {
                    val scaledWidth = (tmpl.cols() * scale).toInt()
                    val scaledHeight = (tmpl.rows() * scale).toInt()
                    
                    if (scaledWidth > mat.cols() || scaledHeight > mat.rows()) continue
                    if (scaledWidth < 30 || scaledHeight < 30) continue
                    
                    val score = matchAtScale(tmpl, mat, scale)
                    scaleScores.add(Pair(scale, score))
                    if (score > bestScore) {
                        bestScore = score
                        bestScale = scale
                    }
                }
                
                // 调试输出和匹配判断
                val weakThreshold = threshold - 0.04  // 弱匹配阈值：比正常阈值低0.04（例如0.92->0.88）
                val templateElapsed = System.currentTimeMillis() - templateStartTime
                
                if (bestScore > threshold - 0.10 && scaleScores.isNotEmpty()) {
                    val scoresStr = scaleScores.sortedByDescending { it.second }
                        .take(5)
                        .joinToString(", ") { "${String.format("%.2f", it.first)}=${String.format("%.3f", it.second)}" }
                    Log.d(TAG, "[$templateName] ${scaleScores.size} scales in ${templateElapsed}ms, best: [$scoresStr]")
                } else if (scaleScores.size == 3) {
                    // 仅粗搜索就跳过了（性能优化生效）
                    Log.d(TAG, "[$templateName] Skipped fine search (coarse best=${String.format("%.3f", bestScore)} << threshold)")
                }
                
                matchVals.add(Pair(templateName, bestScore))
                
                // 早停：匹配成功立即保存并退出
                if (bestScore >= threshold) {
                    Log.i(TAG, "✓ Matched: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold)")
                    if (saveMatched) saveBitmap(bmp, templateName)
                    lastMatchTime = now  // 更新匹配时间，启动冷却期
                    return  // 早停
                } else if (bestScore >= weakThreshold) {
                    // 弱匹配：分数接近但未达到阈值，仍然保存但标记为弱匹配
                    Log.i(TAG, "⚠ Weak match: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold, diff=${String.format("%.3f", threshold - bestScore)})")
                    if (saveMatched) saveBitmap(bmp, "weak_$templateName")
                    lastMatchTime = now  // 也启动冷却期
                    return  // 早停
                }
            }
            
            // 如果没有匹配，记录最高分数（用于调试）
            if (matchVals.isNotEmpty()) {
                val sortedScores = matchVals.sortedByDescending { it.second }.take(3)
                val bestOverallScore = sortedScores.firstOrNull()?.second ?: 0.0
                val threshold = config.matchThreshold
                if (bestOverallScore < threshold - 0.04) {
                    Log.w(TAG, "✗ No match (threshold=$threshold). Top scores: ${sortedScores.map { "${it.first}=${String.format("%.3f", it.second)}" }}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error: $e")
        } finally {
            mat?.release()
            bmp?.recycle()
        }
    }
    
    /**
     * 在指定尺度下执行模板匹配
     * @return 匹配分数 (0-1)
     */
    private fun matchAtScale(template: Mat, image: Mat, scale: Float): Double {
        var scaledTmpl: Mat? = null
        var result: Mat? = null
        return try {
            // 缩放模板
            scaledTmpl = if (scale != 1.0f) {
                val scaledWidth = (template.cols() * scale).toInt()
                val scaledHeight = (template.rows() * scale).toInt()
                Mat().apply {
                    val newSize = Size(scaledWidth.toDouble(), scaledHeight.toDouble())
                    Imgproc.resize(template, this, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                }
            } else {
                template
            }
            
            // 模板匹配
            val resultCols = image.cols() - scaledTmpl.cols() + 1
            val resultRows = image.rows() - scaledTmpl.rows() + 1
            result = Mat(resultRows, resultCols, CvType.CV_32FC1)
            Imgproc.matchTemplate(image, scaledTmpl, result, Imgproc.TM_CCOEFF_NORMED)
            
            val mm = Core.minMaxLoc(result)
            mm.maxVal
        } catch (e: Exception) {
            Log.e(TAG, "matchAtScale error at scale=$scale: $e")
            Double.NEGATIVE_INFINITY
        } finally {
            result?.release()
            if (scale != 1.0f) scaledTmpl?.release()
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
            exec.shutdown()  // 优雅关闭：允许当前任务完成
            if (!exec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                exec.shutdownNow()  // 超时后强制关闭
            }
        } catch (e: InterruptedException) {
            exec.shutdownNow()
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
        }
        // 确保在executor完全停止后释放OpenCV资源
        templateGrays.forEach { it.release() }
        templateGrays = emptyList()
    }
}
