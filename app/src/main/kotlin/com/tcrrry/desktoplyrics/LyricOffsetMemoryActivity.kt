package com.tcrrry.desktoplyrics

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.Locale

class LyricOffsetMemoryActivity : AppCompatActivity() {
    private val prefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private lateinit var content: LinearLayout

    private data class Entry(
        val id: String,
        val title: String,
        val artist: String,
        val source: String,
        val offsetMs: Int,
        val updatedAt: Long
    )

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
            setBackgroundResource(R.drawable.bg_main_screen)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundResource(R.drawable.bg_main_screen)
            addView(content)
        })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "‹  偏移记忆"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), 0, dp(10))
            setOnClickListener { finish() }
        })
        content.addView(text("每首歌、每个歌词源独立保存。删除后再次播放会恢复为 +0.0s。", 12f).apply {
            setTextColor(Color.rgb(144, 151, 169))
            setPadding(dp(2), 0, 0, dp(4))
        })

        val entries = readEntries()
        if (entries.isEmpty()) {
            content.addView(card().apply {
                gravity = Gravity.CENTER
                addView(text("还没有自定义偏移记忆", 15f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(170, 177, 194))
                })
            })
            return
        }

        content.addView(button("重置全部偏移记忆") { showClearDialog() }.apply {
            setTextColor(Color.rgb(255, 176, 184))
        })
        entries.forEach { entry ->
            val panel = card()
            panel.addView(text(entry.title.ifBlank { "未知歌曲" }, 17f).apply {
                setTextColor(Color.WHITE)
            })
            if (entry.artist.isNotBlank()) panel.addView(text(entry.artist, 12f).apply {
                setTextColor(Color.rgb(150, 158, 177)); setPadding(0, 2, 0, 0)
            })
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            detail.addView(text(entry.source.ifBlank { "歌词源" }, 12f).apply {
                setTextColor(Color.rgb(174, 187, 255))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            detail.addView(text(formatOffset(entry.offsetMs), 14f).apply {
                setTextColor(Color.WHITE)
            })
            panel.addView(detail)
            panel.addView(button("删除这条记忆") { delete(entry) }.apply {
                setTextColor(Color.rgb(255, 184, 190))
            })
            content.addView(panel)
        }
    }

    private fun readEntries(): List<Entry> {
        val index = runCatching {
            JSONObject(prefs.getString(LyricsOverlayService.PREF_LYRIC_OFFSET_INDEX, "{}").orEmpty().ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        return index.keys().asSequence().mapNotNull { id ->
            val value = index.optJSONObject(id) ?: return@mapNotNull null
            val identity = value.optString("identity")
            val fallback = identity.split('\u0000', limit = 2)
            Entry(
                id = id,
                title = value.optString("title").ifBlank { fallback.getOrNull(0).orEmpty() },
                artist = value.optString("artist").ifBlank { fallback.getOrNull(1).orEmpty() },
                source = value.optString("source"),
                offsetMs = value.optInt("offsetMs", 0),
                updatedAt = value.optLong("updatedAt", 0)
            ).takeIf { it.offsetMs != 0 }
        }.sortedByDescending { it.updatedAt }.toList()
    }

    private fun delete(entry: Entry) {
        val key = LyricsOverlayService.PREF_LYRIC_OFFSET_ENTRY_PREFIX + entry.id
        removeIndexEntry(entry.id, key)
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_DELETE_LYRIC_OFFSET_MEMORY
                putExtra(LyricsOverlayService.EXTRA_LYRIC_OFFSET_MEMORY_KEY, key)
            })
        }
        render()
    }

    private fun removeIndexEntry(id: String, preferenceKey: String) {
        val index = runCatching {
            JSONObject(prefs.getString(LyricsOverlayService.PREF_LYRIC_OFFSET_INDEX, "{}").orEmpty().ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        index.remove(id)
        prefs.edit().remove(preferenceKey)
            .putString(LyricsOverlayService.PREF_LYRIC_OFFSET_INDEX, index.toString()).apply()
    }

    private fun clearAll() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(LyricsOverlayService.PREF_LYRIC_OFFSET_ENTRY_PREFIX) }
            .forEach { editor.remove(it) }
        editor.remove(LyricsOverlayService.PREF_LYRIC_OFFSET_INDEX)
            .putInt(LyricsOverlayService.PREF_LYRIC_OFFSET_MS, 0).apply()
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_CLEAR_LYRIC_OFFSET_MEMORIES
            })
        }
        render()
    }

    private fun showClearDialog() {
        val dialog = Dialog(this)
        val panel = card().apply {
            addView(text("重置全部偏移？", 20f).apply { setTextColor(Color.WHITE) })
            addView(text("所有歌曲和歌词源都会恢复为 +0.0s。", 12f).apply {
                setTextColor(Color.rgb(158, 165, 182)); setPadding(0, 4, 0, 8)
            })
            addView(button("确认重置") { dialog.dismiss(); clearAll() }.apply {
                setBackgroundResource(R.drawable.bg_ui_primary_button)
            })
            addView(button("取消") { dialog.dismiss() }.apply { alpha = .72f })
        }
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            width = resources.displayMetrics.widthPixels - dp(36)
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(16))
        setBackgroundResource(R.drawable.bg_ui_card)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        includeFontPadding = false
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundResource(R.drawable.bg_ui_pill)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) }
        setOnClickListener { action() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.975f).scaleY(.975f).alpha(.86f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()
            }
            false
        }
    }

    private fun formatOffset(value: Int): String =
        String.format(Locale.ROOT, "%+.1fs", value / 1000f)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
