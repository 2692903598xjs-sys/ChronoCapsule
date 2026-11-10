package com.chronocapsule.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UploadQueueTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `入队后能从磁盘读回`() {
        val dir = temp.newFolder("queue")

        UploadQueue.enqueue(dir, "evt-1", """{"event_id":"evt-1"}""")

        assertEquals(1, UploadQueue.pendingCount(dir))
        val file = UploadQueue.pendingFiles(dir).single()
        assertEquals("evt-1.json", file.name)
        assertTrue(file.readText().contains("evt-1"))
    }

    @Test
    fun `event_id 里的路径字符会被过滤掉`() {
        val dir = temp.newFolder("queue")

        UploadQueue.enqueue(dir, "../../etc/passwd", "{}")

        val file = UploadQueue.pendingFiles(dir).single()
        assertEquals("....etcpasswd.json", file.name)
        assertEquals(dir, file.parentFile)
    }

    @Test
    fun `按入队顺序补传，成功的删除、失败的保留`() {
        val dir = temp.newFolder("queue")
        UploadQueue.enqueue(dir, "evt-1", "1")
        Thread.sleep(5)
        UploadQueue.enqueue(dir, "evt-2", "2")
        Thread.sleep(5)
        UploadQueue.enqueue(dir, "evt-3", "3")

        val sent = mutableListOf<String>()
        val handled = UploadQueue.drain(dir) { json ->
            sent.add(json)
            when (json) {
                "1" -> UploadQueue.DrainAction.DONE
                "2" -> UploadQueue.DrainAction.RETRY_LATER
                else -> UploadQueue.DrainAction.DISCARD
            }
        }

        assertEquals(listOf("1", "2"), sent)      // 遇到失败立刻停，不继续发 evt-3
        assertEquals(1, handled)                  // 只处理掉 evt-1
        assertEquals(2, UploadQueue.pendingCount(dir))
        assertEquals(
            listOf("2", "3"),
            UploadQueue.pendingFiles(dir).map { it.readText() }
        )
    }

    @Test
    fun `超过上限时丢最旧的`() {
        val dir = temp.newFolder("queue")
        repeat(UploadQueue.MAX_PENDING + 3) { i ->
            UploadQueue.enqueue(dir, "evt-$i", "payload-$i")
            Thread.sleep(2)
        }

        assertEquals(UploadQueue.MAX_PENDING, UploadQueue.pendingCount(dir))
        val contents = UploadQueue.pendingFiles(dir).map { it.readText() }
        assertTrue(contents.none { it == "payload-0" })
        assertTrue(contents.any { it == "payload-${UploadQueue.MAX_PENDING + 2}" })
    }

    @Test
    fun `不会留下临时文件`() {
        val dir = temp.newFolder("queue")

        UploadQueue.enqueue(dir, "evt-1", "{}")

        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `目录不存在时自动创建`() {
        val dir = File(temp.root, "not/created/yet")

        UploadQueue.enqueue(dir, "evt-1", "{}")

        assertEquals(1, UploadQueue.pendingCount(dir))
    }
}
