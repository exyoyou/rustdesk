package com.youyou.monitor

import org.opencv.core.Mat
import java.io.File

/**
 * 模板匹配器接口
 * 
 * 支持多种匹配算法实现：
 * - 灰度多尺度匹配 (GrayscaleMultiScaleMatcher)
 * - 边缘检测匹配 (EdgeBasedMatcher)
 * - 特征点匹配等
 */
interface TemplateMatcher {
    /**
     * 加载模板文件
     * @return Pair<模板数量, 模板名称列表>
     * @note 模板目录从 MonitorConfig.getTemplateDir() 获取
     */
    fun loadTemplates(): Pair<Int, List<String>>
    
    /**
     * 执行模板匹配
     * @param grayMat 灰度图像 (必须是单通道)
     * @return 匹配结果，未匹配则返回 null
     * @note 匹配阈值从 MonitorConfig.matchThreshold 获取
     */
    fun match(grayMat: Mat): MatchResult?
    
    /**
     * 重新加载模板（热更新）
     */
    fun reloadTemplates()
    
    /**
     * 释放所有资源
     */
    fun release()
}

/**
 * 模板匹配结果
 * 
 * @property templateName 匹配的模板名称
 * @property score 匹配分数 (0.0-1.0)
 * @property scale 匹配时的缩放比例
 * @property timeMs 匹配耗时 (毫秒)
 * @property isWeak 是否为弱匹配 (分数接近但未达到阈值)
 */
data class MatchResult(
    val templateName: String,
    val score: Double,
    val scale: Float,
    val timeMs: Long,
    val isWeak: Boolean = false
)
