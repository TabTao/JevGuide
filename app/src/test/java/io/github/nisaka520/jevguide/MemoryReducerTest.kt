package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReducerTest {

    private val base = 1_760_000_000_000L

    private fun turn(
        text: String,
        ts: Long,
        mine: Boolean = false,
        sender: String? = "张三",
        source: TurnSource = TurnSource.EXPORT
    ) = Turn(ts = ts, mine = mine, text = text, sender = sender, source = source, approx = false)

    @Test
    fun `空记忆直接收下`() {
        val out = MemoryReducer.merge(emptyList(), listOf(turn("第一句", base), turn("第二句", base + 60_000)))
        assertEquals(2, out.size)
    }

    @Test
    fun `同一批导入两次不会翻倍`() {
        val batch = listOf(turn("第二句", base + 60_000), turn("第一句", base))
        val once = MemoryReducer.merge(emptyList(), batch)
        val twice = MemoryReducer.merge(once, batch)
        assertEquals(once.size, twice.size)
        // merge 会按时间排序，所以顺序是"第一句 → 第二句"
        assertEquals(listOf("第一句", "第二句"), twice.map { it.text })
    }

    @Test
    fun `截图识别错一个字仍判为同一条 且保留导出文本`() {
        val exported = listOf(turn("明天下午三点老地方见", base))
        val ocr = listOf(turn("明天下午三点老地方現", base + 30_000, source = TurnSource.OCR))
        val out = MemoryReducer.merge(exported, ocr)
        assertEquals(1, out.size)
        assertEquals("明天下午三点老地方见", out[0].text)
        assertEquals(TurnSource.EXPORT, out[0].source)
    }

    @Test
    fun `短消息相隔很久不算同一条`() {
        val out = MemoryReducer.merge(
            listOf(turn("好", base)),
            listOf(turn("好", base + 10 * 60_000L))
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `群里不同人说的同一句话不会被合并`() {
        val out = MemoryReducer.merge(
            listOf(turn("收到，我明天过去", base, sender = "张三")),
            listOf(turn("收到，我明天过去", base + 10_000, sender = "李四"))
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `低优先级来源只补缺失字段 不覆盖文本`() {
        val live = listOf(turn("在吗", base, sender = null, source = TurnSource.LIVE))
        val exported = listOf(turn("在吗", base, sender = "张三", source = TurnSource.EXPORT))
        val out = MemoryReducer.merge(live, exported)
        assertEquals(1, out.size)
        assertEquals("张三", out[0].sender)
        assertEquals(TurnSource.EXPORT, out[0].source)
    }

    @Test
    fun `超过上限时保留最新的`() {
        val many = (0 until 10).map { turn("第 $it 句内容", base + it * 60_000L) }
        val out = MemoryReducer.merge(emptyList(), many, cap = 4)
        assertEquals(4, out.size)
        assertEquals("第 6 句内容", out.first().text)
        assertEquals("第 9 句内容", out.last().text)
    }

    @Test
    fun `相似度函数基本行为`() {
        assertTrue(MemoryReducer.similarity("明天下午三点老地方见", "明天下午三点老地方現") > 0.85)
        assertTrue(MemoryReducer.similarity("明天下午三点", "完全不同的一句子") < 0.3)
    }
}
