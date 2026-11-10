package com.chronocapsule.net

import android.util.Log
import com.chronocapsule.contract.ScreenContract
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 云端上传客户端（Cloud Agent v0.4）。
 *
 * 只用 HttpURLConnection，不引 OkHttp —— 整个端侧只发一个 POST，
 * 多一个依赖就多一个离线构建失败的分支。
 *
 * 接口：POST /api/screens，成功返回 201 + {"screen":{...},"replayed":false}
 * 失败语义（和契约一致）：
 *   409 相同 event_id 但内容不同
 *   422 Schema 不满足
 *   401/403 鉴权问题
 */
object CloudClient {

    private const val TAG = "CloudClient"

    /**
     * 候选地址，按顺序试，第一个连上的就用。
     *
     *   10.0.2.2    模拟器访问宿主机回环的专用地址（部分机器上 NAT 不通，实测这台就是）
     *   127.0.0.1   配合 `adb reverse tcp:8765 tcp:8765`，走 adb 通道，最稳
     *   局域网 IP    真机调试时改成电脑的地址，比如 http://192.168.1.5:8765
     */
    val DEFAULT_BASE_URLS = listOf(
        "http://10.0.2.2:8765",
        "http://127.0.0.1:8765"
    )

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 测试连接用短超时：用户点一下按钮，不该等 5 秒才知道连不上 */
    private const val PING_TIMEOUT_MS = 3_000

    data class UploadResult(
        val ok: Boolean,
        val httpCode: Int,
        val screenId: String?,
        val replayed: Boolean,
        val message: String
    )

    data class PingResult(val ok: Boolean, val message: String)

    /** 云端返回的卡片（Cloud Agent 的 Card 模型） */
    data class CardField(val label: String, val value: String)

    data class TaskCard(
        val taskId: String,
        val status: String,
        val title: String,
        val summary: String,
        val fields: List<CardField>,
        val revision: Int
    ) {
        /** 给界面 / 日志用的一行一文展示 */
        fun toDisplayText(): String = buildString {
            append("云端卡片：").append(title)
            if (summary.isNotBlank()) append('\n').append(summary)
            for (field in fields) {
                append('\n').append("• ").append(field.label).append('：').append(field.value)
            }
        }
    }

    data class TaskResult(
        val ok: Boolean,
        val httpCode: Int,
        val taskId: String?,
        val replayed: Boolean,
        val message: String,
        val baseUrl: String? = null
    )

    /**
     * 测试连接：GET /api/screens（云端已有的列表接口）。
     *
     * 只判断"这个地址上确实跑着 Cloud Agent"，不校验业务字段。
     * 返回 2xx 即认为通，并把已有的屏数带回来，方便队友确认是不是同一个库。
     */
    fun ping(baseUrl: String, apiKey: String? = null): PingResult {
        val url = "${baseUrl.trimEnd('/')}/api/screens"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PING_TIMEOUT_MS
                readTimeout = PING_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code in 200..299) {
                val count = runCatching {
                    JSONObject(text).optJSONArray("screens")?.length()
                }.getOrNull()
                PingResult(true, "连接成功（HTTP $code，云端已有 ${count ?: "?"} 屏）")
            } else {
                PingResult(false, "HTTP $code：${text.take(200)}")
            }
        } catch (e: IOException) {
            PingResult(false, "${e.javaClass.simpleName}: ${e.message}（地址 $baseUrl）")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 上传一屏。必须在后台线程调用（会阻塞）。
     *
     * 会按 [baseUrls] 顺序逐个尝试，任何一个连上就返回；
     * 全部失败时返回最后一个错误。
     *
     * @param eventId 重试时必须复用同一个 event_id，服务端靠它做幂等
     */
    fun uploadScreen(
        payload: ScreenContract.Payload,
        baseUrls: List<String> = DEFAULT_BASE_URLS,
        apiKey: String? = null
    ): UploadResult {
        var last: UploadResult? = null
        for (baseUrl in baseUrls) {
            val result = uploadOnce(payload, baseUrl, apiKey)
            // 连不上（httpCode == -1）才换下一个地址；服务端已经答复的错误直接返回
            if (result.ok || result.httpCode != -1) return result
            last = result
        }
        return last ?: UploadResult(false, -1, null, false, "没有可用的云端地址")
    }

    private fun uploadOnce(
        payload: ScreenContract.Payload,
        baseUrl: String,
        apiKey: String?
    ): UploadResult {
        val url = URL("${baseUrl.trimEnd('/')}/api/screens")
        val body = payload.toJsonString().toByteArray(StandardCharsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                setFixedLengthStreamingMode(body.size)
            }

            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val text = readBody(conn, code)
            Log.i(TAG, "POST ${url} → $code, ${text.take(300)}")

            if (code in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val screenId = json?.optJSONObject("screen")?.optString("id")?.takeIf { it.isNotEmpty() }
                UploadResult(
                    ok = true,
                    httpCode = code,
                    screenId = screenId,
                    replayed = json?.optBoolean("replayed", false) ?: false,
                    message = "上传成功，screen_id=$screenId"
                )
            } else {
                UploadResult(
                    ok = false,
                    httpCode = code,
                    screenId = null,
                    replayed = false,
                    message = "HTTP $code：${text.take(200)}"
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "上传失败", e)
            UploadResult(
                ok = false,
                httpCode = -1,
                screenId = null,
                replayed = false,
                message = "${e.javaClass.simpleName}: ${e.message}（云端是否已启动？地址 $baseUrl）"
            )
        } finally {
            conn?.disconnect()
        }
    }

    // ─────────────────────── 建任务 → 取卡片 ───────────────────────
    //
    // 云端只把上传的屏当"材料"存着，要出卡片必须再建一个任务：
    //   POST /api/tasks {event_id, instruction, screen_ids[]}
    //   GET  /api/tasks/{tid}  →  status=queued/running/waiting_input，卡片在 card 字段
    // 用固定的 event_id（task-<screen_id>）做幂等：同屏重复建任务只返回同一个。

    /** 建任务时给云端 Super-Agent 的指令，端侧固定这一句 */
    const val DEFAULT_TASK_INSTRUCTION =
        "整理这一屏聊天里提到的时间、地点、待办和需要我回复的事项。"

    private const val TASK_POLL_INTERVAL_MS = 1_500L
    private const val TASK_POLL_TIMEOUT_MS = 90_000L

    private data class TaskState(val status: String, val card: TaskCard?)

    /**
     * 上传一屏之后调用：建任务并等云端产出卡片。必须在后台线程调用（会阻塞）。
     *
     * @return 云端卡片；建任务失败、云端报错或超时返回 null（不影响已上传的屏）
     */
    fun analyzeScreen(
        screenIds: List<String>,
        baseUrls: List<String> = DEFAULT_BASE_URLS,
        apiKey: String? = null,
        timeoutMs: Long = TASK_POLL_TIMEOUT_MS
    ): TaskCard? {
        if (screenIds.isEmpty()) return null
        val eventId = "task-${screenIds.first()}"

        val created = createTask(eventId, screenIds, DEFAULT_TASK_INSTRUCTION, baseUrls, apiKey)
        Log.i(TAG, "建任务：${created.message}")
        val taskId = created.taskId ?: return null
        val baseUrl = created.baseUrl ?: return null

        val deadline = System.currentTimeMillis() + timeoutMs
        var waitingLogged = false
        while (System.currentTimeMillis() < deadline) {
            val state = getTask(baseUrl, taskId, apiKey)
            if (state == null) {
                // 网络抖一下就再试一次，别把任务丢了
                Thread.sleep(TASK_POLL_INTERVAL_MS)
                continue
            }
            state.card?.let {
                Log.i(TAG, "任务 $taskId 产出卡片：${it.title}（${it.fields.size} 个字段）")
                return it
            }
            when (state.status) {
                "failed", "cancelled" -> {
                    Log.w(TAG, "任务 $taskId 状态 ${state.status}，没有卡片")
                    return null
                }
                "running" -> if (!waitingLogged) {
                    Log.i(TAG, "任务 $taskId 已开始处理…")
                    waitingLogged = true
                }
            }
            Thread.sleep(TASK_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "任务 $taskId 等待卡片超时（${timeoutMs}ms）")
        return null
    }

    /** POST /api/tasks，成功返回 202 + {"task":{...},"replayed":false} */
    fun createTask(
        eventId: String,
        screenIds: List<String>,
        instruction: String = DEFAULT_TASK_INSTRUCTION,
        baseUrls: List<String> = DEFAULT_BASE_URLS,
        apiKey: String? = null
    ): TaskResult {
        var last: TaskResult? = null
        for (baseUrl in baseUrls) {
            val result = createTaskOnce(eventId, screenIds, instruction, baseUrl, apiKey)
            if (result.ok || result.httpCode != -1) return result
            last = result
        }
        return last ?: TaskResult(false, -1, null, false, "没有可用的云端地址")
    }

    private fun createTaskOnce(
        eventId: String,
        screenIds: List<String>,
        instruction: String,
        baseUrl: String,
        apiKey: String?
    ): TaskResult {
        val url = URL("${baseUrl.trimEnd('/')}/api/tasks")
        val body = JSONObject().apply {
            put("event_id", eventId)
            put("instruction", instruction)
            put("screen_ids", org.json.JSONArray(screenIds))
            put("interaction_protocol", "legacy")
        }.toString().toByteArray(StandardCharsets.UTF_8)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url.toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val text = readBody(conn, code)
            Log.i(TAG, "POST $url → $code, ${text.take(300)}")

            if (code in 200..299) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val task = json?.optJSONObject("task")
                val taskId = task?.optString("id")?.takeIf { it.isNotEmpty() }
                TaskResult(
                    ok = true,
                    httpCode = code,
                    taskId = taskId,
                    replayed = json?.optBoolean("replayed", false) ?: false,
                    message = "任务已提交 task_id=$taskId（状态 ${task?.optString("status")}）",
                    baseUrl = baseUrl
                )
            } else {
                TaskResult(false, code, null, false, "建任务失败 HTTP $code：${text.take(200)}")
            }
        } catch (e: IOException) {
            Log.e(TAG, "建任务失败", e)
            TaskResult(false, -1, null, false, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** GET /api/tasks/{tid}，取不到返回 null（交给调用方重试） */
    private fun getTask(baseUrl: String, taskId: String, apiKey: String?): TaskState? {
        val url = "${baseUrl.trimEnd('/')}/api/tasks/$taskId"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (!apiKey.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }
            val code = conn.responseCode
            val text = readBody(conn, code)
            if (code !in 200..299) {
                Log.w(TAG, "GET $url → $code, ${text.take(200)}")
                return null
            }
            val task = runCatching { JSONObject(text).optJSONObject("task") }.getOrNull() ?: return null
            val status = task.optString("status")
            val cardJson = task.optJSONObject("card")
            val card = cardJson?.let {
                parseCard(taskId, status, it, task.optInt("card_revision", 0))
            }
            TaskState(status, card)
        } catch (e: IOException) {
            Log.w(TAG, "取任务失败：${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseCard(
        taskId: String,
        status: String,
        card: JSONObject,
        revision: Int
    ): TaskCard {
        val fields = mutableListOf<CardField>()
        val array = card.optJSONArray("fields")
        if (array != null) {
            for (i in 0 until array.length()) {
                val field = array.optJSONObject(i) ?: continue
                fields += CardField(
                    label = field.optString("label"),
                    value = field.optString("value")
                )
            }
        }
        return TaskCard(
            taskId = taskId,
            status = status,
            title = card.optString("title"),
            summary = card.optString("summary"),
            fields = fields,
            revision = revision
        )
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return try {
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (e: IOException) {
            ""
        }
    }
}
