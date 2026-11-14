package io.github.nisaka520.jevguide

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导入向导：从微信「多选 → 信封」分享过来的聊天记录，落进这位联系人的长期记忆。
 *
 * 设计取舍：
 *  - **先确认再写**：分享面板点一下就会进来，误触的代价不能是"记忆被灌垃圾"，
 *    所以先展示解析结果（会话名/条数/时间跨度），确认后才落盘。
 *  - **关系/性别/发展关系在这里一次设完**：导入本来就是低频动作，顺手把关系定了，
 *    省得用户再跑去设置页；关系直接影响后续所有判读。
 *  - **失败不静默**：解析不出消息时把原因写清楚，并给"复制正文"作为兜底，
 *    原始文本不会凭空消失。
 */
class ImportActivity : Activity() {

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)

    private lateinit var cfg: Config
    private var rawText: String = ""
    private var parsed: ChatExportParser.Result? = null

    private lateinit var infoView: TextView
    private lateinit var peerInput: EditText
    private lateinit var meInput: EditText
    private lateinit var goalInput: EditText
    private lateinit var relationSpinner: Spinner
    private lateinit var sexSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        rawText = extractText(intent)
        parsed = ChatExportParser.parse(rawText, cfg.myNickname.ifEmpty { null })
        buildUi()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        rawText = extractText(intent)
        parsed = ChatExportParser.parse(rawText, cfg.myNickname.ifEmpty { null })
        buildUi()
    }

    // ───────────────────────── 收 intent ─────────────────────────

    /** 微信实测走 SEND_MULTIPLE + message/rfc822，正文在 EXTRA_TEXT 里；兼容逐项 ClipData */
    private fun extractText(intent: Intent?): String {
        if (intent == null) return ""
        val sb = StringBuilder()

        val single = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        if (single != null) sb.append(single.toString())

        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val t = clip.getItemAt(i).text
                if (t != null && t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(t.toString())
                }
            }
        }

        // 有些版本把正文塞在 SUBJECT 里，兜一层
        if (sb.isEmpty()) {
            intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.let { sb.append(it.toString()) }
        }
        return sb.toString()
    }

    // ───────────────────────── UI ─────────────────────────

    private fun buildUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "导入聊天记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(Typeface.DEFAULT_BOLD)
        })

        val r = parsed
        infoView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, dp(4))
            text = when {
                rawText.isBlank() -> "没收到任何文字（分享内容为空）"
                r == null || r.messages.isEmpty() -> "没解析出消息。可能是新格式，正文已保留可复制。"
                else -> {
                    val first = r.messages.first()
                    val last = r.messages.last()
                    val span = if (first.ts > 0 && last.ts > 0) {
                        "${fmt.format(Date(first.ts))} → ${fmt.format(Date(last.ts))}"
                    } else {
                        "时间未知"
                    }
                    "会话：${r.header?.peer ?: "未识别"}\n" +
                        "条数：${r.messages.size}\n" +
                        "跨度：$span\n" +
                        "来源：信封导出"
                }
            }
        }
        root.addView(infoView)

        if (r == null || r.messages.isEmpty()) {
            root.addView(button("复制正文") {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("jevguide-import", rawText))
                toast("已复制 ${rawText.length} 字")
            })
            root.addView(button("关闭") { finish() })
            setContentView(scroll)
            return
        }

        peerInput = edit("会话名（写入记忆用）", r.header?.peer.orEmpty().ifEmpty { "未命名" })
        root.addView(label("会话名"))
        root.addView(peerInput)

        meInput = edit("我的微信昵称（判「我」用）", cfg.myNickname.ifEmpty { r.header?.me.orEmpty() })
        root.addView(label("我的昵称"))
        root.addView(meInput)

        root.addView(label("关系"))
        relationSpinner = spinner(Contacts.RELATIONS)
        root.addView(relationSpinner)

        root.addView(label("对方性别"))
        sexSpinner = spinner(Contacts.SEXES)
        root.addView(sexSpinner)

        root.addView(label("发展关系（可留空）"))
        goalInput = edit("留空 = 无要求；例：想更熟 / 想确定关系 / 只谈工作", "")
        goalInput.inputType = InputType.TYPE_CLASS_TEXT
        root.addView(goalInput)

        // 已存在的联系人：预填当前值，避免导入把它覆盖回默认
        val existing = cfg.contacts().firstOrNull { sameName(it, peerInput.text.toString()) }
        if (existing != null) {
            relationSpinner.setSelection(Contacts.RELATIONS.indexOf(existing.relation).coerceAtLeast(0))
            sexSpinner.setSelection(Contacts.SEXES.indexOf(existing.sex).coerceAtLeast(0))
            goalInput.setText(existing.developGoal)
        }

        root.addView(button("导入并写入记忆") { doImport() })
        root.addView(button("取消") { finish() })

        setContentView(scroll)
    }

    private fun sameName(c: Contact, name: String): Boolean {
        val target = Contacts.normalize(name)
        if (target.isEmpty()) return false
        return c.names.any { Contacts.normalize(it) == target }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@ImportActivity, android.R.layout.simple_spinner_dropdown_item, items)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ───────────────────────── 写入 ─────────────────────────

    private fun doImport() {
        val name = peerInput.text.toString().trim().ifEmpty { "未命名" }
        val me = meInput.text.toString().trim()
        if (me.isEmpty()) {
            toast("先填「我的微信昵称」，否则分不清哪句是你说的")
            return
        }

        // 用「确认后的昵称」重新解析一次：用户可能改过昵称，mine 要跟着变
        val r = ChatExportParser.parse(rawText, me)
        if (r.messages.isEmpty()) {
            toast("没有可导入的消息")
            return
        }

        val key = MemKeys.of(name)
        val before = Memories.load(this, key, name)
        val after = Memories.importTurns(this, key, name, r.messages)
        val added = after.turns.size - before.turns.size

        saveContact(name, me)

        AppLog.add("导入聊天记录：$name ${r.messages.size} 条，新增 $added 条（现在共 ${after.turns.size} 条）")
        toast("已导入：新增 $added 条，记忆里现在 ${after.turns.size} 条")
        finish()
    }

    /** 关系/性别/发展关系顺手写进联系人表；已存在就更新，不存在就新建 */
    private fun saveContact(name: String, me: String) {
        val relation = relationSpinner.selectedItem?.toString() ?: "普通朋友"
        val sex = sexSpinner.selectedItem?.toString() ?: "未知"
        val goal = goalInput.text.toString().trim()

        val list = cfg.contacts().toMutableList()
        val idx = list.indexOfFirst { c ->
            c.names.any { Contacts.normalize(it) == Contacts.normalize(name) }
        }
        if (idx >= 0) {
            val old = list[idx]
            val names = if (old.names.any { Contacts.normalize(it) == Contacts.normalize(name) }) {
                old.names
            } else {
                old.names + name
            }
            list[idx] = old.copy(names = names, relation = relation, sex = sex, developGoal = goal)
        } else {
            list.add(Contact(names = listOf(name), relation = relation, sex = sex, note = "", developGoal = goal))
        }
        cfg.saveContacts(list)
        cfg.myNickname = me
    }
}

