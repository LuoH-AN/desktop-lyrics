package com.tcrrry.desktoplyrics

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Uses the same store as the overlay; no second lyric cache. */
class LyricSourceManagerActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private var busy = false
    private var selected: String? = null
    private var message = ""
    private var songQuery = ""
    private fun col(res: Int) = ContextCompat.getColor(this, res)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundResource(R.drawable.bg_main_screen)
            addView(content)
        })
        render()
    }
    private fun entries(): List<JSONObject> {
        val array = runCatching { JSONArray(prefs.getString("match_memory_v2", "[]").orEmpty().ifBlank { "[]" }) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    private fun save(values: List<JSONObject>, notify: Boolean = true) {
        prefs.edit().putString("match_memory_v2", JSONArray(values).toString()).apply()
        if (notify && LyricsOverlayService.isRunning) startService(Intent(this, LyricsOverlayService::class.java).apply {
            action = "com.tcrrry.desktoplyrics.REFRESH_MATCHES"
        })
    }
    private fun update(key: String, change: (JSONObject) -> Unit) {
        val values = entries()
        values.find { it.optString("key") == key }?.let(change)
        save(values)
        render()
    }
    private fun render() {
        content.removeAllViews()
        content.addView(label("‹  歌词源管理", 25f).apply {
            setOnClickListener { if (selected != null) { selected = null; render() } else finish() }
        })
        content.addView(label("按歌曲整理尝试过的歌词版本，选择来源后可预览与切换。记录持续保留，可随时手动清理。", 13f))
        if (message.isNotBlank()) content.addView(label(message, 14f))
        val values = entries()
        val entry = values.find { it.optString("key") == selected }
        if (entry == null) {
            content.addView(button("清除所有歌词源缓存") {
                save(emptyList()); selected = null; message = "已清除所有匹配记忆和历史版本，恢复自动匹配"; render()
            }.apply {
                isEnabled = values.isNotEmpty() && !busy
            })
            content.addView(label("仅清除这里的匹配记忆和历史版本，不影响翻译语言包与偏移设置。", 12f))
            val input = EditText(this).apply {
                hint = "搜索已记录的歌名或歌手"; textSize = 14f
                setTextColor(col(R.color.text_primary)); setHintTextColor(col(R.color.text_tertiary))
                setSingleLine(true); setPadding(dp(14),dp(8),dp(14),dp(8))
                setBackgroundResource(R.drawable.bg_ui_pill); setText(songQuery)
            }
            content.addView(input, LinearLayout.LayoutParams(-1,dp(50)).apply { topMargin = dp(12) })
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            content.addView(list)
            fun fillSongs() {
                list.removeAllViews()
                val groups = values.asReversed().groupBy {
                    it.optString("title").trim().lowercase() + "\u0000" + it.optString("artist").trim().lowercase()
                }.values.filter { group -> group.any {
                    (it.optString("title") + " " + it.optString("artist")).contains(songQuery.trim(), ignoreCase = true)
                } }
                if(groups.isEmpty()) list.addView(label(if(values.isEmpty()) "暂无重匹配记录" else "没有符合搜索条件的歌曲", 16f))
                groups.forEach { group ->
                    val item = group.first()
                    list.addView(card().apply {
                        addView(label(item.optString("title").ifBlank { "未知歌曲" },18f))
                        addView(label(item.optString("artist"),13f))
                        group.forEach { source ->
                            val name = source.optString("source").ifBlank { source.optJSONObject("candidate")?.optString("source").orEmpty() }
                            val seconds = source.optLong("duration") / 1000
                            val variant = if(group.count { it.optString("source") == name } > 1) " · ${seconds / 60}:${(seconds % 60).toString().padStart(2,'0')}" else ""
                            addView(button("$name$variant · 查看版本与预览  ›") {
                                selected = source.optString("key"); message = ""; render()
                            })
                        }
                    })
                }
            }
            fillSongs()
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { songQuery = s.toString(); fillSongs() }
                override fun afterTextChanged(s: Editable?) {}
            })
            return
        }
        val key = entry.optString("key")
        if (entry.optBoolean("needsReview")) content.addView(label("这是旧版保存的选择，暂不自动应用，请核对歌词后重新选用或删除。", 14f))
        content.addView(label(entry.optString("title"), 20f))
        content.addView(button(if (busy) "正在搜索…" else "搜索下一个匹配结果（仅预览）") { search(entry) }.apply { isEnabled = !busy })
        entry.optJSONObject("original")?.let { original ->
            content.addView(button("恢复最初的歌词") { update(key) { it.put("candidate", original).put("needsReview", false) }; message = "已恢复最初的歌词"; render() })
        }
        content.addView(button("删除记忆 / 恢复自动匹配") {
            save(entries().filter { it.optString("key") != key }); selected = null; message = "已删除，下次搜索使用自动匹配"; render()
        })
        val history = entry.optJSONArray("history") ?: JSONArray().put(entry.optJSONObject("candidate"))
        for (i in 0 until history.length()) {
            val candidate = history.optJSONObject(i) ?: continue
            val current = !entry.optBoolean("needsReview") && candidate.optString("recordId") == entry.optJSONObject("candidate")?.optString("recordId")
            content.addView(card().apply {
                addView(label((if(current) "✓ 当前选择 · " else "") + candidate.optString("title").ifBlank { entry.optString("title").ifBlank { "旧版候选" } }, 17f))
                addView(label(candidate.optString("artist").ifBlank { entry.optString("artist") } + " · " + candidate.optString("source"), 13f))
                val seconds = candidate.optLong("duration") / 1000
                addView(label("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · " +
                    (if(candidate.optString("wordLyrics").isNotBlank()) "含逐字数据" else "普通歌词") +
                    (if(candidate.optString("translatedLyrics").isNotBlank()) " · 含译文" else "") + "\n记录：" + candidate.optString("recordId"), 12f))
                val preview = candidate.optString("lyrics").lineSequence().map { it.replace(Regex("^(\\[[^]]+])+"), "").trim() }
                    .filter { it.isNotBlank() }.take(16).joinToString("\n")
                addView(label(preview, 14f))
                addView(button(if(current) "正在使用" else "选用这个版本") {
                    update(key) { it.put("candidate", candidate).put("needsReview", false).put("at", System.currentTimeMillis()) }
                }.apply { isEnabled = !current && !busy })
            })
        }
    }
    private fun search(entry: JSONObject) {
        if (busy) return
        if (entry.optString("title").isBlank()) { message = "旧版记录缺少歌曲信息，请播放这首歌后再双击一次以补齐。"; render(); return }
        busy = true; message = ""; render()
        val key = entry.optString("key")
        val history = entry.optJSONArray("history") ?: JSONArray().put(entry.optJSONObject("candidate"))
        val excluded = (0 until history.length()).mapNotNull { history.optJSONObject(it)?.optString("recordId") }.filter { it.isNotBlank() }.toSet()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val repository = DirectLyricsRepository()
                try { runCatching {
                    repository.rematch(entry.optString("source"), entry.optString("title"), entry.optString("artist"),
                        entry.optString("album"), entry.optLong("duration"), excluded)?.toJson()
                } } finally { repository.close() }
            }
            busy = false
            val candidate = result.getOrNull()
            val values = entries()
            val latest = values.find { it.optString("key") == key }
            if (candidate != null && latest != null) {
                candidate.remove("alternatives")
                val recent = latest.optJSONArray("history") ?: history
                if ((0 until recent.length()).none { recent.optJSONObject(it)?.optString("recordId") == candidate.optString("recordId") }) recent.put(candidate)
                latest.put("history", recent).put("at", System.currentTimeMillis())
                save(values, false)
                message = "已找到新候选，预览后点击选用。当前歌词保持不变。"
            } else message = if (result.isFailure) "请求失败或来源暂时不可用，请稍后重试。" else "没有更多可靠的匹配结果，已保留现有版本。"
            render()
        }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(if(size >= 17) col(R.color.text_primary) else col(R.color.text_secondary))
        setPadding(0, dp(6), 0, dp(8))
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12))
        setBackgroundResource(R.drawable.bg_ui_card)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(12) }
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f; setTextColor(col(R.color.text_primary))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        setBackgroundResource(R.drawable.bg_ui_pill)
        layoutParams = LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }
}
