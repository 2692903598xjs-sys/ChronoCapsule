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

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return try {
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        } catch (e: IOException) {
            ""
        }
    }
}
