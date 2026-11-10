package com.chronocapsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chronocapsule.capture.ScreenWatcherService
import com.chronocapsule.contract.ScreenContract
import com.chronocapsule.net.CloudClient
import com.chronocapsule.net.CloudConfig
import com.chronocapsule.net.PendingUploads
import com.chronocapsule.ocr.OcrBox
import com.chronocapsule.ocr.OcrEngine
import com.chronocapsule.ocr.OcrLine
import com.chronocapsule.pipeline.ScreenPipeline
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var card: TextView
    private lateinit var preview: ImageView
    private lateinit var btnPick: Button
    private lateinit var btnDefault: Button
    private lateinit var btnUpload: Button
    private lateinit var btnAuto: Button
    private lateinit var etBaseUrl: EditText
    private lateinit var btnTest: Button

    /** 端侧会话 ID，整个 App 生命周期内不变，上传时作为 session_id 带上 */
    private val sessionId: String = "sess-${UUID.randomUUID()}"

    /** 最近一次结构化出来的契约 payload，点"上传云端"时发这一份 */
    @Volatile
    private var lastPayload: ScreenContract.Payload? = null

    /** 系统相册选图。不需要任何存储权限，选完系统把 uri 回调给我们。 */
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult      // 用户取消了
        runDetection("相册图片", decodeUri(uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        status = findViewById(R.id.tvStatus)
        card = findViewById(R.id.tvCard)
        preview = findViewById(R.id.ivPreview)
        btnPick = findViewById(R.id.btnPick)
        btnDefault = findViewById(R.id.btnDefault)
        btnUpload = findViewById(R.id.btnUpload)
        btnAuto = findViewById(R.id.btnAuto)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        btnTest = findViewById(R.id.btnTest)

        // 显示已保存的地址；没配过就显示模拟器默认地址
        etBaseUrl.setText(CloudConfig.baseUrls(this).first())
        etBaseUrl.onFocusChangeListener = android.view.View.OnFocusChangeListener { _, hasFocus ->
            // 输入框失焦时把地址存下来，用户不点任何按钮也不会丢
            if (!hasFocus) saveBaseUrl()
        }

        btnTest.setOnClickListener { testConnection() }

        // 上次没传成功的屏，开机顺手补传
        Thread { PendingUploads.drain(applicationContext) }.start()

        btnPick.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        btnDefault.setOnClickListener {
            runDetection("示例图", loadAssetImage())
        }

        btnUpload.setOnClickListener {
            val payload = lastPayload ?: return@setOnClickListener
            saveBaseUrl()
            btnUpload.isEnabled = false
            Toast.makeText(this, "上传中…", Toast.LENGTH_SHORT).show()
            Thread {
                val result = CloudClient.uploadScreen(payload, CloudConfig.baseUrls(this))
                when {
                    result.httpCode == -1 -> PendingUploads.enqueue(applicationContext, payload)
                    result.ok -> PendingUploads.drain(applicationContext)
                }
                // 手动上传也走完整链路：上传只是材料，建任务才有卡片
                val cardText = if (result.ok && result.screenId != null) {
                    CloudClient.analyzeScreen(
                        screenIds = listOf(result.screenId),
                        baseUrls = CloudConfig.baseUrls(this),
                        timeoutMs = 60_000L
                    )?.toDisplayText()
                } else {
                    null
                }
                runOnUiThread {
                    btnUpload.isEnabled = true
                    val suffix = when {
                        result.httpCode == -1 -> "（已缓存，联网后自动补传）"
                        cardText != null -> "，云端卡片已生成"
                        else -> ""
                    }
                    Toast.makeText(this, result.message + suffix, Toast.LENGTH_LONG).show()
                    if (cardText != null) showCard(cardText)
                }
            }.start()
        }

        // 无障碍服务必须由用户在系统设置里手动开启，App 只能把他带到那个页面
        btnAuto.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 冷启动先跑一遍自带的示例图，保证一打开就有东西看
        runDetection("示例图", loadAssetImage())
    }

    /** 把输入框里的地址写进 SharedPreferences（自动补 http://） */
    private fun saveBaseUrl() {
        CloudConfig.setBaseUrl(this, etBaseUrl.text.toString())
    }

    /** 测试云端是否可达：GET /api/screens，3 秒超时 */
    private fun testConnection() {
        saveBaseUrl()
        val baseUrl = CloudConfig.baseUrls(this).first()
        btnTest.isEnabled = false
        Toast.makeText(this, "正在连接 $baseUrl …", Toast.LENGTH_SHORT).show()
        Thread {
            val result = CloudClient.ping(baseUrl)
            runOnUiThread {
                btnTest.isEnabled = true
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** 自动记录在后台拿到卡片后会发这条 App 内广播，界面开着就直接展示 */
    private val cardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenWatcherService.EXTRA_CARD_TEXT) ?: return
            showCard(text)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            cardReceiver,
            IntentFilter(ScreenWatcherService.ACTION_CARD_READY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(cardReceiver)
    }

    /** 把卡片标题 / 摘要 / 字段展开在状态区下面 */
    private fun showCard(text: String) {
        Log.i("ChronoUI", "显示云端卡片：${text.take(80)}")
        card.text = text
        card.visibility = android.view.View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        saveBaseUrl()
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚在系统设置里开关过服务，回来刷新一下按钮文案。
        // 必须读系统设置而不是进程内的标志位：进程被系统回收、服务还没重绑的
        // 那一瞬间，内存里的状态会和系统真实状态不一致。
        btnAuto.text = if (isAutoCaptureEnabled()) {
            "自动记录：已开启"
        } else {
            "开启自动记录（无障碍）"
        }
    }

    /** 系统启用列表里是否有我们的无障碍服务 */
    private fun isAutoCaptureEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = "$packageName/${ScreenWatcherService::class.java.name}"
        return enabled.split(':').any { it.equals(component, ignoreCase = true) }
    }

    /**
     * 完整流水线：模型初始化 → 检测 → 画框 → 更新界面。
     * 全部在后台线程执行，结果回主线程。
     */
    private fun runDetection(sourceName: String, bitmap: Bitmap) {
        btnPick.isEnabled = false
        btnDefault.isEnabled = false
        status.text = "处理中…"

        Thread {
            try {
                // 检测 → 识别 → 结构化 → 契约，全流程和自动截图走同一个入口
                val output = ScreenPipeline.process(
                    context = applicationContext,
                    bitmap = bitmap,
                    sessionId = sessionId,
                    eventId = "evt-${UUID.randomUUID()}"
                )
                val lines = output.lines
                val structured = output.structured
                val payload = output.payload
                val problems = output.problems
                val loadCost = output.timing.load
                val detCost = output.timing.detect
                val recCost = output.timing.recognize
                val structCost = output.timing.structure

                Log.i("ChronoJSON", structured.json)
                Log.i("ChronoPayload", payload.toJsonString())
                lastPayload = payload
                if (problems.isNotEmpty()) Log.w(TAG, "契约自检未通过：$problems")

                val drawn = drawLines(bitmap, lines)

                val text = buildString {
                    appendLine("来源：$sourceName（${bitmap.width}×${bitmap.height}）")
                    appendLine("加载 ${loadCost}ms ｜ 检测 ${detCost}ms ｜ 识别 ${recCost}ms ｜ 结构化 ${structCost}ms")
                    appendLine("端到端 ${detCost + recCost + structCost}ms")
                    val kind = if (structured.isGroup) {
                        "群聊 ${structured.memberCount ?: "?"} 人"
                    } else {
                        "单聊"
                    }
                    appendLine("OCR ${lines.size} 行 → $kind，消息 ${structured.messages.size} 条 → 契约 block ${payload.blocks.size} 个")
                    appendLine(if (problems.isEmpty()) "契约 ${ScreenContract.VERSION} 自检通过" else "契约自检：${problems.joinToString("；")}")
                    appendLine()
                    appendLine(payload.toJsonString().lineSequence().take(40).joinToString("\n"))
                    appendLine()
                    append("（完整 payload 见 Logcat 的 ChronoPayload）")
                }
                runOnUiThread {
                    status.text = text
                    preview.setImageBitmap(drawn)
                    btnPick.isEnabled = true
                    btnDefault.isEnabled = true
                    btnUpload.isEnabled = payload.blocks.isNotEmpty()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "检测失败", t)
                runOnUiThread {
                    status.text = "失败：${t.javaClass.simpleName}: ${t.message}"
                    btnPick.isEnabled = true
                    btnDefault.isEnabled = true
                }
            }
        }.start()
    }

    /** 从 assets 读自带的示例截图 */
    private fun loadAssetImage(): Bitmap {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return assets.open("test/settings.png").use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("示例图解码失败")
    }

    /**
     * 从相册 uri 解码，并把最长边控制在 MAX_LONG_SIDE 以内。
     *
     * inSampleSize 只能是 2 的幂，一档就是 1/2、1/4，跳得太狠会把小字直接糊掉
     * （手机截图 2400px 会被砍到 1200px，检出框从 34 个掉到 31 个）。
     * 所以分两步：先按 2 的幂粗降省内存，再精确缩放到目标尺寸。
     */
    private fun decodeUri(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longSide / (sample * 2) >= MAX_LONG_SIDE) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("图片解码失败")

        val decodedLong = maxOf(decoded.width, decoded.height)
        if (decodedLong <= MAX_LONG_SIDE) return decoded

        val scale = MAX_LONG_SIDE.toFloat() / decodedLong
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** 把识别结果画到图上：红框标位置，绿字标内容 */
    private fun drawLines(source: Bitmap, lines: List<OcrLine>): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.RED
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            style = Paint.Style.FILL
            textSize = 30f
            color = Color.GREEN
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        lines.forEach { line ->
            val b: OcrBox = line.box
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), boxPaint)
            canvas.drawText(line.text, b.left.toFloat(), (b.top - 8f).coerceAtLeast(30f), textPaint)
        }
        return copy
    }

    override fun onDestroy() {
        super.onDestroy()
        OcrEngine.release()
    }

    private companion object {
        const val TAG = "MainActivity"

        /**
         * 相册图最长边上限。手机截图 1080×2400 必须原样保留，
         * 一旦被降采样，检测框数量会明显减少（实测 34 → 31）。
         */
        const val MAX_LONG_SIDE = 2400
    }
}
