package com.chronocapsule.ocr

import android.graphics.Bitmap
import android.util.Log
import kotlin.math.min

/**
 * DBNet 文字检测：输入一张 Bitmap，输出一堆文字框。
 *
 * 流程（参数全部来自模型自带的 inference.yml，不能随便改）：
 *   1. 缩放：短边缩到 736（DetResizeForTest 默认 limit_type='min'）
 *   2. 归一化：BGR /255，减 ImageNet 均值除标准差
 *   3. 推理：输出 1/4 分辨率的"这个像素是不是文字中心"概率图
 *   4. 二值化：概率 > 0.2 认为是文字
 *   5. 连通域：把连成一片的像素聚成一个候选框
 *   6. 过滤：整框平均概率 < 0.4 的丢掉；太小的丢掉
 *   7. unclip：DB 算法输出的区域比真实文字要收缩一圈，按 1.4 的比例外扩补回来
 */
object TextDetector {

    private const val TAG = "TextDetector"

    /** 短边目标尺寸。736 是 v6 的默认值，不是 v5 的 960 */
    private const val LIMIT_SHORT_SIDE = 736

    private const val DET_THRESH = 0.2f     // 二值化阈值
    private const val BOX_THRESH = 0.4f     // 整框平均置信度阈值
    private const val UNCLIP_RATIO = 1.4f   // 外扩比例
    private const val MIN_SIZE = 3          // 概率图上的最小边长

    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)  // B,G,R
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** 最近一次检测的预处理尺寸，方便调试 */
    var lastInputWidth = 0
        private set
    var lastInputHeight = 0
        private set

    fun detect(bitmap: Bitmap): List<OcrBox> {
        val w0 = bitmap.width
        val h0 = bitmap.height

        // ---- 1. 计算缩放尺寸 ----
        val shortSide = min(w0, h0)
        // 只在短边超过 736 时缩小；比 736 小就保持原样
        val ratio = if (shortSide > LIMIT_SHORT_SIDE) LIMIT_SHORT_SIDE.toFloat() / shortSide else 1f
        val inW = TensorUtils.roundTo32(w0 * ratio)
        val inH = TensorUtils.roundTo32(h0 * ratio)
        lastInputWidth = inW
        lastInputHeight = inH

        // ---- 2. 预处理 ----
        val scaled = TensorUtils.resize(bitmap, inW, inH)
        val input = TensorUtils.toCHW(scaled, MEAN, STD)
        if (scaled !== bitmap) scaled.recycle()

        // ---- 3. 推理 ----
        val prob = OcrEngine.runDetection(input, inW, inH)
        val ph = prob.height
        val pw = prob.width

        // ---- 4~7. 后处理 ----
        val boxes = postProcess(prob, w0, h0)
        Log.i(TAG, "输入 ${inW}x${inH}，概率图 ${pw}x${ph}，候选框 ${boxes.size} 个")
        return boxes
    }

    private fun postProcess(prob: ProbMap, origW: Int, origH: Int): List<OcrBox> {
        val pw = prob.width
        val ph = prob.height
        val total = pw * ph

        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val result = ArrayList<OcrBox>(64)

        // 概率图坐标 → 原图坐标的比例（概率图是 4 倍下采样，且输入图被缩放过）
        val sx = origW.toFloat() / pw
        val sy = origH.toFloat() / ph

        for (start in 0 until total) {
            if (visited[start] || prob.data[start] <= DET_THRESH) continue

            // 广度优先，把一个连通的区域整个吞掉
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true

            var minX = Int.MAX_VALUE
            var maxX = -1
            var minY = Int.MAX_VALUE
            var maxY = -1
            var sum = 0f
            var count = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % pw
                val y = idx / pw

                sum += prob.data[idx]
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                // 8 邻域
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= ph) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= pw) continue
                        val ni = ny * pw + nx
                        if (!visited[ni] && prob.data[ni] > DET_THRESH) {
                            visited[ni] = true
                            queue[tail++] = ni
                        }
                    }
                }
            }

            // 整块的平均置信度太低 → 是噪声
            val score = sum / count
            if (score < BOX_THRESH) continue

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (bw < MIN_SIZE && bh < MIN_SIZE) continue

            // unclip：DB 输出的框是收缩过的，按 面积*比例/周长 外扩
            val offset = (bw * bh * UNCLIP_RATIO) / (2f * (bw + bh))
            val l = (minX - offset) * sx
            val t = (minY - offset) * sy
            val r = (maxX + 1 + offset) * sx
            val b = (maxY + 1 + offset) * sy

            val left = l.toInt().coerceIn(0, origW - 1)
            val top = t.toInt().coerceIn(0, origH - 1)
            val right = r.toInt().coerceIn(left + 1, origW)
            val bottom = b.toInt().coerceIn(top + 1, origH)

            // 原图上小于 4px 的不要，识别也没意义
            if (right - left < 4 || bottom - top < 4) continue

            result.add(OcrBox(left, top, right, bottom))
        }

        // 按阅读顺序排：先上下，再左右
        result.sortWith(compareBy<OcrBox> { it.top }.thenBy { it.left })
        return result
    }
}
