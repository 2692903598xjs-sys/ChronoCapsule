package com.chronocapsule.contract

import com.chronocapsule.struct.ScreenStructurer
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 端云接口契约层（Cloud Agent v0.4 · POST /api/screens）。
 *
 * 服务端 Pydantic 模型是 extra="forbid" —— 顶层多一个字段就 422，
 * 所以这里不改字段名、不加自定义字段，只做映射：
 *
 *   我们内部的结构化结果          →   契约
 *   ------------------------------------------------------------
 *   title                         →   page_title
 *   每条消息的一条 chat           →   blocks[i] {kind:"chat"}
 *   user（群聊昵称）/ 对话人 / 我 →   speaker
 *   消息的 time（20:32）          →   message_at（补全日期+时区）
 *   像素 bbox                     →   bbox（归一化 0–1000）
 *
 * 契约硬约束（来自 api-contract.md 和服务端 models.py）：
 *   · blocks ≤ 60，单块 ≤ 4000 字，整屏 ≤ 24000 字
 *   · block.id 在屏内唯一，只允许 [a-zA-Z0-9_.:-]
 *   · captured_at / message_at 必须带时区
 *   · bbox 必须满足 0 ≤ x1 ≤ x2 ≤ 1000、0 ≤ y1 ≤ y2 ≤ 1000
 */
object ScreenContract {

    const val VERSION = "v0.4"

    const val MAX_BLOCKS = 60
    const val MAX_BLOCK_TEXT = 4000
    const val MAX_TOTAL_TEXT = 24000

    /** 契约里自己说的话统一记成"我"，和云端示例保持一致 */
    const val SELF_SPEAKER = "我"

    /** 拿不到手机号/昵称时，对端只能记成这个 */
    private const val UNKNOWN_SPEAKER = "对方"

    data class Block(
        val id: String,
        val kind: String,
        val speaker: String?,
        val text: String,
        val messageAt: String?,
        val bbox: List<Float>?
    )

    data class Payload(
        val eventId: String,
        val sessionId: String,
        val appPackage: String,
        val appName: String,
        val pageTitle: String,
        val capturedAt: String,
        val blocks: List<Block>
    ) {
        /** 转成 POST /api/screens 可以直接发出去的 JSON */
        fun toJson(): JSONObject {
            val root = JSONObject()
            root.put("event_id", eventId)
            root.put("session_id", sessionId)
            root.put("app_package", appPackage)
            root.put("app_name", appName)
            root.put("page_title", pageTitle)
            root.put("captured_at", capturedAt)

            val arr = JSONArray()
            for (b in blocks) {
                val o = JSONObject()
                o.put("id", b.id)
                o.put("kind", b.kind)
                if (b.speaker != null) o.put("speaker", b.speaker)
                o.put("text", b.text)
                if (b.messageAt != null) o.put("message_at", b.messageAt)
                if (b.bbox != null) o.put("bbox", JSONArray(b.bbox))
                arr.put(o)
            }
            root.put("blocks", arr)
            return root
        }

        fun toJsonString(): String = toJson().toString(2)

        companion object {
            /**
             * 从 JSON 还原（离线队列重发用）。
             *
             * event_id 必须原样保留：服务端靠它做幂等，重发不能换 id，
             * 否则同一屏会在云端入库两次。
             */
            fun fromJson(o: JSONObject): Payload {
                val blocksArr = o.optJSONArray("blocks") ?: JSONArray()
                val blocks = ArrayList<Block>(blocksArr.length())
                for (i in 0 until blocksArr.length()) {
                    val b = blocksArr.optJSONObject(i) ?: continue
                    val bboxArr = b.optJSONArray("bbox")
                    val bbox = bboxArr?.let { arr ->
                        (0 until arr.length()).map { arr.getDouble(it).toFloat() }
                    }
                    blocks.add(
                        Block(
                            id = b.optString("id"),
                            kind = b.optString("kind", KIND_CHAT),
                            speaker = b.optString("speaker").takeIf { it.isNotEmpty() },
                            text = b.optString("text"),
                            messageAt = b.optString("message_at").takeIf { it.isNotEmpty() },
                            bbox = bbox
                        )
                    )
                }
                return Payload(
                    eventId = o.optString("event_id"),
                    sessionId = o.optString("session_id"),
                    appPackage = o.optString("app_package", "unknown"),
                    appName = o.optString("app_name", "unknown"),
                    pageTitle = o.optString("page_title"),
                    capturedAt = o.optString("captured_at"),
                    blocks = blocks
                )
            }
        }

        /** 本地自检：把契约里的硬约束在发出去之前先过一遍 */
        fun validate(): List<String> {
            val problems = ArrayList<String>()
            if (blocks.isEmpty()) problems.add("blocks 不能为空（服务端要求 min_length=1）")
            if (blocks.size > MAX_BLOCKS) problems.add("blocks=${blocks.size} 超过 $MAX_BLOCKS")
            if (blocks.map { it.id }.toSet().size != blocks.size) problems.add("block.id 在屏内重复")
            blocks.forEach {
                if (it.text.length > MAX_BLOCK_TEXT) problems.add("${it.id} 文本超过 $MAX_BLOCK_TEXT 字")
                if (!ID_PATTERN.matches(it.id)) problems.add("${it.id} 不符合 id 字符集")
            }
            val total = blocks.sumOf { it.text.length }
            if (total > MAX_TOTAL_TEXT) problems.add("整屏文本 $total 超过 $MAX_TOTAL_TEXT")
            if (!ID_PATTERN.matches(eventId)) problems.add("event_id 不符合 id 字符集")
            if (!ID_PATTERN.matches(sessionId)) problems.add("session_id 不符合 id 字符集")
            return problems
        }
    }

    private val ID_PATTERN = Regex("""^[a-zA-Z0-9_.:-]{1,100}$""")

    /**
     * 跨屏去重后重新编号。
     *
     * 契约要求 block.id 在屏内唯一，但没要求连续；这里仍然从 bubble-1 连续编，
     * 是为了日志里"第几条"和 id 直观对应。顺序保持不变（云端按顺序拼时间线）。
     */
    fun renumber(blocks: List<Block>): List<Block> =
        blocks.mapIndexed { index, block ->
            val id = "bubble-${index + 1}"
            if (block.id == id) block else block.copy(id = id)
        }

    /**
     * 结构化结果 → 契约 payload。
     *
     * @param appPackage 目前还没有 AccessibilityService，拿不到前台包名，先传 "unknown"，
     *                   接上无障碍服务后改成真实包名（com.tencent.mm / com.tencent.mobileqq）
     */
    fun build(
        result: ScreenStructurer.Result,
        width: Int,
        height: Int,
        sessionId: String,
        eventId: String,
        capturedAt: OffsetDateTime = OffsetDateTime.now(),
        appPackage: String = "unknown",
        appName: String = "unknown",
        zone: ZoneId = ZoneId.systemDefault()
    ): Payload {
        val blocks = ArrayList<Block>()
        var totalChars = 0

        for (message in result.messages) {
            if (blocks.size >= MAX_BLOCKS) break

            val text = message.text.trim().take(MAX_BLOCK_TEXT)
            if (text.isEmpty()) continue
            if (totalChars + text.length > MAX_TOTAL_TEXT) break
            totalChars += text.length

            blocks.add(
                Block(
                    id = "bubble-${blocks.size + 1}",
                    kind = KIND_CHAT,
                    speaker = speakerOf(message, result),
                    text = text,
                    messageAt = resolveMessageAt(message.time, capturedAt, zone),
                    bbox = normalize(message.left, message.top, message.right, message.bottom, width, height)
                )
            )
        }

        return Payload(
            eventId = eventId,
            sessionId = sessionId,
            appPackage = appPackage,
            appName = appName,
            pageTitle = result.title.orEmpty(),
            capturedAt = capturedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            blocks = blocks
        )
    }

    // ---------- 映射细节 ----------

    /**
     * 群聊：谁说的就记谁；自己记"我"。
     * 单聊：对端就是标题里的那个人，自己记"我"。
     */
    private fun speakerOf(message: ScreenStructurer.Message, result: ScreenStructurer.Result): String {
        if (message.role == ScreenStructurer.ROLE_SELF) return SELF_SPEAKER
        if (result.isGroup) return message.user?.takeIf { it.isNotBlank() } ?: UNKNOWN_SPEAKER
        return result.title?.takeIf { it.isNotBlank() } ?: UNKNOWN_SPEAKER
    }

    /** 像素坐标 → 0–1000 归一化，保留一位小数 */
    private fun normalize(l: Int, t: Int, r: Int, b: Int, width: Int, height: Int): List<Float> {
        if (width <= 0 || height <= 0) return listOf(0f, 0f, 0f, 0f)
        fun x(v: Int) = (v * 1000f / width).coerceIn(0f, 1000f).round1()
        fun y(v: Int) = (v * 1000f / height).coerceIn(0f, 1000f).round1()
        return listOf(x(l), y(t), x(r), y(b))
    }

    private fun Float.round1(): Float = kotlin.math.round(this * 10f) / 10f

    // ---------- 时间补全 ----------

    private val YEAR_MONTH_DAY = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")
    private val MONTH_DAY = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val HOUR_MINUTE = Regex("""(\d{1,2}):(\d{2})""")

    /**
     * 聊天界面里的时间分隔符只有 "20:32" 或 "10月29日 10:23"，
     * 但契约要带时区的完整时间，所以要用截图时刻的日期去补。
     *
     * 规则：
     *   有年月日 → 用它
     *   只有月日 → 用截图年份；若补出来的日期在未来，说明是去年
     *   只有时分 → 当作截图当天（跨零点的聊天会记错一天，第一版接受）
     */
    private fun resolveMessageAt(
        raw: String?,
        capturedAt: OffsetDateTime,
        zone: ZoneId
    ): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        val hm = HOUR_MINUTE.find(text) ?: return null
        val hour = hm.groupValues[1].toInt()
        val minute = hm.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null

        val capturedDate = capturedAt.atZoneSameInstant(zone).toLocalDate()
        val date = when {
            YEAR_MONTH_DAY.containsMatchIn(text) -> {
                val m = YEAR_MONTH_DAY.find(text)!!
                safeDate(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt()
                ) ?: capturedDate
            }

            MONTH_DAY.containsMatchIn(text) -> {
                val m = MONTH_DAY.find(text)!!
                val month = m.groupValues[1].toInt()
                val day = m.groupValues[2].toInt()
                val thisYear = safeDate(capturedDate.year, month, day)
                thisYear?.takeIf { !it.isAfter(capturedDate) }
                    ?: safeDate(capturedDate.year - 1, month, day)
                    ?: capturedDate
            }

            else -> capturedDate
        }

        val offset = capturedAt.atZoneSameInstant(zone).offset
        return OffsetDateTime.of(date, LocalTime.of(hour, minute), offset)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (e: java.time.DateTimeException) {
        null
    }

    const val KIND_CHAT = "chat"
}
