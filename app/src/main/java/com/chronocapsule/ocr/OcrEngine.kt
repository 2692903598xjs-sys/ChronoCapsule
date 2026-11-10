package com.chronocapsule.ocr

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * 端侧 OCR 引擎。
 *
 * 模型来自 PP-OCRv6 tiny（官方 HuggingFace ONNX 仓库，Apache-2.0）：
 *   det —— DBNet 文字检测，输入整张图，输出"每个像素是不是文字"的概率图
 *   rec —— LightSVTR 文字识别，输入单个文字框，输出字符序列
 *
 * 这个类只负责"喂张量、收张量"，所有图像处理都在 TextDetector / TextRecognizer 里。
 */
object OcrEngine {

    private const val TAG = "OcrEngine"

    // PP-OCRv6 tiny：det 1.7MB + rec 4.3MB，2026 年 6 月发布的端侧档位
    private const val DET_MODEL = "models/ch_PP-OCRv6_tiny_det.onnx"
    private const val REC_MODEL = "models/ch_PP-OCRv6_tiny_rec.onnx"
    private const val DICT_FILE = "models/ppocr_keys_v6.txt"

    @Volatile
    var isReady: Boolean = false
        private set

    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null

    /** 6904 个字符的字典，CTC 解码时用：下标 0 是 CTC 的 blank */
    var dict: List<String> = emptyList()
        private set

    /** 初始化：加载两个模型 + 字典。耗时操作，必须放在后台线程。 */
    @Synchronized
    fun init(context: Context) {
        if (isReady) return
        val t0 = System.currentTimeMillis()

        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)                          // 手机上 2~4 线程最划算
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        val detBytes = context.assets.open(DET_MODEL).use { it.readBytes() }
        val recBytes = context.assets.open(REC_MODEL).use { it.readBytes() }

        det = environment.createSession(detBytes, options)
        rec = environment.createSession(recBytes, options)
        dict = context.assets.open(DICT_FILE).use { input ->
            input.bufferedReader(Charsets.UTF_8).readLines()
                .map { it.trimEnd('\r', '\n') }              // 去掉 Windows 换行残留
                .filter { it.isNotEmpty() }
        }

        env = environment
        isReady = true

        val cost = System.currentTimeMillis() - t0
        Log.i(TAG, "模型加载完成，耗时 ${cost}ms，字典 ${dict.size} 行")
        Log.i(TAG, describe())
    }

    /**
     * 跑一次检测。input 是已经归一化好的 CHW 张量（长度 3*h*w）。
     * 返回 1/4 分辨率的概率图。
     */
    @Synchronized
    fun runDetection(input: FloatArray, width: Int, height: Int): ProbMap {
        val environment = env ?: error("OcrEngine 还没 init 就调用了 runDetection")
        val session = det ?: error("检测模型未加载")

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val outShape = out.info.shape
                val ph = outShape[2].toInt()
                val pw = outShape[3].toInt()
                val buffer = out.floatBuffer
                val data = FloatArray(ph * pw)
                buffer.get(data, 0, data.size)
                return ProbMap(data, ph, pw)
            }
        }
    }

    /**
     * 跑一次识别。input 是单个文字框裁图后的 CHW 张量（高固定 48，宽可变）。
     * 返回未解码的字符概率矩阵，交给 TextRecognizer 做 CTC 解码。
     */
    @Synchronized
    fun runRecognition(input: FloatArray, width: Int, height: Int): RecOutput {
        val environment = env ?: error("OcrEngine 还没 init 就调用了 runRecognition")
        val session = rec ?: error("识别模型未加载")

        val shape = longArrayOf(1, 3, height.toLong(), width.toLong())
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = result.get(0) as OnnxTensor
                val outShape = out.info.shape
                val t = outShape[1].toInt()
                val c = outShape[2].toInt()
                val buffer = out.floatBuffer
                val data = FloatArray(t * c)
                buffer.get(data, 0, data.size)
                return RecOutput(data, t, c)
            }
        }
    }

    /** 返回两个模型的输入/输出信息，用于验证模型结构是否符合预期。 */
    fun describe(): String = buildString {
        appendLine("det（检测）")
        appendLine(infoOf(det))
        appendLine()
        appendLine("rec（识别）")
        appendLine(infoOf(rec))
        appendLine()
        appendLine("字典：${dict.size} 个字符，首字符=「${dict.firstOrNull().orEmpty()}」")
    }

    private fun infoOf(session: OrtSession?): String {
        val s = session ?: return "  （未加载）"
        return buildString {
            s.inputInfo.entries.forEach { (name, info) ->
                appendLine("  IN  $name : ${shapeOf(info)}")
            }
            s.outputInfo.entries.forEach { (name, info) ->
                appendLine("  OUT $name : ${shapeOf(info)}")
            }
        }
    }

    /** ONNX 里的动态维度会显示成 -1 */
    private fun shapeOf(node: NodeInfo): String {
        val tensor = node.info as? TensorInfo ?: return node.info.toString()
        return "${tensor.type} [${tensor.shape.joinToString(", ")}]"
    }

    /** 释放原生资源，App 退出时可调用 */
    @Synchronized
    fun release() {
        det?.close()
        rec?.close()
        det = null
        rec = null
        isReady = false
    }
}
