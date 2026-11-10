package com.chronocapsule.pipeline

import android.content.Context
import android.graphics.Bitmap
import com.chronocapsule.contract.ScreenContract
import com.chronocapsule.ocr.OcrEngine
import com.chronocapsule.ocr.OcrLine
import com.chronocapsule.ocr.TextDetector
import com.chronocapsule.ocr.TextRecognizer
import com.chronocapsule.struct.ScreenStructurer
import java.time.OffsetDateTime

/**
 * 一条完整流水线的唯一入口：图片 → 检测 → 识别 → 结构化 → 契约 payload。
 *
 * 手动选图（MainActivity）和自动截图（ScreenWatcherService）都走这里，
 * 保证两条路径产出的 JSON 完全一致。
 */
object ScreenPipeline {

    data class Timing(
        val load: Long,
        val detect: Long,
        val recognize: Long,
        val structure: Long
    ) {
        val endToEnd: Long get() = detect + recognize + structure
    }

    data class Output(
        val lines: List<OcrLine>,
        val structured: ScreenStructurer.Result,
        val payload: ScreenContract.Payload,
        val problems: List<String>,
        val timing: Timing
    )

    /**
     * 必须在后台线程调用：模型加载和推理都是同步阻塞的。
     *
     * @param appPackage 自动截图时来自 AccessibilityEvent，手动选图时传 "unknown"
     */
    fun process(
        context: Context,
        bitmap: Bitmap,
        sessionId: String,
        eventId: String,
        appPackage: String = "unknown",
        appName: String = "unknown",
        capturedAt: OffsetDateTime = OffsetDateTime.now()
    ): Output {
        val t0 = System.currentTimeMillis()
        OcrEngine.init(context)
        val loadCost = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val boxes = TextDetector.detect(bitmap)
        val detCost = System.currentTimeMillis() - t1

        val t2 = System.currentTimeMillis()
        val lines = TextRecognizer.recognizeAll(bitmap, boxes)
        val recCost = System.currentTimeMillis() - t2

        val t3 = System.currentTimeMillis()
        val structured = ScreenStructurer.structure(lines, bitmap.width, bitmap.height)
        val structCost = System.currentTimeMillis() - t3

        val payload = ScreenContract.build(
            result = structured,
            width = bitmap.width,
            height = bitmap.height,
            sessionId = sessionId,
            eventId = eventId,
            capturedAt = capturedAt,
            appPackage = appPackage,
            appName = appName
        )

        return Output(
            lines = lines,
            structured = structured,
            payload = payload,
            problems = payload.validate(),
            timing = Timing(loadCost, detCost, recCost, structCost)
        )
    }
}
