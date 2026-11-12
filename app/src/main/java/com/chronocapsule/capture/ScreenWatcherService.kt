package com.chronocapsule.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.chronocapsule.contract.ScreenContract
import com.chronocapsule.dedup.CrossScreenDeduper
import com.chronocapsule.net.CloudClient
import com.chronocapsule.net.CloudConfig
import com.chronocapsule.net.PendingUploads
import com.chronocapsule.pipeline.ScreenPipeline
import com.chronocapsule.util.EventTrace
import java.io.File
import java.util.UUID

/**
 * 自动截图：换成聊天 App 时抓一屏，走完整流水线后自动上传。
 *
 * 为什么用无障碍服务而不是 MediaProjection：
 *   · 不需要每次采集都弹系统确认框，开一次长期有效
 *   · 同一个服务还能拿到前台包名，正好补上契约里必填的 app_package
 *   · 抓不到 FLAG_SECURE 的界面（银行、密码框），天然不会把敏感页传出去
 *
 * 抓图时机：窗口事件到达后不立刻抓，等界面稳定 SETTLE_MS 再抓——
 *   切换动画期间截到的常常是半张旧页面或空白会话页，OCR 出来 0 条消息。
 *   事件期间只保留"最新一屏"：连点三个会话，最终抓的是最后那个。
 *
 * 频率限制（三道闸，防止真机上被高频窗口事件拖垮）：
 *   1. 1.5 秒最小间隔：系统建屏硬限是 1 秒 1 张，再低就会拿到重复帧
 *   2. 每分钟上限：60 秒内最多 20 张，超了丢弃
 *   3. busy 锁 + 20 秒看门狗：上一张没处理完先记下最新一屏，处理完补抓；
 *      卡住超过 20 秒强制复位
 * 锁屏 / 息屏时不抓（抓到的也是黑屏或锁屏页）。
 * 重复内容由 CrossScreenDeduper 按消息级别过滤，只上传新增消息。
 */
class ScreenWatcherService : AccessibilityService() {

    private val sessionId = "sess-${UUID.randomUUID()}"

    @Volatile
    private var lastCaptureAt = 0L

    /** 一张图还没处理完就不再抓新的 */
    @Volatile
    private var busy = false

    /** 已经有一个任务在等云端卡片，别为下一屏再建任务（云端本来就是串行处理） */
    @Volatile
    private var taskInFlight = false

    /** 每次开始抓图的时刻，看门狗用来判断 busy 卡了多久 */
    @Volatile
    private var busySince = 0L

    /** 最近一分钟内的抓图时刻，用来限制总频率 */
    private val recentCaptures = ArrayDeque<Long>()

    private val handler = Handler(Looper.getMainLooper())

    /** 等待界面稳定后再截的那一屏，只保留最新一次 */
    private var pendingPkg: String? = null
    private var pendingAppName: String? = null

    private val pendingCapture = Runnable { capturePending() }

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /** 去重历史的落盘位置：App 被杀后重启也能接着去重 */
    private val dedupeFile by lazy { File(filesDir, "dedupe_history.txt") }

    override fun onServiceConnected() {
        super.onServiceConnected()
        EventTrace.log(applicationContext, TAG, "无障碍服务已连接，开始监听聊天应用")

        // 读历史、补传离线队列都要碰磁盘和网络，别占主线程
        Thread {
            CrossScreenDeduper.loadFrom(dedupeFile)
            PendingUploads.drain(applicationContext)
        }.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val rawPkg = e.packageName?.toString() ?: return
        val className = e.className?.toString().orEmpty()
        // 真机上 logcat 常常读不到，先落盘再判断，才能看到事件到底有没有来
        EventTrace.log(applicationContext, TAG, "事件 type=${e.eventType} $rawPkg / $className")

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 只认聊天 App 自己的窗口事件。输入法弹出时事件包名是输入法，
        // 拿"最近一次聊天 App"去猜键盘底下是谁，实测会把键盘按键当成聊天内容
        // 传上云（整屏 36 条里 35 条是 Gboard 的键），比漏一屏更糟。
        val appName = CHAT_PACKAGES[rawPkg] ?: return
        if (!isChatWindow(className)) {
            EventTrace.log(applicationContext, TAG, "非聊天页面，跳过：$className")
            return
        }
        EventTrace.log(applicationContext, TAG, "命中聊天窗口：$appName / $className")

        // 不立刻抓：等动画结束、页面渲染完再抓，否则经常截到空白会话页
        pendingPkg = rawPkg
        pendingAppName = appName
        handler.removeCallbacks(pendingCapture)
        handler.postDelayed(pendingCapture, SETTLE_MS)
    }

    /**
     * 同一个 App 里不全是聊天：微信的网页、图片查看器，QQ 的相册/空间/支付页
     * 都会发同样的窗口事件。这些页面抓下来会把网页内容当成聊天消息上传，
     * 真机实测一屏相册能出 60 个垃圾块。按类名挡掉，宁可少抓一屏。
     */
    private fun isChatWindow(className: String): Boolean {
        if (className.isEmpty()) return true
        val lower = className.lowercase()
        return BLOCKED_WINDOW_CLASSES.none { lower.contains(it) }
    }

    /** 界面已稳定，真正执行截图前的最后一轮检查 */
    private fun capturePending() {
        val pkg = pendingPkg ?: return
        val appName = pendingAppName ?: return
        val now = SystemClock.elapsedRealtime()

        if (busy) {
            // 上一张还在流水线里。不要丢：pending 还留着，process 结束时会补抓
            if (now - busySince < BUSY_TIMEOUT_MS) {
                EventTrace.log(applicationContext, TAG, "上一张还在处理，稍后补抓")
                return
            }
            EventTrace.log(applicationContext, TAG, "上一张处理超过 ${BUSY_TIMEOUT_MS / 1000} 秒未结束，强制复位")
            busy = false
        }
        if (!screenReady()) {
            EventTrace.log(applicationContext, TAG, "息屏或锁屏，跳过")
            clearPending()
            return
        }
        if (now - lastCaptureAt < MIN_INTERVAL_MS) {
            // 还没冷却完，按剩余时间再排一次（期间有新事件会覆盖成更新的那一屏）
            handler.removeCallbacks(pendingCapture)
            handler.postDelayed(pendingCapture, MIN_INTERVAL_MS - (now - lastCaptureAt))
            return
        }
        if (!allowByRateLimit(now)) {
            clearPending()
            return
        }

        clearPending()
        lastCaptureAt = now
        EventTrace.log(applicationContext, TAG, "开始截图：$appName")
        capture(pkg, appName)
    }

    private fun clearPending() {
        pendingPkg = null
        pendingAppName = null
        handler.removeCallbacks(pendingCapture)
    }

    /** 息屏、锁屏时不抓：拿到的是黑屏或锁屏页，没有结构化价值，白耗电 */
    private fun screenReady(): Boolean {
        if (powerManager?.isInteractive != true) return false
        return keyguard?.isKeyguardLocked != true
    }

    /** 滑动窗口限流：一分钟最多 MAX_CAPTURES_PER_MINUTE 张 */
    private fun allowByRateLimit(now: Long): Boolean {
        while (recentCaptures.isNotEmpty() && now - recentCaptures.first() >= RATE_WINDOW_MS) {
            recentCaptures.removeFirst()
        }
        if (recentCaptures.size >= MAX_CAPTURES_PER_MINUTE) {
            Log.w(TAG, "一分钟内已抓 ${recentCaptures.size} 张，丢弃本次事件")
            return false
        }
        recentCaptures.addLast(now)
        return true
    }

    private fun capture(pkg: String, appName: String) {
        busy = true
        busySince = SystemClock.elapsedRealtime()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    // wrapHardwareBuffer 得到的是 HARDWARE 位图，必须 copy 成软件位图才能读像素
                    val software = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    buffer.close()

                    if (software == null) {
                        EventTrace.log(applicationContext, TAG, "截图转换失败")
                        busy = false
                        return
                    }
                    EventTrace.log(applicationContext, TAG, "截图成功 ${software.width}×${software.height}")
                    Thread { process(software, pkg, appName) }.start()
                }

                override fun onFailure(errorCode: Int) {
                    // 1 = 服务没被授予截图能力；2 = 被系统限流
                    EventTrace.log(applicationContext, TAG, "截图失败 code=$errorCode")
                    busy = false
                }
            }
        )
    }

    private fun process(bitmap: Bitmap, pkg: String, appName: String) {
        try {
            val output = ScreenPipeline.process(
                context = applicationContext,
                bitmap = bitmap,
                sessionId = sessionId,
                eventId = "evt-${UUID.randomUUID()}",
                appPackage = pkg,
                appName = appName
            )
            Log.i(
                TAG,
                "截图 ${bitmap.width}×${bitmap.height}，OCR ${output.lines.size} 行，" +
                    "消息 ${output.structured.messages.size} 条，耗时 ${output.timing.endToEnd}ms"
            )
            Log.i("ChronoPayload", output.payload.toJsonString())

            EventTrace.log(
                applicationContext, TAG,
                "OCR ${output.lines.size} 行，消息 ${output.structured.messages.size} 条，耗时 ${output.timing.endToEnd}ms"
            )

            if (output.payload.blocks.isEmpty()) {
                EventTrace.log(applicationContext, TAG, "没有识别出文字，跳过上传")
                return
            }

            // 跨屏去重：整屏 18 条里通常只有 1 条是新消息，只传新的那几条
            val deduped = CrossScreenDeduper.filter(
                blocks = output.payload.blocks,
                appPackage = output.payload.appPackage,
                pageTitle = output.payload.pageTitle
            )
            if (deduped.blocks.isEmpty()) {
                EventTrace.log(applicationContext, TAG, "没有新消息（整屏 ${output.payload.blocks.size} 条都已上传过），跳过上传")
                return
            }
            if (deduped.skipped > 0) {
                Log.i(
                    TAG,
                    "跨屏去重：整屏 ${output.payload.blocks.size} 条 → 新增 ${deduped.blocks.size} 条，" +
                        "跳过重复 ${deduped.skipped} 条"
                )
            }
            // 每屏更新后立刻落盘，进程随时可能被系统回收
            CrossScreenDeduper.saveTo(dedupeFile)

            val toUpload = output.payload.copy(blocks = ScreenContract.renumber(deduped.blocks))
            Log.i("ChronoPayload", toUpload.toJsonString())

            val result = CloudClient.uploadScreen(
                toUpload,
                CloudConfig.baseUrls(applicationContext)
            )
            EventTrace.log(applicationContext, TAG, "自动上传：${result.message}")

            when {
                // 网络不可达：落盘，等下次连上再补
                result.httpCode == -1 -> PendingUploads.enqueue(applicationContext, toUpload)
                // 上传成功说明网络恢复了，顺手把之前积压的补传掉
                result.ok -> {
                    PendingUploads.drain(applicationContext)
                    // 屏只是原料，再建任务云端才会产出卡片；放后台线程，不拖慢下一屏
                    submitTask(result.screenId)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "自动处理失败", t)
        } finally {
            bitmap.recycle()
            busy = false
            // 处理期间用户可能又切到了别的会话：把最新的那一屏补抓上（回到主线程操作 handler）
            handler.post {
                if (pendingPkg != null) {
                    handler.removeCallbacks(pendingCapture)
                    handler.postDelayed(pendingCapture, 0L)
                }
            }
        }
    }

    /**
     * 上传成功后在后台建任务、等卡片。
     *
     * 同一时间只跑一个：云端任务本来就排队处理，端侧堆多个线程只会重复等。
     * 卡片拿到后发 App 内广播，界面开着就能直接看到。
     */
    private fun submitTask(screenId: String?) {
        if (screenId == null) return
        if (taskInFlight) {
            EventTrace.log(applicationContext, TAG, "上一个任务还在等卡片，本屏不重复建任务")
            return
        }
        taskInFlight = true
        Thread {
            try {
                val card = CloudClient.analyzeScreen(
                    screenIds = listOf(screenId),
                    baseUrls = CloudConfig.baseUrls(applicationContext)
                ) ?: return@Thread
                // 落盘只记任务和字段数，聊天原文不进本地日志（Logcat 里仍可看到全文）
                Log.i("ChronoCard", card.toDisplayText())
                EventTrace.log(
                    applicationContext, "ChronoCard",
                    "${card.taskId}「${card.title}」${card.fields.size} 个字段"
                )
                sendBroadcast(
                    Intent(ACTION_CARD_READY)
                        .setPackage(packageName)
                        .putExtra(EXTRA_CARD_TEXT, card.toDisplayText())
                        .putExtra(EXTRA_CARD_TASK, card.taskId)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "建任务或取卡片失败", t)
            } finally {
                taskInFlight = false
            }
        }.start()
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        Log.w(TAG, "无障碍服务已断开")
        clearPending()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenWatcher"

        /** 云端卡片产出后发给界面的 App 内广播（只发给本应用） */
        const val ACTION_CARD_READY = "com.chronocapsule.CARD_READY"
        const val EXTRA_CARD_TEXT = "card_text"
        const val EXTRA_CARD_TASK = "card_task_id"

        /** 只在这些应用里自动记录。加新应用只需要往这张表里加一行 */
        private val CHAT_PACKAGES = mapOf(
            "com.tencent.mm" to "微信",
            "com.tencent.mobileqq" to "QQ"
        )

        /** 窗口事件后等这么久再抓，避开切换动画，保证拿到渲染完的页面 */
        private const val SETTLE_MS = 350L

        /**
         * 类名里带这些片段的窗口不是聊天页：
         * webview 网页、gallery 图片查看、相册、系统对话框、QQ 空间/插件页。
         * 小写比较，微信/QQ 的类名大小写不统一。
         */
        private val BLOCKED_WINDOW_CLASSES = listOf(
            "webview", "gallery", "winkhome", "qbasealbum",
            "dialog", "imageviewer", "browser",
            // 公众号文章是 Flutter 页面，类名里没有 webview
            "brandservice", "flutter", "plugin."
        )

        /**
         * 两次采集的最小间隔。系统建屏硬限 1 秒 1 张，这里留 1.5 倍余量；
         * 单次流水线本身就要 0.3~1 秒，再密也处理不过来。
         */
        private const val MIN_INTERVAL_MS = 1_500L

        /** 一分钟内最多抓几张，防止微信高频弹窗把 CPU 吃满、发热耗电 */
        private const val MAX_CAPTURES_PER_MINUTE = 20
        private const val RATE_WINDOW_MS = 60_000L

        /** busy 超过这个时长仍未复位，判定为卡死，强制放行下一次抓图 */
        private const val BUSY_TIMEOUT_MS = 20_000L
    }
}
