package com.chronocapsule.dedup

import com.chronocapsule.contract.ScreenContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrossScreenDeduperTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        CrossScreenDeduper.reset()
    }

    @Test
    fun `第一次抓屏全部上传`() {
        val screen = listOf(
            block("bubble-1", "我", "晚上一起吃饭？"),
            block("bubble-2", "室友", "好")
        )

        val result = CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")

        assertTrue(result.applied)
        assertEquals(2, result.blocks.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `同一屏再抓一次全部跳过`() {
        val screen = listOf(
            block("bubble-1", "我", "晚上一起吃饭？"),
            block("bubble-2", "室友", "好")
        )
        CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")

        val again = CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")

        assertTrue(again.applied)
        assertTrue(again.blocks.isEmpty())
        assertEquals(2, again.skipped)
    }

    @Test
    fun `只上传新冒出来的那条`() {
        val first = listOf(
            block("bubble-1", "室友", "你什么时候回来"),
            block("bubble-2", "我", "路上")
        )
        CrossScreenDeduper.filter(first, "com.tencent.mm", "室友")

        val second = listOf(
            block("bubble-1", "室友", "你什么时候回来"),
            block("bubble-2", "我", "路上"),
            block("bubble-3", "室友", "带杯奶茶")
        )
        val result = CrossScreenDeduper.filter(second, "com.tencent.mm", "室友")

        assertEquals(1, result.blocks.size)
        assertEquals("带杯奶茶", result.blocks[0].text)
        assertEquals(2, result.skipped)
    }

    @Test
    fun `同一屏里两句一样的话都要保留`() {
        val screen = listOf(
            block("bubble-1", "室友", "哈哈哈"),
            block("bubble-2", "室友", "哈哈哈")
        )

        val result = CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")

        assertEquals(2, result.blocks.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `同样的句子再多出现一次时才补传一条`() {
        val first = listOf(block("bubble-1", "室友", "哈哈哈"))
        CrossScreenDeduper.filter(first, "com.tencent.mm", "室友")

        val second = listOf(
            block("bubble-1", "室友", "哈哈哈"),
            block("bubble-2", "室友", "哈哈哈")
        )
        val result = CrossScreenDeduper.filter(second, "com.tencent.mm", "室友")

        assertEquals(1, result.blocks.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `不同会话的历史互不影响`() {
        val screen = listOf(block("bubble-1", "妈妈", "吃饭了吗"))
        CrossScreenDeduper.filter(screen, "com.tencent.mm", "妈妈")

        val other = listOf(block("bubble-1", "妈妈", "吃饭了吗"))
        val result = CrossScreenDeduper.filter(other, "com.tencent.mm", "爸爸")

        assertEquals(1, result.blocks.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `同一句话在不同 App 里不算重复`() {
        val screen = listOf(block("bubble-1", "室友", "在吗"))
        CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")

        val result = CrossScreenDeduper.filter(screen, "com.tencent.mobileqq", "室友")

        assertEquals(1, result.blocks.size)
    }

    @Test
    fun `标题识别失败时不去重宁多勿漏`() {
        val screen = listOf(block("bubble-1", "室友", "在吗"))
        CrossScreenDeduper.filter(screen, "com.tencent.mm", "")

        val result = CrossScreenDeduper.filter(screen, "com.tencent.mm", "  ")

        assertFalse(result.applied)
        assertEquals(1, result.blocks.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `OCR 插空格和大小写差异仍算同一条`() {
        val first = listOf(block("bubble-1", "室友", "我用 AI 查了 xlsx"))
        CrossScreenDeduper.filter(first, "com.tencent.mm", "室友")

        val second = listOf(block("bubble-1", "室友", "我用AI查了 XLSX"))
        val result = CrossScreenDeduper.filter(second, "com.tencent.mm", "室友")

        assertTrue(result.blocks.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun `说话人不同时同文本不算重复`() {
        val first = listOf(block("bubble-1", "室友", "好"))
        CrossScreenDeduper.filter(first, "com.tencent.mm", "室友")

        val second = listOf(
            block("bubble-1", "我", "好"),
            block("bubble-2", "室友", "好")
        )
        val result = CrossScreenDeduper.filter(second, "com.tencent.mm", "室友")

        assertEquals(1, result.blocks.size)
        assertEquals("我", result.blocks[0].speaker)
    }

    @Test
    fun `历史能落盘并在重启后恢复`() {
        val file = temp.newFile("dedupe.txt")
        val screen = listOf(
            block("bubble-1", "室友", "在吗"),
            block("bubble-2", "我", "在")
        )
        CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")
        CrossScreenDeduper.saveTo(file)

        // 模拟进程重启：内存清空，再从磁盘读回
        CrossScreenDeduper.reset()
        CrossScreenDeduper.loadFrom(file)

        val result = CrossScreenDeduper.filter(screen, "com.tencent.mm", "室友")

        assertTrue(result.blocks.isEmpty())
        assertEquals(2, result.skipped)
    }

    @Test
    fun `落盘后新增消息仍然能被识别`() {
        val file = temp.newFile("dedupe.txt")
        CrossScreenDeduper.filter(
            listOf(block("bubble-1", "室友", "在吗")),
            "com.tencent.mm",
            "室友"
        )
        CrossScreenDeduper.saveTo(file)

        CrossScreenDeduper.reset()
        CrossScreenDeduper.loadFrom(file)

        val result = CrossScreenDeduper.filter(
            listOf(
                block("bubble-1", "室友", "在吗"),
                block("bubble-2", "室友", "明天开会")
            ),
            "com.tencent.mm",
            "室友"
        )

        assertEquals(1, result.blocks.size)
        assertEquals("明天开会", result.blocks[0].text)
    }

    @Test
    fun `历史文件损坏时不影响后续去重`() {
        val file = temp.newFile("dedupe.txt")
        file.writeText("这不是合法格式\nC\t坏数据\nxxx")
        CrossScreenDeduper.reset()

        CrossScreenDeduper.loadFrom(file)   // 不抛异常

        val result = CrossScreenDeduper.filter(
            listOf(block("bubble-1", "室友", "在吗")),
            "com.tencent.mm",
            "室友"
        )
        assertEquals(1, result.blocks.size)
    }

    private fun block(id: String, speaker: String?, text: String) = ScreenContract.Block(
        id = id,
        kind = ScreenContract.KIND_CHAT,
        speaker = speaker,
        text = text,
        messageAt = null,
        bbox = null
    )
}
