package com.chronocapsule.net

import java.io.File

/**
 * 离线上传队列：云端不可达时先把 payload 落盘，恢复后再补传。
 *
 * 为什么必须做：现在的上传是"发一次，失败只打日志"。手机在地铁、电梯、
 * 校园网切换时很容易断一下，一断这一屏就永久丢了，而用户根本不知道。
 *
 * 设计取舍：
 *   · 一屏一个文件，文件名就是 event_id —— 重发时 event_id 不变，
 *     服务端靠它做幂等，补传不会造成重复入库
 *   · 只保留最近 MAX_PENDING 屏，超出丢最旧的，防止长期离线把存储撑爆
 *   · 补传顺序按落盘时间从旧到新，符合时间线阅读顺序
 *   · 遇到"连不上"就停，不继续重试后面的——大概率整体网络都没恢复
 *   · 服务端明确答复的错误（4xx/5xx）直接丢弃，重试也不会成功
 *
 * 刻意不依赖 Android Context：目录由调用方传入，这样能用 JUnit 直接测。
 */
object UploadQueue {

    /** 最多缓存多少屏；按每屏 24K 字符估算，20 屏不超过 1MB */
    const val MAX_PENDING = 20

    enum class DrainAction {
        /** 发送成功，删除文件 */
        DONE,

        /** 服务端明确拒绝，重试无意义，删除文件 */
        DISCARD,

        /** 网络不可达，保留文件并停止本轮补传 */
        RETRY_LATER
    }

    /**
     * 把一屏 payload 写入队列。
     *
     * @param eventId 作为文件名的一部分，同时保证重发时内容可追溯
     * @param json    已经序列化好的契约 JSON（原样落盘，不做二次加工）
     * @return 写入的文件；失败返回 null
     */
    fun enqueue(dir: File, eventId: String, json: String): File? {
        if (!dir.exists() && !dir.mkdirs()) return null

        val safeId = eventId.filter { it.isLetterOrDigit() || it in "_.:-" }.take(100)
        if (safeId.isEmpty()) return null

        val target = File(dir, "$safeId.json")
        val tmp = File(dir, "$safeId.tmp")
        return try {
            tmp.writeText(json)
            // 先写临时文件再改名：进程在写的途中被杀，也不会留下半截 JSON
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return null
            }
            trim(dir)
            target
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    /**
     * 依次补传。若 [send] 返回 RETRY_LATER 立刻停止本轮（网络大概率整体没恢复）。
     *
     * @return 本轮处理掉（成功或丢弃）的屏数
     */
    fun drain(dir: File, send: (String) -> DrainAction): Int {
        var handled = 0
        for (file in pendingFiles(dir)) {
            val action = try {
                send(file.readText())
            } catch (e: Exception) {
                DrainAction.RETRY_LATER
            }
            when (action) {
                DrainAction.DONE, DrainAction.DISCARD -> {
                    file.delete()
                    handled++
                }

                DrainAction.RETRY_LATER -> return handled
            }
        }
        return handled
    }

    fun pendingCount(dir: File): Int = pendingFiles(dir).size

    /** 按落盘时间从旧到新 */
    fun pendingFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

    private fun trim(dir: File) {
        val files = pendingFiles(dir)
        if (files.size <= MAX_PENDING) return
        files.take(files.size - MAX_PENDING).forEach { it.delete() }
    }
}
