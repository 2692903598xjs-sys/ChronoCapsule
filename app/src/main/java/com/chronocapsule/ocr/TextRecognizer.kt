package com.chronocapsule.ocr

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * 文字识别：把检测框里的像素变成字符串。
 *
 * 四步：
 *   1. 裁图，左右各留 3px（DBNet 的框偏紧，不留会切掉首尾笔画）
 *   2. 等比缩放到高 48，宽度按原比例，最长 640
 *   3. 归一化 (x/255 - 0.5) / 0.5 —— 注意和检测用的 ImageNet 均值不一样
 *   4. CTC 解码：逐时间步取最大 → 去连续重复 → 去 blank → 查字典
 */
object TextRecognizer {

    private const val IMG_H = 48
    private const val MIN_W = 16
    private const val MAX_W = 640

    /** 裁图时左右上下各补的像素 */
    private const val PAD = 3

    /** 识别用的是 0.5 / 0.5，不是检测那套 ImageNet 参数 */
    private val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    private val STD = floatArrayOf(0.5f, 0.5f, 0.5f)

    fun recognizeAll(bitmap: Bitmap, boxes: List<OcrBox>): List<OcrLine> {
        val result = ArrayList<OcrLine>(boxes.size)
        for (box in boxes) {
            val line = recognize(bitmap, box)
            // 空字符串说明框里不是字（图标、装饰线），直接丢掉
            if (line.text.isNotBlank()) result.add(line)
        }
        return result
    }

    fun recognize(bitmap: Bitmap, box: OcrBox): OcrLine {
        val left = (box.left - PAD).coerceAtLeast(0)
        val top = (box.top - PAD).coerceAtLeast(0)
        val right = (box.right + PAD).coerceAtMost(bitmap.width)
        val bottom = (box.bottom + PAD).coerceAtMost(bitmap.height)

        val cropW = right - left
        val cropH = bottom - top
        if (cropW < 2 || cropH < 2) return OcrLine("", 0f, box)

        val crop = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        val inputW = (IMG_H.toFloat() * cropW / cropH).roundToInt().coerceIn(MIN_W, MAX_W)
        val resized = TensorUtils.resize(crop, inputW, IMG_H)
        val input = TensorUtils.toCHW(resized, MEAN, STD)

        if (resized !== crop) {
            resized.recycle()
            crop.recycle()
        } else {
            crop.recycle()
        }

        val output = OcrEngine.runRecognition(input, inputW, IMG_H)
        val (text, score) = ctcDecode(output)
        return OcrLine(text, score, box)
    }

    /**
     * CTC 解码。
     *
     * 模型输出的类别表是：[blank, 字典第1个字, ..., 字典第6904个字, 空格]
     * 所以 index 0 是 blank，index 1..n 对应 dict[index-1]，最后一个 index 是空格。
     */
    private fun ctcDecode(output: RecOutput): Pair<String, Float> {
        val dict = OcrEngine.dict
        val sb = StringBuilder()
        var lastIndex = -1
        var probSum = 0f
        var probCount = 0

        for (t in 0 until output.timeSteps) {
            val base = t * output.classes
            var bestIndex = 0
            var bestProb = output.data[base]
            for (c in 1 until output.classes) {
                val v = output.data[base + c]
                if (v > bestProb) {
                    bestProb = v
                    bestIndex = c
                }
            }

            // blank 和"与上一帧相同"都跳过——这是 CTC 的合并规则
            if (bestIndex != 0 && bestIndex != lastIndex) {
                sb.append(labelOf(bestIndex, dict))
                probSum += bestProb
                probCount++
            }
            lastIndex = bestIndex
        }

        val score = if (probCount == 0) 0f else probSum / probCount
        return sb.toString() to score
    }

    private fun labelOf(index: Int, dict: List<String>): String = when {
        index == 0 -> ""
        index <= dict.size -> dict[index - 1]
        else -> " "
    }
}
