package io.github.nisaka520.jevguide

/**
 * 三种来源（导出 / 截图识别 / 实时抓屏）写进同一份记忆时的**确定性归并**。
 *
 * 为什么不能交给模型：同一批数据两次跑结论不同、没法单测；而且模型会把真的连发两条「好」
 * 合并成一条 —— 那是数据丢失，不是去重。模型只负责之后的摘要压缩。
 *
 * 归并顺序（前一条命中就返回）：
 *  1. 精确同一：归一化文本相同、同侧、发送者兼容，且时间相差 ≤2 分钟（任一方没有时间也算）
 *  2. 近似同一：文本相似度 ≥0.85、同侧、发送者兼容、时间相差 ≤2 分钟（OCR 错字容错）
 *  3. 都不命中 → 追加
 *
 * 冲突处理：同一条两种来源都有时，保留**高优先级**那份（导出 > 截图 > 抓屏），
 * 另一份只用来补缺失字段（例如导出里是 `[语音]`、截图里有转文字）。
 */
object MemoryReducer {

    /** 判定「同一条消息」的时间窗 */
    internal const val NEAR_WINDOW_MS = 2 * 60 * 1000L

    /** 近似匹配阈值 */
    internal const val SIMILARITY = 0.85

    /** 太短的消息（"好"、"嗯"）不做近似匹配，否则会把真·连发吃掉 */
    private const val MIN_FUZZY_LEN = 6

    private val RE_DIGITS = Regex("""\d+""")

    /**
     * 数字必须完全一致才允许近似匹配。
     * 真实反例：「第 0 句内容」vs「第 1 句内容」相似度 0.875，只差一个数字但是两条不同消息。
     * OCR 错字几乎都发生在汉字上，把数字钉死不会伤到容错。
     */
    private fun sameDigits(a: String, b: String): Boolean =
        RE_DIGITS.findAll(a).joinToString(",") { it.value } ==
            RE_DIGITS.findAll(b).joinToString(",") { it.value }

    private fun rank(s: TurnSource): Int = when (s) {
        TurnSource.EXPORT -> 3
        TurnSource.OCR -> 2
        TurnSource.LIVE -> 1
    }

    /** 归并：existing 保持原顺序，incoming 里真正的新消息追加到末尾，最后按 cap 截断 */
    fun merge(existing: List<Turn>, incoming: List<Turn>, cap: Int = Memories.MAX_TURNS): List<Turn> {
        val out = ArrayList<Turn>(existing.size + incoming.size)
        out.addAll(existing)

        for (t in incoming) {
            if (t.text.isBlank()) continue
            val idx = out.indexOfFirst { sameMessage(it, t) }
            if (idx < 0) {
                out.add(t)
                continue
            }
            val cur = out[idx]
            when {
                // 新来源更可信：换成新的，但保留已知的时间/侧别（抓屏的时间是"现在"，不可信）
                rank(t.source) > rank(cur.source) -> out[idx] = t.copy(
                    ts = if (cur.ts > 0L) cur.ts else t.ts,
                    mine = cur.mine,
                    approx = cur.approx && t.approx
                )
                // 同来源：只补缺失字段
                t.source == cur.source -> out[idx] = cur.copy(
                    sender = cur.sender ?: t.sender,
                    text = if (cur.text.length >= t.text.length) cur.text else t.text,
                    approx = cur.approx && t.approx
                )
                // 低优先级来源：只补空
                else -> if (cur.sender == null && t.sender != null) out[idx] = cur.copy(sender = t.sender)
            }
        }

        val sorted = if (out.all { it.ts > 0L }) out.sortedBy { it.ts } else out
        return if (sorted.size <= cap) sorted else ArrayList(sorted.subList(sorted.size - cap, sorted.size))
    }

    internal fun sameMessage(a: Turn, b: Turn): Boolean {
        if (a.mine != b.mine) return false
        val na = normalize(a.text)
        val nb = normalize(b.text)
        if (na.isEmpty() || nb.isEmpty()) return false

        // 发送者：两边都有值时必须一致；群聊里张三和李四说同一句话不算同一条
        val sa = a.sender?.let { ChatExportParser.cleanName(it) }
        val sb = b.sender?.let { ChatExportParser.cleanName(it) }
        if (sa != null && sb != null && sa != sb) return false

        val bothTimed = a.ts > 0L && b.ts > 0L
        val near = !bothTimed || kotlin.math.abs(a.ts - b.ts) <= NEAR_WINDOW_MS

        if (na == nb) return near

        if (!bothTimed || !near) return false
        if (na.length < MIN_FUZZY_LEN || nb.length < MIN_FUZZY_LEN) return false
        if (!sameDigits(na, nb)) return false
        return similarity(na, nb) >= SIMILARITY
    }

    /** 归一化：去 PUA/零宽、折叠空白、去首尾标点。只用于比对，不用于展示 */
    internal fun normalize(s: String): String {
        val cleaned = ChatExportParser.cleanName(s)
            .replace(Regex("""\s+"""), " ")
            .trim()
        return cleaned.trim(' ', '。', '，', ',', '.', '！', '？', '!', '?', '~', '～', '、')
    }

    /**
     * 归一化编辑距离相似度（0..1）。
     * 文本一般很短（聊天消息），O(n·m) 完全够用；长度差超过 50% 直接判不相似。
     */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val la = a.length
        val lb = b.length
        if (la == 0 || lb == 0) return 0.0
        if (kotlin.math.abs(la - lb) > maxOf(la, lb) / 2) return 0.0

        var prev = IntArray(lb + 1) { it }
        var cur = IntArray(lb + 1)
        for (i in 1..la) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..lb) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        val dist = prev[lb]
        return 1.0 - dist.toDouble() / maxOf(la, lb)
    }
}
