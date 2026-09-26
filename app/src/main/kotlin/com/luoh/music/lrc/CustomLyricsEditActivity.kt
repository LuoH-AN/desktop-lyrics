package com.luoh.music.lrc

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 为一首歌粘贴或导入 LRC 歌词；保存后主页和悬浮窗都优先使用它。 */
class CustomLyricsEditActivity : AppCompatActivity() {
    private lateinit var titleField: EditText
    private lateinit var artistField: EditText
    private lateinit var lyricsField: EditText
    private lateinit var status: TextView
    private var editingId: String? = null
    private var savedSnapshot = ""

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    private fun col(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        // 可能从悬浮窗直接打开，此时主页还没设置过主题
        ThemePrefs.apply(getSharedPreferences(ThemePrefs.PREFS, Context.MODE_PRIVATE).getString(ThemePrefs.KEY, ThemePrefs.FOLLOW))
        super.onCreate(state)
        val requestedTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val requestedArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
        val existing = intent.getStringExtra(EXTRA_ID)?.let { CustomLyricsStore.get(this, it) }
            ?: CustomLyricsStore.find(this, requestedTitle, requestedArtist)
        editingId = existing?.id

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_main_screen)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(16), dp(6))
        }
        header.addView(TextView(this).apply {
            text = if (existing == null) "‹  添加自定义歌词" else "‹  编辑自定义歌词"
            textSize = 21f
            setTextColor(col(R.color.text_primary))
            setPadding(dp(2), dp(8), 0, dp(8))
            setOnClickListener { leave() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply {
            text = "保存"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(col(R.color.text_on_accent))
            setBackgroundResource(R.drawable.bg_flat_button)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(-2, dp(38)))
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), dp(32))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        content.addView(text("保存后，这首歌在主页和悬浮窗都直接使用这份歌词，不再联网搜索；删除即恢复自动匹配。", 12f).apply {
            setTextColor(col(R.color.text_tertiary))
            setPadding(dp(2), 0, 0, dp(4))
        })

        val info = card()
        info.addView(text("歌名", 13f))
        titleField = field("必填，与播放器显示的歌名一致").apply { setText(existing?.title ?: requestedTitle) }
        info.addView(titleField)
        info.addView(text("歌手", 13f).apply { setPadding(0, dp(12), 0, 0) })
        artistField = field("留空则所有同名歌曲都使用这份歌词").apply { setText(existing?.artist ?: requestedArtist) }
        info.addView(artistField)
        content.addView(info)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("导入 .lrc 文件") { importLauncher.launch(arrayOf("*/*")) },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5); topMargin = dp(12) })
        actions.addView(button("粘贴剪贴板") { pasteClipboard() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5); topMargin = dp(12) })
        content.addView(actions)

        lyricsField = EditText(this).apply {
            hint = "[00:12.30]第一句歌词\n[00:16.85]第二句歌词\n[00:16.85]同一时间戳的第二行作为译文"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(col(R.color.text_primary))
            setHintTextColor(col(R.color.text_tertiary))
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 12
            setHorizontallyScrolling(false)
            setBackgroundResource(R.drawable.bg_ui_input)
            setText(existing?.lyrics.orEmpty())
        }
        content.addView(lyricsField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        status = text("", 13f).apply { setPadding(dp(2), dp(10), 0, 0) }
        content.addView(status)
        content.addView(text(
            "格式：每行以 [分:秒.毫秒] 开头，例如 [01:23.45]歌词。同一时间戳写两行时，第二行作为译文显示；支持 [offset:毫秒] 整体偏移。",
            12f
        ).apply {
            setTextColor(col(R.color.text_tertiary))
            setPadding(dp(2), dp(6), 0, 0)
        })
        if (existing != null) {
            content.addView(button("删除这份自定义歌词") { confirmDelete(existing) }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
            })
        }

        lyricsField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateStatus()
        })
        updateStatus()
        savedSnapshot = snapshot()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }

    private fun snapshot(): String =
        listOf(titleField.text, artistField.text, lyricsField.text).joinToString("\u0000")

    private fun updateStatus() {
        val raw = lyricsField.text.toString()
        if (raw.isBlank()) {
            status.text = "粘贴、输入或导入 LRC 歌词"
            status.setTextColor(col(R.color.text_tertiary))
            return
        }
        status.setTextColor(col(R.color.text_secondary))
        if (raw.length > CustomLyricsStore.MAX_LENGTH) {
            status.text = "歌词太长，请删减到 ${CustomLyricsStore.MAX_LENGTH / 1000}K 字符以内"
            return
        }
        val parsed = CustomLyricsStore.parse(raw)
        status.text = if (parsed.lineCount == 0) {
            "没有识别到带时间轴的歌词行"
        } else buildString {
            append("识别到 ${parsed.lineCount} 行歌词")
            if (parsed.translationCount > 0) append(" · ${parsed.translationCount} 行译文")
            if (parsed.ignoredCount > 0) append(" · ${parsed.ignoredCount} 行没有时间轴，已忽略")
        }
    }

    private fun save() {
        val title = titleField.text.toString().trim()
        val artist = artistField.text.toString().trim()
        val raw = lyricsField.text.toString().trim()
        if (title.isEmpty()) {
            toast("请填写歌名")
            titleField.requestFocus()
            return
        }
        if (raw.length > CustomLyricsStore.MAX_LENGTH) {
            toast("歌词太长，请删减后再保存")
            return
        }
        val parsed = CustomLyricsStore.parse(raw)
        if (parsed.lineCount == 0) {
            toast("没有识别到带时间轴的歌词行，格式示例：[01:23.45]歌词")
            return
        }
        CustomLyricsStore.save(this, title, artist, raw, editingId)
        LyricsOverlayService.instance?.reloadCustomLyrics()
        toast("已保存 · ${parsed.lineCount} 行歌词")
        finish()
    }

    private fun leave() {
        if (snapshot() == savedSnapshot) {
            finish()
            return
        }
        confirm("放弃未保存的修改？", "刚才输入或导入的歌词不会保存。", "放弃修改") { finish() }
    }

    private fun confirmDelete(entry: CustomLyricsStore.Entry) {
        confirm("删除这份自定义歌词？", "《${entry.title}》会恢复自动匹配歌词。", "确认删除") {
            CustomLyricsStore.delete(this, entry.id)
            LyricsOverlayService.instance?.reloadCustomLyrics()
            toast("已删除，恢复自动匹配")
            finish()
        }
    }

    private fun pasteClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast("剪贴板里没有文字")
            return
        }
        if (lyricsField.hasFocus()) {
            // 正在编辑时按光标位置插入，和系统粘贴一致
            val start = lyricsField.selectionStart.coerceAtLeast(0)
            val end = lyricsField.selectionEnd.coerceAtLeast(0)
            lyricsField.text.replace(minOf(start, end), maxOf(start, end), text)
        } else {
            lyricsField.setText(text.trim())
        }
        fillFromTags(text)
    }

    private fun importFile(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    if (output.size() > MAX_FILE_BYTES) error("File too large")
                }
                decodeText(output.toByteArray())
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            toast("无法读取这个文件，请确认是文本格式的 LRC 歌词")
            return
        }
        lyricsField.setText(text.trim())
        fillFromTags(text)
        val parsed = CustomLyricsStore.parse(text)
        toast(if (parsed.lineCount > 0) "已导入 ${parsed.lineCount} 行歌词" else "文件里没有识别到带时间轴的歌词行")
    }

    /** 歌名、歌手还空着时，用 LRC 里的 [ti:] / [ar:] 标签补上。 */
    private fun fillFromTags(raw: String) {
        val tags = CustomLyricsStore.metadata(raw)
        if (titleField.text.isBlank()) tags["ti"]?.takeIf { it.isNotBlank() }?.let(titleField::setText)
        if (artistField.text.isBlank()) tags["ar"]?.takeIf { it.isNotBlank() }?.let(artistField::setText)
    }

    /** LRC 文件常见 UTF-8（可能带 BOM）、UTF-16 和 GBK 编码。 */
    private fun decodeText(bytes: ByteArray): String {
        val text = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrElse { String(bytes, Charset.forName("GB18030")) }
        }
        return text.removePrefix(0xFEFF.toChar().toString())
    }

    private fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        val panel = card().apply {
            addView(text(title, 19f).apply { setTextColor(col(R.color.text_primary)) })
            addView(text(message, 12f).apply { setPadding(0, dp(4), 0, dp(8)) })
            addView(button(action) { dialog.dismiss(); onConfirm() }.apply {
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

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(col(R.color.text_secondary))
    }

    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        isSingleLine = true
        setTextColor(col(R.color.text_primary))
        setHintTextColor(col(R.color.text_tertiary))
        setBackgroundResource(R.drawable.bg_ui_input)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(16))
        setBackgroundResource(R.drawable.bg_ui_card)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
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

    companion object {
        private const val EXTRA_ID = "custom_lyrics_id"
        private const val EXTRA_TITLE = "custom_lyrics_title"
        private const val EXTRA_ARTIST = "custom_lyrics_artist"
        private const val MAX_FILE_BYTES = 1_000_000

        /** 按歌名/歌手打开：已有自定义歌词就编辑它，否则新建并预填。 */
        fun intent(context: Context, title: String, artist: String): Intent =
            Intent(context, CustomLyricsEditActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)

        fun editIntent(context: Context, id: String): Intent =
            Intent(context, CustomLyricsEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
