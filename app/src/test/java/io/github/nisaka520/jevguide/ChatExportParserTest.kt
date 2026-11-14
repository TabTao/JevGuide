package io.github.nisaka520.jevguide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExportParserTest {

    // 全部用合成数据，不使用任何真实聊天内容
    private val sample = listOf(
        "张三 和 我本人 在微信上的聊天记录如下，请查收。",
        "",
        "——— 2026-11-09 ———",
        "",
        "",
        "张三 17:17",
        "",
        "第一行",
        "第二行",
        "",
        "我本人 17:20",
        "",
        "收到",
        "",
        "——— 2026-11-10 ———",
        "",
        "",
        "张三 09:01",
        "",
        "早上好"
    ).joinToString("\n")

    @Test
    fun `解析头部与消息`() {
        val r = ChatExportParser.parse(sample, me = "我本人")
        assertNotNull(r.header)
        assertEquals("张三", r.header!!.peer)
        assertEquals("我本人", r.header!!.me)
        assertEquals(3, r.messages.size)
    }

    @Test
    fun `mine 按我的昵称判定`() {
        val r = ChatExportParser.parse(sample, me = "我本人")
        assertEquals(listOf(false, true, false), r.messages.map { it.mine })
    }

    @Test
    fun `多行正文保留换行 日期能落成时间戳`() {
        val r = ChatExportParser.parse(sample, me = "我本人")
        val first = r.messages.first()
        assertEquals("第一行\n第二行", first.text)
        assertTrue("时间戳应可解析", first.ts > 0L)
        assertTrue("后一条应晚于前一条", r.messages[1].ts > first.ts)
        assertEquals(TurnSource.EXPORT, first.source)
        assertFalse(first.approx)
        assertEquals("张三", first.sender)
    }

    @Test
    fun `昵称里的 PUA 图标与零宽字符会被清掉`() {
        val pua = "\uE008" // 微信塞进昵称的 PUA 图标
        val zw = "\u200B"
        val raw = listOf(
            "${pua}张三 和 ${pua}我本人 在微信上的聊天记录如下，请查收。",
            "",
            "——— 2026-11-09 ———",
            "",
            "",
            "${pua}张三$zw 10:00",
            "",
            "平A"
        ).joinToString("\n")
        val r = ChatExportParser.parse(raw, me = "我本人")
        assertEquals("张三", r.header!!.peer)
        assertEquals("张三", r.messages.first().sender)
        assertFalse("别人的消息不该被判成 mine", r.messages.first().mine)
    }

    @Test
    fun `探针落盘文件的外壳会被剥掉`() {
        val wrapped = "action   : android.intent.action.SEND_MULTIPLE\n" +
            "type     : message/rfc822\n\n" +
            "===== PAYLOAD =====\n" + sample
        val r = ChatExportParser.parse(wrapped, me = "我本人")
        assertEquals(3, r.messages.size)
    }

    @Test
    fun `没有给我昵称时不猜 mine`() {
        val r = ChatExportParser.parse(sample, me = null)
        assertTrue(r.messages.all { !it.mine })
        assertTrue(r.messages.all { it.sender != null })
    }
}
