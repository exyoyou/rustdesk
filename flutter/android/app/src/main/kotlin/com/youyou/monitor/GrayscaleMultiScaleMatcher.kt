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
        
        Log.d(TAG, "Loaded ${templateGrays.size} templates: $templateNames")
        
        return Pair(templateGrays.size, templateNames)
    }
    
    override fun match(grayMat: Mat): MatchResult? {
        if (templateGrays.isEmpty()) {
            Log.w(TAG, "No templates loaded")
            return null
        }
        
        val threshold = config.matchThreshold  // 从配置获取阈值
        val weakThreshold = threshold - 0.04  // 弱匹配阈值
        
        for ((idx, tmpl) in templateGrays.withIndex()) {
            val templateName = templateNames.getOrNull(idx) ?: "template$idx"
            val templateStartTime = System.currentTimeMillis()
            
            // 智能多尺度匹配：两阶段策略
            // 阶段1：粗搜索 - 快速定位大致范围 (3个点)
            // 阶段2：细搜索 - 在最佳点附近细化 (2-3个点)
            val coarseScales = floatArrayOf(1.0f, 0.7f, 0.5f)
            var bestScore = Double.NEGATIVE_INFINITY
            var bestScale = 1.0f
            val scaleScores = mutableListOf<Pair<Float, Double>>()
            
            // 阶段1：粗搜索
            for (scale in coarseScales) {
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()
                
                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < 30 || scaledHeight < 30) continue
                
                val score = matchAtScale(tmpl, grayMat, scale)
                scaleScores.add(Pair(scale, score))
                if (score > bestScore) {
                    bestScore = score
                    bestScale = scale
                }
            }
            
            // 提前退出优化：如果粗搜索分数很低，跳过细搜索
            if (bestScore < threshold - 0.20) {
                if (scaleScores.size == 3) {
                    Log.d(TAG, "[$templateName] Skipped fine search (coarse best=${String.format("%.3f", bestScore)} << threshold)")
                }
                continue  // 跳到下一个模板
            }
            
            // 阶段2：细搜索 - 在最佳尺度附近细化
            val fineScales = when {
                bestScale >= 0.9f -> floatArrayOf(0.95f, 0.9f, 0.85f)
                bestScale >= 0.65f -> floatArrayOf(bestScale + 0.05f, bestScale - 0.05f)
                else -> floatArrayOf(0.55f, 0.48f, 0.45f)
            }.filter { it != bestScale }
            
            for (scale in fineScales) {
                val scaledWidth = (tmpl.cols() * scale).toInt()
                val scaledHeight = (tmpl.rows() * scale).toInt()
                
                if (scaledWidth > grayMat.cols() || scaledHeight > grayMat.rows()) continue
                if (scaledWidth < 30 || scaledHeight < 30) continue
                
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
        
        // 无匹配，记录最高分数用于调试
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
