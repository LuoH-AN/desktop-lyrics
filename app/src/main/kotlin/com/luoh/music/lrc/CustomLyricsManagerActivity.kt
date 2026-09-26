package com.luoh.music.lrc

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
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 自定义歌词管理页：列出所有手动指定的 LRC 歌词，可为正在播放的歌新建，也可手动填歌名新建。
 * 每张卡片显示歌名/歌手/行数/更新时间，支持编辑与删除。
 */
class CustomLyricsManagerActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun col(res: Int) = ContextCompat.getColor(this, res)

    override fun onCreate(state: Bundle?) {
        ThemePrefs.apply(getSharedPreferences(ThemePrefs.PREFS, Context.MODE_PRIVATE).getString(ThemePrefs.KEY, ThemePrefs.FOLLOW))
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
            text = "‹  自定义歌词"
            textSize = 21f
            setTextColor(col(R.color.text_primary))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), 0, dp(10))
            setOnClickListener { finish() }
        })
        content.addView(text("获取不到歌词时，手动指定一份标准 LRC 时间轴歌词。按歌名+歌手匹配，命中后主页和悬浮窗都直接使用这份。", 12f).apply {
            setTextColor(col(R.color.text_tertiary))
            setPadding(dp(2), 0, 0, dp(4))
        })

        // 正在播放：给当前这首歌快速新建 / 编辑
        NowPlaying.current(this)?.let { track ->
            val existing = CustomLyricsStore.find(this, track.title, track.artist)
            val panel = card()
            panel.addView(text("正在播放", 12f).apply {
                setTextColor(col(R.color.text_tertiary))
            })
            panel.addView(text(track.title.ifBlank { "未知歌曲" }, 17f).apply {
                setTextColor(col(R.color.text_primary)); setPadding(0, dp(2), 0, 0)
            })
            if (track.artist.isNotBlank()) panel.addView(text(track.artist, 12f).apply {
                setTextColor(col(R.color.text_secondary)); setPadding(0, dp(2), 0, 0)
            })
            panel.addView(button(if (existing != null) "编辑这首歌的歌词" else "为这首歌指定 LRC 歌词") {
                startActivity(CustomLyricsEditActivity.intent(this, track.title, track.artist))
            }.apply {
                setBackgroundResource(R.drawable.bg_flat_button)
                setTextColor(col(R.color.text_on_accent))
            })
            content.addView(panel)
        }

        content.addView(button("手动填写歌名新建") {
            startActivity(CustomLyricsEditActivity.intent(this, "", ""))
        })

        val entries = CustomLyricsStore.all(this)
        if (entries.isEmpty()) {
            content.addView(card().apply {
                gravity = Gravity.CENTER
                addView(text("还没有自定义歌词", 15f).apply {
                    gravity = Gravity.CENTER
                    setTextColor(col(R.color.text_secondary))
                })
            })
            return
        }

        content.addView(text("已保存 ${entries.size} 份", 12f).apply {
            setTextColor(col(R.color.text_tertiary))
            setPadding(dp(2), dp(14), 0, 0)
        })
        entries.forEach { entry ->
            val panel = card()
            panel.addView(text(entry.title.ifBlank { "未命名" }, 17f).apply {
                setTextColor(col(R.color.text_primary))
            })
            panel.addView(text(entry.artist.ifBlank { "所有歌手" }, 12f).apply {
                setTextColor(col(R.color.text_secondary)); setPadding(0, dp(2), 0, 0)
            })
            val parsed = CustomLyricsStore.parse(entry.lyrics)
            val summary = buildString {
                append("${parsed.lineCount} 行歌词")
                if (parsed.translationCount > 0) append(" · 含译文")
                append(" · 更新于 ${timeFormat.format(Date(entry.updatedAt))}")
            }
            panel.addView(text(summary, 12f).apply {
                setTextColor(col(R.color.text_tertiary)); setPadding(0, dp(6), 0, 0)
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            actions.addView(button("编辑") {
                startActivity(CustomLyricsEditActivity.editIntent(this@CustomLyricsManagerActivity, entry.id))
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
            actions.addView(button("删除") { confirmDelete(entry) },
                LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
            panel.addView(actions)
            content.addView(panel)
        }
    }

    private fun confirmDelete(entry: CustomLyricsStore.Entry) {
        val dialog = Dialog(this)
        val panel = card().apply {
            addView(text("删除这份自定义歌词？", 19f).apply { setTextColor(col(R.color.text_primary)) })
            addView(text("${entry.title.ifBlank { "未命名" }}${if (entry.artist.isNotBlank()) " · ${entry.artist}" else ""}\n删除后这首歌会恢复自动匹配。", 12f).apply {
                setTextColor(col(R.color.text_secondary)); setPadding(0, dp(4), 0, dp(8))
            })
            addView(button("确认删除") {
                dialog.dismiss()
                CustomLyricsStore.delete(this@CustomLyricsManagerActivity, entry.id)
                LyricsOverlayService.instance?.reloadCustomLyrics()
                render()
            }.apply {
                setBackgroundResource(R.drawable.bg_flat_button)
                setTextColor(col(R.color.text_on_accent))
            })
            addView(button("取消") { dialog.dismiss() })
        }
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.55f)
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
        setTextColor(col(R.color.text_primary))
        setBackgroundResource(R.drawable.bg_ui_pill)
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
