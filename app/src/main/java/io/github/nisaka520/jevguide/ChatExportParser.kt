package io.github.nisaka520.jevguide

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 解析微信「多选 → 信封」导出的聊天记录文本。
 *
 * 实测格式（2026-11，微信 8.0.79）：
 *
 * ```
 * 张三 和 李四 在微信上的聊天记录如下，请查收。
 *
 * ——— 2026-11-09 ———
 *
 *
 * 张三 17:17
 *
 * 正文第一行
 * 正文第二行
 *
 * 李四 17:20
 * ```
 *
 * 三个坑（都有真机样本）：
 *  - 日期分隔线是全角破折号（`———`），不是 ASCII `-`
 *  - 昵称里混着微信自己的 PUA 图标（U+E000–U+F8FF）和零宽字符
 *  - 昵称本身可能带空格，所以时间必须从**行尾**取
 */
object ChatExportParser {

    /** 头部：`<对方> 和 <我> 在微信上的聊天记录如下` */
    data class Header(val peer: String, val me: String)

    data class Result(val header: Header?, val messages: List<Turn>)

    private val PAYLOAD_SEP = "===== PAYLOAD ====="
    private val RE_HEADER = Regex("""^(.+?)\s*和\s*(.+?)\s*在微信上的聊天记录如下""")
    private val RE_DATE = Regex("""^[\-—–－]{3,}\s*(\d{4}-\d{2}-\d{2})\s*[\-—–－]{3,}$""")
    // 发送者行：昵称（可含空格）+ 行尾 HH:mm
    private val RE_SENDER = Regex("""^(.+?)\s+(\d{1,2}):(\d{2})$""")
    private val RE_SENTENCE_END = Regex("""[。！？.!?~～]$""")

    /**
     * 去掉微信往昵称里塞的 PUA 图标和零宽字符。
     * 不清掉的话，「我」的昵称永远匹配不上，mine 全错。
     */
    fun cleanName(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            val o = ch.code
            if (o in 0xE000..0xF8FF) continue          // PUA 图标
            if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF') continue
            append(ch)
        }
    }.trim()

    /**
     * @param raw 分享正文（可以是探针落盘的整份文件，会自动切掉元数据头）
     * @param me  我的微信昵称；给了才能判 mine，没给则全部按「对方」处理并把 sender 留着
     */
    fun parse(raw: String, me: String? = null): Result {
        val body = if (raw.contains(PAYLOAD_SEP)) raw.substringAfter(PAYLOAD_SEP) else raw
        val lines = body.replace("\r\n", "\n").split("\n")
        val meName = me?.let { cleanName(it) }?.takeIf { it.isNotEmpty() }

        var header: Header? = null
        var curDate: LocalDate? = null
        var sender: String? = null
        var time: LocalTime? = null
        val content = ArrayList<String>()
        val out = ArrayList<Turn>()

        fun flush() {
            val s = sender ?: return
            val text = content.joinToString("\n").trim('\n', ' ', '\t')
            if (text.isNotEmpty()) {
                val d = curDate
                val tm = time
                val ts = if (d != null && tm != null) {
                    LocalDateTime.of(d, tm).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else {
                    0L
                }
                val cleanSender = cleanName(s)
                out.add(
                    Turn(
                        ts = ts,
                        mine = meName != null && cleanSender == meName,
                        text = text,
                        sender = cleanSender.ifEmpty { null },
                        source = TurnSource.EXPORT,
                        approx = false
                    )
                )
            }
            content.clear()
            sender = null
            time = null
        }

        for (line in lines) {
            val s = line.trim()

            if (header == null) {
                val h = RE_HEADER.find(s)
                if (h != null) {
                    header = Header(cleanName(h.groupValues[1]), cleanName(h.groupValues[2]))
                    continue
                }
            }

            val d = RE_DATE.find(s)
            if (d != null) {
                flush()
                curDate = runCatching { LocalDate.parse(d.groupValues[1]) }.getOrNull()
                continue
            }

            val m = RE_SENDER.find(s)
            if (m != null && s.length <= 60 && !RE_SENTENCE_END.containsMatchIn(s)) {
                flush()
                sender = m.groupValues[1]
                val hh = m.groupValues[2].toIntOrNull() ?: 0
                val mm = m.groupValues[3].toIntOrNull() ?: 0
                time = runCatching { LocalTime.of(hh.coerceIn(0, 23), mm.coerceIn(0, 59)) }.getOrNull()
                continue
            }

            if (sender != null) content.add(line)
        }
        flush()

        return Result(header, out)
    }
}
