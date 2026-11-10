package com.chronocapsule.dedup

import com.chronocapsule.contract.ScreenContract
import java.io.File
import java.util.LinkedHashMap

/**
 * 跨屏去重：同一个会话被连续抓屏时，只保留"上一次抓屏之后新出现"的消息。
 *
 * 为什么必须做：
 *   自动截图是按"进/出聊天页"触发的，每来一条新消息用户通常会切回聊天页，
 *   此时整屏 18 条消息里只有 1 条是新的。如果整屏照发，同一个 event 会带着
 *   新 event_id 反复入库，云端看到的是一串重复消息，时间线和任务提取全会被污染。
 *
 * 判定方式（消息指纹）：
 *   speaker + text，做归一化（去掉所有空白、英文转小写）。
 *   · 去空白：OCR 经常在中文之间插空格（"你 好"），不归一化会被当成新消息
 *   · 转小写：OCR 大小写不稳（AI / Al），统一后能少漏一条重复
 *
 * 计数而不是简单集合：
 *   同一屏里可能真的有两句一模一样的"哈哈哈"，用集合会把第二条误删。
 *   所以记的是"这个指纹至今出现过几次"，本次抓屏里第 k 次出现、
 *   而历史上只记到 m 次，就把 k > m 的那几条当作新增。
 *
 * 已知边界：
 *   · 历史消息滚出屏幕后，又来一条同文本消息，会被误判为重复（计数法固有）。聊天场景可接受。
 *   · OCR 把同一句话识别成不同文字时，会被当成新消息重发（宁多勿漏）。
 *   · 只在进程内存里记，进程被杀后重新开始（第一版接受；持久化留在优化阶段）。
 */
object CrossScreenDeduper {

    /** 最多同时记住几个会话，超出按进入顺序淘汰最旧的 */
    private const val MAX_CONVERSATIONS = 32

    /** 单个会话最多记住多少条消息指纹 */
    private const val MAX_FINGERPRINTS = 1024

    /** 会话标识 → (消息指纹 → 至今已发出的最大出现次数) */
    private val history = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    data class Result(
        /** 需要上传的 block（保持原屏内顺序，id 由调用方重新编号） */
        val blocks: List<ScreenContract.Block>,
        /** 有内容但被判定为重复而丢掉的条数 */
        val skipped: Int,
        /** 本次抓屏是否真的做了去重（认不出会话时为 false） */
        val applied: Boolean
    )

    /**
     * 过滤一屏的 block。
     *
     * @param appPackage 前台包名，和服务端契约里的 app_package 一致
     * @param pageTitle  聊天标题；标题识别失败时不做去重，宁可整屏重发也不能漏消息
     */
    fun filter(
        blocks: List<ScreenContract.Block>,
        appPackage: String,
        pageTitle: String
    ): Result {
        if (blocks.isEmpty()) return Result(blocks, 0, false)

        val title = pageTitle.trim()
        if (title.isEmpty()) return Result(blocks, 0, false)

        val key = "$appPackage|${normalize(title)}"

        synchronized(history) {
            // 先摘下再插回，让最近用过的会话排在队尾，淘汰时总是先丢最久没用的
            val seen = history.remove(key) ?: LinkedHashMap()
            history[key] = seen
            trimConversations()

            val occurrences = HashMap<String, Int>(blocks.size)
            val kept = ArrayList<ScreenContract.Block>(blocks.size)
            var skipped = 0

            for (block in blocks) {
                val fingerprint = fingerprint(block)
                val n = (occurrences[fingerprint] ?: 0) + 1
                occurrences[fingerprint] = n

                val remembered = seen[fingerprint] ?: 0
                if (n <= remembered) {
                    skipped++
                    continue
                }
                kept.add(block)
                seen[fingerprint] = n
            }
            trimFingerprints(seen)

            return Result(kept, skipped, true)
        }
    }

    /** 清空全部历史（换会话/调试用） */
    fun reset() {
        synchronized(history) { history.clear() }
    }

    // ---------- 持久化 ----------
    //
    // 为什么落盘：进程被系统回收（真机上很常见）后内存历史清零，
    // 重启后的第一屏会把整屏消息重新上传一遍。
    //
    // 格式（纯文本，不用 JSON —— 单测里 org.json 是桩实现）：
    //   v1
    //   C<TAB>会话键
    //   <次数><TAB><消息指纹>
    //   ...
    // 指纹去掉过所有空白，会话键来自包名和标题，都不可能含 TAB / 换行。

    private const val FILE_VERSION = "v1"

    /** 原子写：先写 .tmp 再改名，写到一半进程被杀也不会留下半截历史 */
    fun saveTo(file: File) {
        synchronized(history) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            try {
                tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write(FILE_VERSION)
                    w.write("\n")
                    for ((key, seen) in history) {
                        w.write("C\t")
                        w.write(key)
                        w.write("\n")
                        for ((fingerprint, count) in seen) {
                            w.write(count.toString())
                            w.write("\t")
                            w.write(fingerprint)
                            w.write("\n")
                        }
                    }
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (e: Exception) {
                tmp.delete()
            }
        }
    }

    /** 读回历史；文件不存在或内容损坏时保持当前内存状态 */
    fun loadFrom(file: File) {
        if (!file.exists()) return

        val loaded = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                var current: LinkedHashMap<String, Int>? = null
                var firstLine = true
                reader.forEachLine { line ->
                    if (firstLine) {
                        firstLine = false
                        if (line == FILE_VERSION) return@forEachLine
                    }
                    when {
                        line.startsWith("C\t") -> {
                            current = LinkedHashMap()
                            loaded[line.substring(2)] = current!!
                        }

                        current != null && line.isNotEmpty() -> {
                            val tab = line.indexOf('\t')
                            if (tab > 0) {
                                val count = line.substring(0, tab).toIntOrNull()
                                if (count != null && count > 0) {
                                    current!![line.substring(tab + 1)] = count
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return
        }

        synchronized(history) {
            history.clear()
            history.putAll(loaded)
        }
    }

    // ---------- 内部 ----------

    private fun fingerprint(block: ScreenContract.Block): String =
        normalize(block.speaker ?: "") + '\u0001' + normalize(block.text)

    /** 去掉所有空白并转小写；只用于比较，不回写契约 */
    private fun normalize(s: String): String =
        s.filterNot { it.isWhitespace() }.lowercase()

    private fun trimConversations() {
        // 插入顺序就是最近使用顺序，队头即最久没用过的会话
        while (history.size > MAX_CONVERSATIONS) {
            val eldest = history.keys.firstOrNull() ?: return
            history.remove(eldest)
        }
    }

    private fun trimFingerprints(seen: LinkedHashMap<String, Int>) {
        while (seen.size > MAX_FINGERPRINTS) {
            val eldest = seen.keys.firstOrNull() ?: return
            seen.remove(eldest)
        }
    }
}
