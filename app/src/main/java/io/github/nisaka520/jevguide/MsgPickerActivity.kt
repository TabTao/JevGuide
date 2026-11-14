package io.github.nisaka520.jevguide

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 悬浮条 → 消息选择列表之间的临时数据。
 *
 * 用内存持有而不是 Intent 序列化：Digest 里是标题 + 若干条文本，转成 Parcelable
 * 除了多写一堆样板没有任何收益，而且这个 Activity 永远只由同一个进程拉起。
 */
object MsgPicker {
    @Volatile
    var pending: Digest? = null
}

/**
 * 「2 点判读」的第二点：从当前屏读到的消息里挑一条来判。
 *
 * 为什么要有它：悬浮条一键判读只能判「最后一条对方消息」，而聊天里真正想分析的
 * 往往是上面几条（对方先撩完你又补一句、群里某条突然的阴阳怪气）。让用户点一下，
 * 目标就精确了；上下文仍然是同一屏里的对话，不需要滚动采集。
 */
class MsgPickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = MsgPicker.pending
        if (d == null || d.msgs.isEmpty()) {
            finish()
            return
        }
        setContentView(build(d))
    }

    private fun build(d: Digest): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "选择要判读的消息"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(Typeface.DEFAULT_BOLD)
        })
        root.addView(TextView(this).apply {
            text = "共 ${d.msgs.size} 条 · 会话：${d.title.ifEmpty { "未识别" }}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, dp(10))
        })

        d.msgs.forEachIndexed { i, m ->
            root.addView(TextView(this).apply {
                text = (if (m.mine) "我：" else "对方：") + m.text.replace("\n", " ")
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                // 用底色区分我/对方，比加前缀更省视觉带宽
                setBackgroundColor(if (m.mine) 0x2233CC66 else 0x22CC6633)
                setOnClickListener { pick(d, i) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        root.addView(Button(this).apply {
            text = "取消"
            setOnClickListener {
                MsgPicker.pending = null
                finish()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        return scroll
    }

    private fun pick(d: Digest, index: Int) {
        MsgPicker.pending = null
        Analyzer.run(this, d, manual = true, targetIndex = index)
        finish()
    }

    override fun onDestroy() {
        MsgPicker.pending = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
