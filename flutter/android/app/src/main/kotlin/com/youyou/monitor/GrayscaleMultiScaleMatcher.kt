package com.youyou.monitor

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 灰度多尺度模板匹配器
 * 
 * 特性：
 * - 灰度图像匹配 (TM_CCOEFF_NORMED)
 * - 两阶段多尺度搜索 (粗搜索 + 细搜索)
 * - 早停优化 (粗搜索分数过低时跳过细搜索)
 * - 弱匹配支持 (阈值 - 0.04)
 */
class GrayscaleMultiScaleMatcher : TemplateMatcher {
    
    private val TAG = "GrayscaleMultiScaleMatcher"
    private val config = MonitorConfig.getInstance()
    
    @Volatile
    private var templateGrays: List<Mat> = emptyList()
    
    @Volatile
    private var templateNames: List<String> = emptyList()
    
    // 性能优化：避免重复创建数组对象
    // 注意：单线程执行器保证无并发，可以安全复用
    private val coarseScales = floatArrayOf(1.0f, 0.7f, 0.5f)
    private val fineScalesHigh = floatArrayOf(0.95f, 0.9f, 0.85f)
    private val fineScalesMid = FloatArray(2)  // 动态填充，单线程安全
    private val fineScalesLow = floatArrayOf(0.55f, 0.48f, 0.45f)
    
    companion object {
        private const val WEAK_MATCH_OFFSET = 0.04
        private const val EARLY_EXIT_OFFSET = 0.20
        private const val MIN_TEMPLATE_SIZE = 30
    }
    
    override fun loadTemplates(): Pair<Int, List<String>> {
        val templateDir = config.getTemplateDir()  // 从配置获取模板目录
        
        Log.d(TAG, "Loading templates from: ${templateDir.absolutePath}")
        
        val files = templateDir.listFiles { f -> 
            f.isFile && (f.name.endsWith(".png") || f.name.endsWith(".jpg"))
        } ?: emptyArray()
        
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
            var tmp: Mat? = null
            var resized: Mat? = null
            try {
                tmp = Mat()
                Utils.bitmapToMat(bmp, tmp)
                
                // 模板也缩小到相同基准（与匹配图像一致）
                val MAX_DIMENSION = 3200
                val result = if (tmp.cols() > MAX_DIMENSION || tmp.rows() > MAX_DIMENSION) {
                    val maxDim = maxOf(tmp.cols(), tmp.rows())
                    val scale = MAX_DIMENSION.toFloat() / maxDim
                    resized = Mat()
                    val newSize = Size((tmp.cols() * scale).toDouble(), (tmp.rows() * scale).toDouble())
                    Imgproc.resize(tmp, resized, newSize, 0.0, 0.0, Imgproc.INTER_AREA)
                    Imgproc.cvtColor(resized, resized, Imgproc.COLOR_RGBA2GRAY)
                    resized
                } else {
                    Imgproc.cvtColor(tmp, tmp, Imgproc.COLOR_RGBA2GRAY)
                    tmp
                }
                
                // 成功创建后，标记为null避免finally释放
                if (result === tmp) tmp = null else resized = null
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to convert bitmap to Mat: $e")
                null
            } finally {
                // 确保异常时释放未使用的Mat
                tmp?.release()
                resized?.release()
            }
        }
        
        // 线程安全：先保存旧引用，再赋值新数据，最后释放旧资源
        // 避免 match() 线程正在使用时被释放
        val oldTemplateGrays = templateGrays
        templateGrays = newTemplateGrays
        templateNames = names
        
        // 延迟释放旧模板（新数据已生效，旧数据不会被新请求使用）
        oldTemplateGrays.forEach { it.release() }
        
        // 重要：回收所有Bitmap，防止内存泄漏
        bitmaps.forEach { it.recycle() }
        
        Log.d(TAG, "Loaded ${templateGrays.size} templates: $templateNames")
        
        return Pair(templateGrays.size, templateNames)
    }
    
    override fun match(grayMat: Mat): MatchResult? {
        // 线程安全：本地缓存引用，避免迭代过程中列表被热更新
        val templates = templateGrays
        val names = templateNames
        
        if (templates.isEmpty()) {
            Log.w(TAG, "No templates loaded")
            return null
        }
        
        val threshold = config.matchThreshold
        val weakThreshold = threshold - WEAK_MATCH_OFFSET
        
        for ((idx, tmpl) in templates.withIndex()) {
            val templateName = names.getOrNull(idx) ?: "template$idx"
            val templateStartTime = System.currentTimeMillis()
            
            // 智能多尺度匹配：两阶段策略
            var bestScore = Double.NEGATIVE_INFINITY
            var bestScale = 1.0f
            val scaleScores = mutableListOf<Pair<Float, Double>>()
            
            // 阶段1：粗搜索
            for (scale in coarseScales) {
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()
                
                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue
                
                val score = matchAtScale(tmpl, grayMat, scale)
                scaleScores.add(Pair(scale, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scale
                }
            }
            
            // 提前退出优化：如果粗搜索分数很低，跳过细搜索
            if (bestScore < threshold - EARLY_EXIT_OFFSET) {
                if (scaleScores.size == coarseScales.size) {
                    Log.d(TAG, "[$templateName] Skipped fine search (coarse best=${String.format("%.3f", bestScore)} << threshold)")
                }
                continue
            }
            
            // 阶段2：细搜索 - 在最佳尺度附近细化
            val fineScales = when {
                bestScale >= 0.9f -> fineScalesHigh
                bestScale >= 0.65f -> {
                    // 单线程环境，安全复用数组
                    fineScalesMid[0] = bestScale + 0.05f
                    fineScalesMid[1] = bestScale - 0.05f
                    fineScalesMid
                }
                else -> fineScalesLow
            }
            
            for (scale in fineScales) {
                if (scale == bestScale) continue  // 跳过已测试的
                
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()
                
                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < MIN_TEMPLATE_SIZE || scaledHeight < MIN_TEMPLATE_SIZE) continue
                
                val score = matchAtScale(tmpl, grayMat, scale)
                scaleScores.add(Pair(scale, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scale
                }
            }
            
            val templateElapsed = System.currentTimeMillis() - templateStartTime
            
            // 调试输出
            if (bestScore > threshold - 0.10 && scaleScores.isNotEmpty()) {
                val scoresStr = scaleScores.sortedByDescending { it.second }
                    .take(5)
                    .joinToString(", ") { "${String.format("%.2f", it.first)}=${String.format("%.3f", it.second)}" }
                Log.d(TAG, "[$templateName] ${scaleScores.size} scales in ${templateElapsed}ms, best: [$scoresStr]")
            }
            
            // 匹配判断
            if (bestScore >= threshold) {
                Log.i(TAG, "✓ Matched: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold)")
                return MatchResult(
                    templateName = templateName,
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = false
                )
            } else if (bestScore >= weakThreshold) {
                Log.i(TAG, "⚠ Weak match: $templateName (score=$bestScore, scale=${String.format("%.2f", bestScale)}, threshold=$threshold, diff=${String.format("%.3f", threshold - bestScore)})")
                return MatchResult(
                    templateName = "weak_$templateName",
                    score = bestScore,
                    scale = bestScale,
                    timeMs = templateElapsed,
                    isWeak = true
                )
            }
        }
        
        // 无匹配
        Log.d(TAG, "✗ No match (threshold=$threshold)")
        return null
    }
    
    override fun reloadTemplates() {
        loadTemplates()
    }
    
    override fun release() {
        templateGrays.forEach { it.release() }
        templateGrays = emptyList()
        templateNames = emptyList()
        Log.d(TAG, "Released all templates")
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
}
