package com.chronocapsule.struct

import com.chronocapsule.ocr.OcrLine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 OCR 输出的「一堆带坐标的文字」变成「一场对话」。
 *
 * 处理顺序：
 *   L1 状态栏 —— 全部丢弃，但先把时间抠出来
 *   L2 头部   —— 只保留最靠上的一行当 username，下面的在线状态丢弃
 *   L3 正文   —— 只保留头部以下、输入框以上的内容
 *   L4 角色   —— 按 x 坐标判左右：左=peer（对话人），右=self（自己）
 *   L5 群聊   —— 标题带 (人数) 就判为群聊，再给每条消息挂上发言人昵称
 *
 * 群聊和单聊的输出差别：群聊多 chat_type/member_count，且每条消息带 user；
 * 单聊只留一个 username（title），消息里不重复记名字。
 *
 * 所有比例参数都相对屏幕高宽，换设备不用改。
 */
object ScreenStructurer {

    // ---------- 可调参数 ----------

    /** 状态栏占屏幕高度的比例。状态栏文字的中心点低于这个值就判为状态栏 */
    private const val STATUS_BAR_RATIO = 0.075f

    /** 在哪个纵向区间里找标题（避开状态栏，也避开正文） */
    private const val HEADER_SEARCH_TOP = 0.06f
    private const val HEADER_SEARCH_BOTTOM = 0.18f

    /** 标题左侧留出的比例，用来排除返回箭头和头像 */
    private const val HEADER_LEFT_MARGIN = 0.12f

    /** 超过这个比例就是底部输入区（表情、加号、话筒） */
    private const val INPUT_AREA_RATIO = 0.90f

    /** 低于这个置信度的文字不参与结构化 */
    private const val MIN_SCORE = 0.55f

    /** 行高小于屏幕高度这个比例的，当作噪声（分隔线、图标碎片） */
    private const val MIN_HEIGHT_RATIO = 0.008f

    /** 群聊标题的长度上限（字符数），超过这个长度的一律不当作昵称 */
    private const val MAX_NAME_CHARS = 16

    /**
     * 群名(人数)：QQ 和微信都用这个格式。
     *   数学建模班(40) ｜ 生权/工作(32) ｜ 考研加油(500)
     * 单人聊天的标题不会带括号人数，所以这个正则就是群聊判据。
     *
     * 不锚定结尾：标题右边常跟着静音铃铛、群公告之类的图标，
     * OCR 会把它们读成零散字符（实测 "生权/工作(32)🔕" → "生权J工作(32)及"）。
     */
    private val GROUP_TITLE = Regex("""^(.+?)\s*[（(]\s*(\d{1,4})\s*[）)]""")

    /** 时间分隔符：20:32 / 10月29日 10:23 / 昨天 20:32 / 上午10:23 之类 */
    private val TIME_LABEL = Regex(
        """^(\d{1,2}:\d{2}(:\d{2})?|昨天\s*\d{1,2}:\d{2}|今天\s*\d{1,2}:\d{2}|前天\s*\d{1,2}:\d{2}|(上午|下午|中午|晚上|凌晨)\s*\d{1,2}:\d{2}|\d{1,2}月\d{1,2}日(\s*\d{1,2}:\d{2})?|\d{4}年\d{1,2}月\d{1,2}日.*)$"""
    )

    /** 从混合文本里抠时间，状态栏那行常被图标污染成 "22:11Ⓢ90A r" 这种 */
    private val TIME_FIND = Regex("""\d{1,2}:\d{2}""")

    /**
     * 系统提示的典型措辞。这类行是居中灰字，不是任何一方说的话，
     * 但按 x 坐标会被判成 self（居中偏右一点点）。要排除掉。
     */
    private val SYSTEM_NOTICE_HINTS = listOf(
        "撤回了一条消息", "加入了群聊", "已加入群聊", "移出群聊", "已退出群聊",
        "领取了", "拆开了", "以上是打招呼的内容", "现在可以开始聊天了",
        "对方开启了朋友验证", "消息已发出，但被对方拒收"
    )

    // ---------- 输出结构 ----------

    data class Message(
        val time: String?,
        val role: String,          // "peer" 或 "self"
        val user: String?,         // 群聊里这条消息的发言人昵称；单聊为 null
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class Result(
        val screenTime: String?,
        val title: String?,
        val chatType: String,      // "single" 或 "group"
        val memberCount: Int?,     // 群聊人数
        val messages: List<Message>,
        val json: String,
        val rawLineCount: Int
    ) {
        val isGroup: Boolean get() = chatType == CHAT_GROUP
    }

    // ---------- 主流程 ----------

    fun structure(lines: List<OcrLine>, width: Int, height: Int): Result {
        val h = height.toFloat()
        val w = width.toFloat()

        // ---- L1 状态栏：丢内容，留时间 ----
        val statusBar = lines.filter { it.box.centerY / h < STATUS_BAR_RATIO }
        val screenTime = statusBar
            .mapNotNull { TIME_FIND.find(it.text)?.value }
            .firstOrNull()

        // ---- L2 头部：头部区域里最靠上的一行就是 username ----
        val headerCandidates = lines.filter {
            val ny = it.box.centerY / h
            ny >= HEADER_SEARCH_TOP &&
                ny < HEADER_SEARCH_BOTTOM &&
                it.box.left > w * HEADER_LEFT_MARGIN &&
                it.score >= MIN_SCORE &&
                it.box.height / h >= MIN_HEIGHT_RATIO
        }
        // 不能按字号取：QQ 默认主题里气泡正文比标题字还大，"又邀我玩原神"会被误判成 username。
        // 标题永远是头部区域里最靠上的那行，所以按 top 取最小；top 相同再比字号。
        val title = headerCandidates.minWithOrNull(compareBy({ it.box.top }, { -it.box.height }))

        // 标题正下方紧贴的那行是"在线 - 4G · 种菜中"这类状态，一并丢掉。
        // 只认"与标题的垂直间距最小且不超过 1.2 倍字高"的那一行，时间分隔符不算。
        val subtitle = title?.let { t ->
            val maxGap = 1.2f * t.box.height
            lines.asSequence()
                .filter {
                    it !== t &&
                        !isTimeLabel(it.text) &&
                        it.box.top >= t.box.centerY &&        // 排除和标题同高的返回键、未读数徽标
                        it.box.top - t.box.bottom <= maxGap
                }
                .minByOrNull { it.box.top - t.box.bottom }
        }

        // 正文的起始线：标题（含状态行）的下沿。没找到标题就退回固定比例。
        val bodyTop: Float = (subtitle ?: title)?.box?.bottom?.toFloat() ?: (HEADER_SEARCH_BOTTOM * h)

        // ---- L3 正文区 ----
        val body = lines.filter {
            val ny = it.box.centerY / h
            it !== title &&
                it !== subtitle &&
                ny >= STATUS_BAR_RATIO &&
                it.box.top > bodyTop &&
                ny < INPUT_AREA_RATIO &&
                it.score >= MIN_SCORE &&
                it.box.height / h >= MIN_HEIGHT_RATIO &&
                it.text.isNotBlank()
        }.sortedWith(compareBy({ it.box.top }, { it.box.left }))

        // ---- 群聊判定：标题带 (人数) ----
        val groupMatch = title?.text?.let { GROUP_TITLE.find(it.trim()) }
        val chatType = if (groupMatch != null) CHAT_GROUP else CHAT_SINGLE
        val groupName = groupMatch?.groupValues?.get(1)?.trim() ?: title?.text
        val memberCount = groupMatch?.groupValues?.get(2)?.toIntOrNull()

        // ---- 群聊：把每条气泡上方的发言人昵称找出来 ----
        val nameLines = if (groupMatch != null) detectSpeakerNames(body, w) else emptySet()

        // ---- L4 时间节点 + 左右角色 + 发言人 ----
        val messages = ArrayList<Message>(body.size)
        var currentTime: String? = null
        var currentUser: String? = null

        for (line in body) {
            if (isTimeLabel(line.text)) {
                currentTime = normalizeTime(line.text)
                continue
            }
            if (line in nameLines) {
                // 昵称本身不是消息，只改"下一条是谁说的"
                currentUser = line.text.trim()
                continue
            }
            if (isSystemNotice(line, w)) continue
            val role = if (line.box.centerX < w * 0.5f) ROLE_PEER else ROLE_SELF
            // 自己发言后，下一条别人的消息会重新带昵称；这里先清空避免串台
            if (role == ROLE_SELF) currentUser = ROLE_SELF
            messages.add(
                Message(
                    time = currentTime,
                    role = role,
                    user = if (groupMatch != null) (currentUser ?: role) else null,
                    text = line.text,
                    left = line.box.left,
                    top = line.box.top,
                    right = line.box.right,
                    bottom = line.box.bottom
                )
            )
        }

        return Result(
            screenTime = screenTime,
            title = groupName,
            chatType = chatType,
            memberCount = memberCount,
            messages = messages,
            json = buildJson(screenTime, groupName, chatType, memberCount, messages),
            rawLineCount = lines.size
        )
    }

    // ---------- 工具 ----------

    private fun isTimeLabel(text: String): Boolean {
        val t = text.trim().replace(" ", "")
        if (t.isEmpty()) return false
        // 只有时间、很短，才可能是分隔符；避免把"我十点钟"这种正文误判
        if (t.length > 16) return false
        return TIME_LABEL.matches(t)
    }

    private fun normalizeTime(text: String): String = text.trim()

    /**
     * 系统提示：措辞命中 + 位置居中。
     *
     * 只看措辞会误伤正文（"我领取了快递"），只看居中会误伤很宽的气泡，
     * 两条一起用才稳。
     */
    private fun isSystemNotice(line: OcrLine, w: Float): Boolean {
        if (SYSTEM_NOTICE_HINTS.none { line.text.contains(it) }) return false
        return kotlin.math.abs(line.box.centerX - w * 0.5f) <= 0.12f * w
    }

    /**
     * 群聊里每条左侧气泡上方都有一行小小的发言人昵称（QQ 和微信排版一致）。
     *
     * 判据（必须同时满足）：
     *   1. 在左半边，且不是时间分隔符
     *   2. 下一行是一条左侧气泡
     *   3. 和气泡之间的空隙是"半个字高左右"——同一个气泡内部的行距只有 0.2 个字高，
     *      昵称和气泡之间是 0.7 左右，这是区分"昵称"和"气泡换行"最可靠的信号
     *   4. 昵称的左边比气泡正文更靠左：昵称贴着气泡外沿起排，正文有内边距
     *      （刷卡片的"学习通签到"就是靠这条排除的，它在正文右边）
     */
    private fun detectSpeakerNames(body: List<OcrLine>, w: Float): Set<OcrLine> {
        val names = HashSet<OcrLine>()
        for (i in 0 until body.size - 1) {
            val name = body[i]
            val bubble = body[i + 1]

            if (name.box.centerX >= w * 0.5f) continue            // 右侧是自己的气泡，没有昵称
            if (bubble.box.centerX >= w * 0.5f) continue          // 昵称后面必须跟左侧气泡
            if (isTimeLabel(name.text)) continue
            if (name.text.trim().length > MAX_NAME_CHARS) continue
            if (name.box.height > bubble.box.height) continue     // 昵称不会比正文还高

            // 昵称从气泡外沿起排，正文左右有内边距，所以昵称更靠左
            if (name.box.left >= bubble.box.left) continue

            // 间距：气泡内换行只有 0.2 个字高，昵称到气泡有 0.6~0.8 个
            // 下界用昵称自己字高算（气泡框里带表情时会被撑高，拿它算比值会漏判）
            val gap = (bubble.box.top - name.box.bottom).toFloat()
            if (gap < 0.5f * name.box.height || gap > 1.2f * bubble.box.height) continue

            names.add(name)
        }
        return names
    }

    private fun buildJson(
        screenTime: String?,
        title: String?,
        chatType: String,
        memberCount: Int?,
        messages: List<Message>
    ): String {
        val root = JSONObject()
        root.put("app", "unknown")
        root.put("chat_type", chatType)
        root.put("screen_time", screenTime ?: JSONObject.NULL)
        root.put("title", title ?: JSONObject.NULL)
        if (chatType == CHAT_GROUP) {
            root.put("member_count", memberCount ?: JSONObject.NULL)
        }

        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("time", m.time ?: JSONObject.NULL)
            o.put("role", m.role)
            if (chatType == CHAT_GROUP) o.put("user", m.user ?: m.role)
            o.put("text", m.text)
            o.put("bbox", JSONArray(listOf(m.left, m.top, m.right, m.bottom)))
            arr.put(o)
        }
        root.put("messages", arr)
        return root.toString(2)
    }

    const val ROLE_PEER = "peer"
    const val ROLE_SELF = "self"
    const val CHAT_SINGLE = "single"
    const val CHAT_GROUP = "group"
}
