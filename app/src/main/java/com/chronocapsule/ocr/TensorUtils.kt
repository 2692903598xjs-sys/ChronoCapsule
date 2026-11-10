package com.chronocapsule.ocr

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * Bitmap → 模型输入的转换工具。
 *
 * PaddleOCR 训练时用的是 OpenCV：
 *   cv2.imread 读进来是 BGR（不是 RGB）
 *   NormalizeImage(order='hwc') 逐通道减均值除方差
 *   ToCHWImage 把 HWC 转成 CHW
 * 这三步必须一模一样，否则精度会明显下降——模型只认它训练时见过的分布。
 */
object TensorUtils {

    /** 双线性缩放，等价于 cv2.resize(INTER_LINEAR) */
    fun resize(src: Bitmap, width: Int, height: Int): Bitmap =
        if (src.width == width && src.height == height) src
        else Bitmap.createScaledBitmap(src, width, height, true)

    /**
     * 把 Bitmap 转成 CHW 排布的归一化 FloatArray。
     * @param mean 按 BGR 顺序给出的均值
     * @param std  按 BGR 顺序给出的标准差
     */
    fun toCHW(bitmap: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // 注意通道顺序：0=B 1=G 2=R
            out[i] = (b / 255f - mean[0]) / std[0]
            out[plane + i] = (g / 255f - mean[1]) / std[1]
            out[2 * plane + i] = (r / 255f - mean[2]) / std[2]
        }
        return out
    }

    /** 缩放到 32 的整数倍：DBNet 是 4 倍下采样，输入尺寸必须是 32 的倍数才能保证特征对齐 */
    fun alignTo32(v: Int): Int = ((v + 16) / 32 * 32).coerceAtLeast(32)

    fun roundTo32(v: Float): Int = ((v / 32f).roundToInt() * 32).coerceAtLeast(32)
}
