package com.chronocapsule.ocr

/**
 * 检测出来的一个文字框（原图像素坐标，轴对齐矩形）。
 *
 * 为什么不保留多边形：手机界面上的文字是水平排布的，
 * 轴对齐矩形已经够用，而且后面做 XY-cut、气泡合并全是矩形运算，简单且快。
 * 代价是极端倾斜的文字（几乎没有）框会略大。
 */
data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/** 检测 + 识别之后的最终结果，交给结构化模块 */
data class OcrLine(
    val text: String,
    val score: Float,
    val box: OcrBox
)

/** 检测模型的原始输出：概率图是 [1,1,ph,pw]，这里摊平成一维 */
class ProbMap(
    val data: FloatArray,
    val height: Int,
    val width: Int
) {
    operator fun get(y: Int, x: Int): Float = data[y * width + x]
}

/**
 * 识别模型的原始输出：[1, T, C]
 * T 是时间步数（≈ 输入宽度 / 8），C 是字符类别数（6906 = 6904 字典 + blank + 空格）
 */
class RecOutput(
    val data: FloatArray,
    val timeSteps: Int,
    val classes: Int
)
