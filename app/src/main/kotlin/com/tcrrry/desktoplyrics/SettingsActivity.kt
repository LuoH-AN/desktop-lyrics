package com.tcrrry.desktoplyrics

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 独立设置页（黑白灰扁平纯边框分组列表）。
 * 承接原 MainActivity 里的全部设置逻辑；主页改为全屏歌词。
 */
class SettingsActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val appPrefs by lazy {
        getSharedPreferences(ThemePrefs.PREFS, Context.MODE_PRIVATE)
    }

    private lateinit var listenerState: TextView
    private lateinit var overlayState: TextView
    private lateinit var overlayToggleState: TextView
    private lateinit var themeFollow: TextView
    private lateinit var themeLight: TextView
    private lateinit var themeDark: TextView
    private lateinit var settingsTargetExpanded: TextView
    private lateinit var settingsTargetCompact: TextView
    private lateinit var backgroundModeTransparent: TextView
    private lateinit var backgroundModeLow: TextView
    private lateinit var backgroundModeMedium: TextView
    private lateinit var backgroundModeHigh: TextView
    private lateinit var seekFontSize: SeekBar
    private lateinit var fontSizeValue: TextView
    private lateinit var seekLyricOffset: SeekBar
    private lateinit var lyricOffsetValue: TextView
    private lateinit var lyricColorWhite: TextView
    private lateinit var lyricColorBlue: TextView
    private lateinit var lyricColorBlack: TextView
    private lateinit var lyricColorPink: TextView
    private lateinit var lyricColorCustom: TextView
    private lateinit var translationOriginal: TextView
    private lateinit var translationBilingual: TextView
    private lateinit var translationTranslated: TextView
    private lateinit var versionValue: TextView

    private var settingsTargetIsCompact = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.settings_back).setOnClickListener { finish() }

        listenerState = findViewById(R.id.listener_permission_state)
        overlayState = findViewById(R.id.overlay_permission_state)
        overlayToggleState = findViewById(R.id.overlay_toggle_state)
        themeFollow = findViewById(R.id.theme_follow)
        themeLight = findViewById(R.id.theme_light)
        themeDark = findViewById(R.id.theme_dark)
        settingsTargetExpanded = findViewById(R.id.settings_target_expanded)
        settingsTargetCompact = findViewById(R.id.settings_target_compact)
        backgroundModeTransparent = findViewById(R.id.background_mode_transparent)
        backgroundModeLow = findViewById(R.id.background_mode_low)
        backgroundModeMedium = findViewById(R.id.background_mode_medium)
        backgroundModeHigh = findViewById(R.id.background_mode_high)
        seekFontSize = findViewById(R.id.seek_font_size)
        fontSizeValue = findViewById(R.id.font_size_value)
        seekLyricOffset = findViewById(R.id.seek_lyric_offset)
        lyricOffsetValue = findViewById(R.id.lyric_offset_value)
        lyricColorWhite = findViewById(R.id.lyric_color_white)
        lyricColorBlue = findViewById(R.id.lyric_color_blue)
        lyricColorBlack = findViewById(R.id.lyric_color_black)
        lyricColorPink = findViewById(R.id.lyric_color_pink)
        lyricColorCustom = findViewById(R.id.lyric_color_custom)
        translationOriginal = findViewById(R.id.translation_original)
        translationBilingual = findViewById(R.id.translation_bilingual)
        translationTranslated = findViewById(R.id.translation_translated)
        versionValue = findViewById(R.id.version_value)
        versionValue.text = currentVersionName

        // 悬浮窗开关
        findViewById<View>(R.id.cell_overlay_toggle).setOnClickListener { toggleOverlay() }
        // 权限
        findViewById<View>(R.id.cell_listener_permission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<View>(R.id.cell_overlay_permission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // 主题
        themeFollow.setOnClickListener { setTheme(ThemePrefs.FOLLOW) }
        themeLight.setOnClickListener { setTheme(ThemePrefs.LIGHT) }
        themeDark.setOnClickListener { setTheme(ThemePrefs.DARK) }

        // 设置对象
        settingsTargetExpanded.setOnClickListener { setSettingsTarget(false) }
        settingsTargetCompact.setOnClickListener { setSettingsTarget(true) }

        // 动态背景
        backgroundModeTransparent.setOnClickListener { setBackgroundMode(LyricsOverlayService.BACKGROUND_TRANSPARENT) }
        backgroundModeLow.setOnClickListener { setBackgroundMode(LyricsOverlayService.BACKGROUND_LOW) }
        backgroundModeMedium.setOnClickListener { setBackgroundMode(LyricsOverlayService.BACKGROUND_MEDIUM) }
        backgroundModeHigh.setOnClickListener { setBackgroundMode(LyricsOverlayService.BACKGROUND_HIGH) }

        // 字号
        seekFontSize.max = LyricsOverlayService.FONT_SCALE_MAX_PERCENT -
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT
        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = LyricsOverlayService.FONT_SCALE_MIN_PERCENT + progress
                fontSizeValue.text = "$percent%"
                if (fromUser) setFontScale(percent)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        // 偏移
        seekLyricOffset.max = 100
        seekLyricOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val offsetMs = LyricsOverlayService.LYRIC_OFFSET_MIN_MS + progress * 100
                lyricOffsetValue.text = formatOffset(offsetMs)
                if (fromUser) setLyricOffset(offsetMs)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        // 颜色
        listOf(
            lyricColorWhite to "#FFFFFF",
            lyricColorBlue to "#9FD8FF",
            lyricColorBlack to "#111111",
            lyricColorPink to "#FFB6D5"
        ).forEach { (option, color) -> option.setOnClickListener { setLyricColor(color) } }
        lyricColorCustom.setOnClickListener { showColorPickerDialog() }

        // 翻译
        translationOriginal.setOnClickListener { setTranslationMode(LyricsOverlayService.TRANSLATION_ORIGINAL) }
        translationBilingual.setOnClickListener { setTranslationMode(LyricsOverlayService.TRANSLATION_BILINGUAL) }
        translationTranslated.setOnClickListener { setTranslationMode(LyricsOverlayService.TRANSLATION_TRANSLATED) }

        // 管理
        findViewById<View>(R.id.cell_supplement_translation).setOnClickListener {
            startActivity(Intent(this, TranslationSettingsActivity::class.java))
        }
        findViewById<View>(R.id.cell_manage_sources).setOnClickListener {
            startActivity(Intent(this, LyricSourceManagerActivity::class.java))
        }
        findViewById<View>(R.id.cell_manage_offsets).setOnClickListener {
            startActivity(Intent(this, LyricOffsetMemoryActivity::class.java))
        }
        // 关于
        findViewById<View>(R.id.cell_check_update).setOnClickListener { checkForUpdates() }
        findViewById<View>(R.id.cell_bilibili).setOnClickListener {
            openUrl("https://space.bilibili.com/")
        }
        findViewById<View>(R.id.cell_github).setOnClickListener {
            openUrl("https://github.com/tcrrry/desktop-lyrics")
        }
        findViewById<View>(R.id.cell_privacy).setOnClickListener {
            openUrl("https://github.com/tcrrry/desktop-lyrics/blob/main/PRIVACY.md")
        }

        refreshAll()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show() }
    }

    // ---------- 悬浮窗开关 ----------
    private fun toggleOverlay() {
        if (LyricsOverlayService.isRunning) {
            stopService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_STOP
            })
            overlayToggleState.postDelayed({ updatePermissionStates() }, 250)
            return
        }
        if (!hasNotificationListenerAccess()) {
            Toast.makeText(this, "请先授予通知使用权", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许显示悬浮窗", Toast.LENGTH_SHORT).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            ); return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
            }
        )
        overlayToggleState.postDelayed({ updatePermissionStates() }, 250)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        updatePermissionStates()
        updateThemeUi()
        updateSettingsTargetUi()
        updateBackgroundModeUi()
        updateFontSizeUi()
        updateLyricOffsetUi()
        updateLyricColorUi()
        updateTranslationModeUi()
    }

    // ---------- 主题 ----------
    private fun setTheme(mode: String) {
        appPrefs.edit().putString(ThemePrefs.KEY, mode).apply()
        ThemePrefs.apply(mode)
        updateThemeUi()
    }

    private fun updateThemeUi() {
        val mode = appPrefs.getString(ThemePrefs.KEY, ThemePrefs.FOLLOW) ?: ThemePrefs.FOLLOW
        applySeg(
            listOf(
                themeFollow to ThemePrefs.FOLLOW,
                themeLight to ThemePrefs.LIGHT,
                themeDark to ThemePrefs.DARK
            ),
            mode
        )
    }

    // ---------- 权限状态 ----------
    private fun hasNotificationListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun updatePermissionStates() {
        val listenerOk = hasNotificationListenerAccess()
        val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        listenerState.text = if (listenerOk) "已授权" else "去开启"
        overlayState.text = if (overlayOk) "已授权" else "去开启"
        overlayToggleState.text = if (LyricsOverlayService.isRunning) "运行中" else "已关闭"
    }

    // ---------- 设置对象 ----------
    private fun setSettingsTarget(compact: Boolean) {
        if (settingsTargetIsCompact == compact) return
        settingsTargetIsCompact = compact
        updateSettingsTargetUi()
        updateBackgroundModeUi()
        updateFontSizeUi()
        updateLyricColorUi()
    }

    private fun updateSettingsTargetUi() {
        applySeg(
            listOf(settingsTargetExpanded to "expanded", settingsTargetCompact to "compact"),
            if (settingsTargetIsCompact) "compact" else "expanded"
        )
    }

    // ---------- 动态背景 ----------
    private fun setBackgroundMode(mode: String) {
        val normalized = when (mode) {
            LyricsOverlayService.BACKGROUND_LOW,
            LyricsOverlayService.BACKGROUND_MEDIUM,
            LyricsOverlayService.BACKGROUND_HIGH -> mode
            else -> LyricsOverlayService.BACKGROUND_TRANSPARENT
        }
        overlayPrefs.edit().putString(backgroundPreferenceKey(), normalized).apply()
        updateBackgroundModeUi()
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_BACKGROUND
                putExtra(LyricsOverlayService.EXTRA_BACKGROUND_MODE, normalized)
                putExtra(LyricsOverlayService.EXTRA_TARGET_COMPACT, settingsTargetIsCompact)
            })
        }
    }

    private fun updateBackgroundModeUi() {
        val selected = overlayPrefs.getString(backgroundPreferenceKey(), expandedBackgroundMode())
        val known = setOf(
            LyricsOverlayService.BACKGROUND_TRANSPARENT,
            LyricsOverlayService.BACKGROUND_LOW,
            LyricsOverlayService.BACKGROUND_MEDIUM,
            LyricsOverlayService.BACKGROUND_HIGH
        )
        val effective = if (selected in known) selected!! else LyricsOverlayService.BACKGROUND_DEFAULT
        applySeg(
            listOf(
                backgroundModeTransparent to LyricsOverlayService.BACKGROUND_TRANSPARENT,
                backgroundModeLow to LyricsOverlayService.BACKGROUND_LOW,
                backgroundModeMedium to LyricsOverlayService.BACKGROUND_MEDIUM,
                backgroundModeHigh to LyricsOverlayService.BACKGROUND_HIGH
            ),
            effective
        )
    }

    // ---------- 字号 ----------
    private fun setFontScale(percent: Int) {
        val normalized = percent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        val previous = overlayPrefs.getInt(fontScalePreferenceKey(), expandedFontScale()).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        val editor = overlayPrefs.edit().putInt(fontScalePreferenceKey(), normalized)
        if (settingsTargetIsCompact) {
            val density = resources.displayMetrics.density
            fun minHeightPx(value: Int): Int =
                (LyricsOverlayService.compactMinimumHeightDp(value) * density + 0.5f).toInt()
            val storedHeight = overlayPrefs.getInt("compact_height_v3", (48 * density + 0.5f).toInt())
            val previousMin = minHeightPx(previous)
            val nextMin = minHeightPx(normalized)
            val adjustedHeight = if (storedHeight <= previousMin + (2 * density + 0.5f).toInt()) {
                nextMin
            } else {
                maxOf(storedHeight, nextMin)
            }
            editor.putInt("compact_height_v3", adjustedHeight)
        }
        editor.apply()
        fontSizeValue.text = "$normalized%"
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_FONT_SCALE
                putExtra(LyricsOverlayService.EXTRA_FONT_SCALE_PERCENT, normalized)
                putExtra(LyricsOverlayService.EXTRA_TARGET_COMPACT, settingsTargetIsCompact)
            })
        }
    }

    private fun updateFontSizeUi() {
        val percent = overlayPrefs.getInt(fontScalePreferenceKey(), expandedFontScale()).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        fontSizeValue.text = "$percent%"
        seekFontSize.progress = percent - LyricsOverlayService.FONT_SCALE_MIN_PERCENT
    }

    // ---------- 偏移 ----------
    private fun setLyricOffset(value: Int) {
        val normalized = value.coerceIn(
            LyricsOverlayService.LYRIC_OFFSET_MIN_MS,
            LyricsOverlayService.LYRIC_OFFSET_MAX_MS
        )
        overlayPrefs.edit().putInt(LyricsOverlayService.PREF_LYRIC_OFFSET_MS, normalized).apply()
        lyricOffsetValue.text = formatOffset(normalized)
        seekLyricOffset.progress = (normalized - LyricsOverlayService.LYRIC_OFFSET_MIN_MS) / 100
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_LYRIC_OFFSET
                putExtra(LyricsOverlayService.EXTRA_LYRIC_OFFSET_MS, normalized)
            })
        }
    }

    private fun updateLyricOffsetUi() {
        val value = (LyricsOverlayService.instance?.currentLyricOffsetMs()
            ?: overlayPrefs.getInt(LyricsOverlayService.PREF_LYRIC_OFFSET_MS, 0))
            .coerceIn(LyricsOverlayService.LYRIC_OFFSET_MIN_MS, LyricsOverlayService.LYRIC_OFFSET_MAX_MS)
        lyricOffsetValue.text = formatOffset(value)
        seekLyricOffset.progress = (value - LyricsOverlayService.LYRIC_OFFSET_MIN_MS) / 100
    }

    private fun formatOffset(value: Int): String =
        String.format(java.util.Locale.ROOT, "%+.1fs", value / 1000f)

    // ---------- 颜色 ----------
    private fun setLyricColor(color: String) {
        overlayPrefs.edit().putString(lyricColorPreferenceKey(), color).apply()
        updateLyricColorUi()
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_LYRIC_COLOR
                putExtra(LyricsOverlayService.EXTRA_LYRIC_COLOR, color)
                putExtra(LyricsOverlayService.EXTRA_TARGET_COMPACT, settingsTargetIsCompact)
            })
        }
    }

    private fun updateLyricColorUi() {
        val selected = overlayPrefs.getString(lyricColorPreferenceKey(), expandedLyricColor()).orEmpty()
        val options = listOf(
            lyricColorWhite to "#FFFFFF",
            lyricColorBlue to "#9FD8FF",
            lyricColorBlack to "#111111",
            lyricColorPink to "#FFB6D5"
        )
        options.forEach { (option, color) ->
            val isSelected = color.equals(selected, ignoreCase = true)
            option.alpha = if (isSelected) 1f else 0.5f
            // 选中的圆点加个描边框（用 background ring）
            option.background = if (isSelected) ringDrawable() else null
        }
        val normalized = selected.uppercase(java.util.Locale.ROOT)
        val isCustom = options.none { (_, color) -> color == normalized }
        lyricColorCustom.background = if (isCustom) ringDrawable() else null
    }

    private fun ringDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke((resources.displayMetrics.density * 2f + .5f).toInt(), resolveColor(R.color.accent))
    }

    private fun resolveColor(res: Int): Int = ContextCompat.getColor(this, res)

    // ---------- 翻译 ----------
    private fun setTranslationMode(mode: String) {
        val normalized = when (mode) {
            LyricsOverlayService.TRANSLATION_ORIGINAL -> LyricsOverlayService.TRANSLATION_ORIGINAL
            LyricsOverlayService.TRANSLATION_TRANSLATED -> LyricsOverlayService.TRANSLATION_TRANSLATED
            else -> LyricsOverlayService.TRANSLATION_BILINGUAL
        }
        overlayPrefs.edit().putString(LyricsOverlayService.PREF_TRANSLATION_MODE, normalized).apply()
        updateTranslationModeUi()
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_TRANSLATION_MODE
                putExtra(LyricsOverlayService.EXTRA_TRANSLATION_MODE, normalized)
            })
        }
    }

    private fun updateTranslationModeUi() {
        val selected = overlayPrefs.getString(
            LyricsOverlayService.PREF_TRANSLATION_MODE,
            LyricsOverlayService.TRANSLATION_BILINGUAL
        ).orEmpty()
        applySeg(
            listOf(
                translationOriginal to LyricsOverlayService.TRANSLATION_ORIGINAL,
                translationBilingual to LyricsOverlayService.TRANSLATION_BILINGUAL,
                translationTranslated to LyricsOverlayService.TRANSLATION_TRANSLATED
            ),
            selected
        )
    }

    // ---------- 分段选中态：黑白灰（选中=实心 accent + on-accent 文字） ----------
    private fun applySeg(options: List<Pair<TextView, String>>, selected: String) {
        val onAccent = resolveColor(R.color.text_on_accent)
        val secondary = resolveColor(R.color.text_secondary)
        options.forEach { (option, value) ->
            val isSelected = value == selected
            option.setBackgroundResource(if (isSelected) R.drawable.bg_seg_on else android.R.color.transparent)
            option.setTextColor(if (isSelected) onAccent else secondary)
            option.typeface = android.graphics.Typeface.create(
                "sans-serif",
                if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    // ---------- 无级调色对话框（沿用旧布局） ----------
    private fun showColorPickerDialog() {
        val picker = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val preview = picker.findViewById<TextView>(R.id.color_picker_preview)
        val red = picker.findViewById<SeekBar>(R.id.seek_color_red)
        val green = picker.findViewById<SeekBar>(R.id.seek_color_green)
        val blue = picker.findViewById<SeekBar>(R.id.seek_color_blue)
        val redValue = picker.findViewById<TextView>(R.id.color_red_value)
        val greenValue = picker.findViewById<TextView>(R.id.color_green_value)
        val blueValue = picker.findViewById<TextView>(R.id.color_blue_value)
        val initialHex = overlayPrefs.getString(lyricColorPreferenceKey(), expandedLyricColor())
            .orEmpty().takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }
            ?: LyricsOverlayService.LYRIC_COLOR_DEFAULT
        val initial = Color.parseColor(initialHex)
        red.progress = Color.red(initial)
        green.progress = Color.green(initial)
        blue.progress = Color.blue(initial)
        var selectedHex = initialHex.uppercase(java.util.Locale.ROOT)

        fun updatePreview() {
            val r = red.progress; val g = green.progress; val b = blue.progress
            selectedHex = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", r, g, b)
            redValue.text = r.toString(); greenValue.text = g.toString(); blueValue.text = b.toString()
            preview.text = selectedHex
            preview.setTextColor(if (r * 299 + g * 587 + b * 114 > 150_000) Color.BLACK else Color.WHITE)
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 13f * resources.displayMetrics.density
                setColor(Color.rgb(r, g, b))
                setStroke((resources.displayMetrics.density + .5f).toInt(), Color.parseColor("#33808080"))
            }
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = updatePreview()
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        red.setOnSeekBarChangeListener(listener)
        green.setOnSeekBarChangeListener(listener)
        blue.setOnSeekBarChangeListener(listener)
        updatePreview()

        val dialog = Dialog(this).apply {
            setContentView(picker)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(.6f)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        picker.findViewById<Button>(R.id.color_picker_cancel).setOnClickListener { dialog.dismiss() }
        picker.findViewById<Button>(R.id.color_picker_apply).setOnClickListener {
            setLyricColor(selectedHex)
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(
            resources.displayMetrics.widthPixels - (36 * resources.displayMetrics.density).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    // ---------- 更新检查 ----------
    private fun checkForUpdates() {
        versionValue.text = "检查中…"
        lifecycleScope.launch {
            val result = UpdateChecker.fetchLatest(currentVersionName)
            versionValue.text = currentVersionName
            result.onSuccess { release ->
                val newer = UpdateChecker.isNewer(release.version, currentVersionName)
                if (newer) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
                } else {
                    Toast.makeText(this@SettingsActivity, "当前已是最新版", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "暂时无法检查更新，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 偏好 key ----------
    private fun backgroundPreferenceKey(): String = if (settingsTargetIsCompact) {
        LyricsOverlayService.PREF_BACKGROUND_MODE_COMPACT
    } else LyricsOverlayService.PREF_BACKGROUND_MODE

    private fun fontScalePreferenceKey(): String = if (settingsTargetIsCompact) {
        LyricsOverlayService.PREF_FONT_SCALE_COMPACT_PERCENT
    } else LyricsOverlayService.PREF_FONT_SCALE_PERCENT

    private fun lyricColorPreferenceKey(): String = if (settingsTargetIsCompact) {
        LyricsOverlayService.PREF_LYRIC_COLOR_COMPACT
    } else LyricsOverlayService.PREF_LYRIC_COLOR

    private fun expandedBackgroundMode(): String = overlayPrefs.getString(
        LyricsOverlayService.PREF_BACKGROUND_MODE,
        LyricsOverlayService.BACKGROUND_DEFAULT
    ).orEmpty().ifBlank { LyricsOverlayService.BACKGROUND_DEFAULT }

    private fun expandedFontScale(): Int = overlayPrefs.getInt(
        LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
        LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
    )

    private fun expandedLyricColor(): String = overlayPrefs.getString(
        LyricsOverlayService.PREF_LYRIC_COLOR,
        LyricsOverlayService.LYRIC_COLOR_DEFAULT
    ).orEmpty().ifBlank { LyricsOverlayService.LYRIC_COLOR_DEFAULT }

    private val currentVersionName: String
        get() = packageManager.getPackageInfo(packageName, 0).versionName
            .orEmpty().substringBefore('-').ifBlank { "1.07" }
}
