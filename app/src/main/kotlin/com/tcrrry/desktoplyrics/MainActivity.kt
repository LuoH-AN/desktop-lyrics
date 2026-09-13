package com.tcrrry.desktoplyrics

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var btnOverlay: Button
    private lateinit var btnOverlayPermission: Button
    private lateinit var btnListenerPermission: Button
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvRuntimeBadge: TextView
    private lateinit var backgroundModeTransparent: TextView
    private lateinit var backgroundModeLow: TextView
    private lateinit var backgroundModeMedium: TextView
    private lateinit var backgroundModeHigh: TextView
    private lateinit var seekFontSize: SeekBar
    private lateinit var fontSizeValue: TextView
    private lateinit var seekLyricOffset: SeekBar
    private lateinit var lyricOffsetValue: TextView
    private lateinit var translationOriginal: TextView
    private lateinit var translationBilingual: TextView
    private lateinit var translationTranslated: TextView
    private lateinit var lyricColorWhite: TextView
    private lateinit var lyricColorBlue: TextView
    private lateinit var lyricColorBlack: TextView
    private lateinit var lyricColorPink: TextView
    private lateinit var lyricColorCustom: TextView
    private var overlayStateReceiverRegistered = false

    private val overlayStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LyricsOverlayService.ACTION_STATE_CHANGED &&
                ::tvOverlayStatus.isInitialized
            ) {
                updateOverlayUi()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_manage_lyric_sources).setOnClickListener {
            startActivity(Intent(this, LyricSourceManagerActivity::class.java))
        }
        findViewById<Button>(R.id.btn_supplement_translation).setOnClickListener {
            startActivity(Intent(this, TranslationSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_manage_lyric_offsets).setOnClickListener {
            startActivity(Intent(this, LyricOffsetMemoryActivity::class.java))
        }

        btnOverlay = findViewById(R.id.btn_overlay)
        btnOverlayPermission = findViewById(R.id.btn_overlay_permission)
        btnListenerPermission = findViewById(R.id.btn_listener_permission)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvRuntimeBadge = findViewById(R.id.tv_runtime_badge)
        backgroundModeTransparent = findViewById(R.id.background_mode_transparent)
        backgroundModeLow = findViewById(R.id.background_mode_low)
        backgroundModeMedium = findViewById(R.id.background_mode_medium)
        backgroundModeHigh = findViewById(R.id.background_mode_high)
        seekFontSize = findViewById(R.id.seek_font_size)
        fontSizeValue = findViewById(R.id.font_size_value)
        seekLyricOffset = findViewById(R.id.seek_lyric_offset)
        lyricOffsetValue = findViewById(R.id.lyric_offset_value)
        translationOriginal = findViewById(R.id.translation_original)
        translationBilingual = findViewById(R.id.translation_bilingual)
        translationTranslated = findViewById(R.id.translation_translated)
        lyricColorWhite = findViewById(R.id.lyric_color_white)
        lyricColorBlue = findViewById(R.id.lyric_color_blue)
        lyricColorBlack = findViewById(R.id.lyric_color_black)
        lyricColorPink = findViewById(R.id.lyric_color_pink)
        lyricColorCustom = findViewById(R.id.lyric_color_custom)
        seekFontSize.max = LyricsOverlayService.FONT_SCALE_MAX_PERCENT -
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT

        btnListenerPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        btnOverlay.setOnClickListener {
            if (LyricsOverlayService.isRunning) {
                stopService(Intent(this, LyricsOverlayService::class.java).apply {
                    action = LyricsOverlayService.ACTION_STOP
                })
                btnOverlay.postDelayed({ updateOverlayUi() }, 250)
                return@setOnClickListener
            }

            if (!hasNotificationListenerAccess()) {
                Toast.makeText(this, "请先授予通知使用权，用于读取 MediaSession", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先允许显示悬浮窗", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }

            startLyricsOverlay()
        }

        backgroundModeTransparent.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_TRANSPARENT)
        }
        backgroundModeLow.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_LOW)
        }
        backgroundModeMedium.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_MEDIUM)
        }
        backgroundModeHigh.setOnClickListener {
            setBackgroundMode(LyricsOverlayService.BACKGROUND_HIGH)
        }

        updateBackgroundModeUi()
        updateFontSizeUi()
        seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = LyricsOverlayService.FONT_SCALE_MIN_PERCENT + progress
                fontSizeValue.text = "$percent%"
                if (fromUser) setFontScale(percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        seekLyricOffset.max = 100
        seekLyricOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offsetMs = LyricsOverlayService.LYRIC_OFFSET_MIN_MS + progress * 100
                lyricOffsetValue.text = formatOffset(offsetMs)
                if (fromUser) setLyricOffset(offsetMs)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        translationOriginal.setOnClickListener {
            setTranslationMode(LyricsOverlayService.TRANSLATION_ORIGINAL)
        }
        translationBilingual.setOnClickListener {
            setTranslationMode(LyricsOverlayService.TRANSLATION_BILINGUAL)
        }
        translationTranslated.setOnClickListener {
            setTranslationMode(LyricsOverlayService.TRANSLATION_TRANSLATED)
        }

        listOf(
            lyricColorWhite to "#FFFFFF",
            lyricColorBlue to "#9FD8FF",
            lyricColorBlack to "#111111",
            lyricColorPink to "#FFB6D5"
        ).forEach { (option, color) -> option.setOnClickListener { setLyricColor(color) } }
        lyricColorCustom.setOnClickListener { showColorPickerDialog() }
        updateLyricOffsetUi()
        updateTranslationModeUi()
        updateLyricColorUi()
        updateOverlayUi()
    }

    override fun onStart() {
        super.onStart()
        if (!overlayStateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                overlayStateReceiver,
                IntentFilter(LyricsOverlayService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            overlayStateReceiverRegistered = true
        }
        updateOverlayUi()
    }

    override fun onStop() {
        if (overlayStateReceiverRegistered) {
            unregisterReceiver(overlayStateReceiver)
            overlayStateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::tvOverlayStatus.isInitialized) {
            updateOverlayUi()
            updateBackgroundModeUi()
            updateFontSizeUi()
            updateLyricOffsetUi()
            updateTranslationModeUi()
            updateLyricColorUi()
        }
    }

    private fun startLyricsOverlay() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
            }
        )
        btnOverlay.postDelayed({ updateOverlayUi() }, 250)
    }

    private fun hasNotificationListenerAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun setBackgroundMode(mode: String) {
        val normalized = when (mode) {
            LyricsOverlayService.BACKGROUND_LOW -> LyricsOverlayService.BACKGROUND_LOW
            LyricsOverlayService.BACKGROUND_MEDIUM -> LyricsOverlayService.BACKGROUND_MEDIUM
            LyricsOverlayService.BACKGROUND_HIGH -> LyricsOverlayService.BACKGROUND_HIGH
            else -> LyricsOverlayService.BACKGROUND_TRANSPARENT
        }
        overlayPrefs.edit()
            .putString(LyricsOverlayService.PREF_BACKGROUND_MODE, normalized)
            .apply()
        updateBackgroundModeUi()

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_BACKGROUND
                putExtra(LyricsOverlayService.EXTRA_BACKGROUND_MODE, normalized)
            })
        }
    }

    private fun setFontScale(percent: Int) {
        val normalized = percent.coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        val previous = overlayPrefs.getInt(
            LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
            LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
        ).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        val density = resources.displayMetrics.density
        fun minHeightPx(value: Int): Int =
            (LyricsOverlayService.compactMinimumHeightDp(value) * density + 0.5f).toInt()

        val storedHeight = overlayPrefs.getInt(
            "compact_height_v3",
            (48 * density + 0.5f).toInt()
        )
        val previousMin = minHeightPx(previous)
        val nextMin = minHeightPx(normalized)
        val adjustedHeight = if (storedHeight <= previousMin + (2 * density + 0.5f).toInt()) {
            nextMin
        } else {
            maxOf(storedHeight, nextMin)
        }

        overlayPrefs.edit()
            .putInt(LyricsOverlayService.PREF_FONT_SCALE_PERCENT, normalized)
            .putInt("compact_height_v3", adjustedHeight)
            .apply()
        fontSizeValue.text = "$normalized%"

        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_FONT_SCALE
                putExtra(LyricsOverlayService.EXTRA_FONT_SCALE_PERCENT, normalized)
            })
        }
    }

    private fun updateFontSizeUi() {
        val percent = overlayPrefs.getInt(
            LyricsOverlayService.PREF_FONT_SCALE_PERCENT,
            LyricsOverlayService.FONT_SCALE_DEFAULT_PERCENT
        ).coerceIn(
            LyricsOverlayService.FONT_SCALE_MIN_PERCENT,
            LyricsOverlayService.FONT_SCALE_MAX_PERCENT
        )
        fontSizeValue.text = "$percent%"
        seekFontSize.progress = percent - LyricsOverlayService.FONT_SCALE_MIN_PERCENT
    }

    private fun updateBackgroundModeUi() {
        val selectedMode = overlayPrefs.getString(
            LyricsOverlayService.PREF_BACKGROUND_MODE,
            LyricsOverlayService.BACKGROUND_DEFAULT
        )

        listOf(
            backgroundModeTransparent to LyricsOverlayService.BACKGROUND_TRANSPARENT,
            backgroundModeLow to LyricsOverlayService.BACKGROUND_LOW,
            backgroundModeMedium to LyricsOverlayService.BACKGROUND_MEDIUM,
            backgroundModeHigh to LyricsOverlayService.BACKGROUND_HIGH
        ).forEach { (option, mode) ->
            val selected = selectedMode == mode || (
                selectedMode !in setOf(
                    LyricsOverlayService.BACKGROUND_TRANSPARENT,
                    LyricsOverlayService.BACKGROUND_LOW,
                    LyricsOverlayService.BACKGROUND_MEDIUM,
                    LyricsOverlayService.BACKGROUND_HIGH
                ) && mode == LyricsOverlayService.BACKGROUND_DEFAULT
            )
            option.setBackgroundResource(
                if (selected) R.drawable.bg_ui_segment_selected else android.R.color.transparent
            )
            option.setTextColor(
                android.graphics.Color.parseColor(if (selected) "#202331" else "#9DA4B5")
            )
            option.typeface = android.graphics.Typeface.create(
                "sans-serif",
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

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

    private fun formatOffset(value: Int): String = String.format(
        java.util.Locale.ROOT,
        "%+.1fs",
        value / 1000f
    )

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
        )
        updateSegmentOptions(
            listOf(
                translationOriginal to LyricsOverlayService.TRANSLATION_ORIGINAL,
                translationBilingual to LyricsOverlayService.TRANSLATION_BILINGUAL,
                translationTranslated to LyricsOverlayService.TRANSLATION_TRANSLATED
            ),
            selected.orEmpty()
        )
    }

    private fun setLyricColor(color: String) {
        overlayPrefs.edit().putString(LyricsOverlayService.PREF_LYRIC_COLOR, color).apply()
        updateLyricColorUi()
        if (LyricsOverlayService.isRunning) {
            startService(Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_SET_LYRIC_COLOR
                putExtra(LyricsOverlayService.EXTRA_LYRIC_COLOR, color)
            })
        }
    }

    private fun updateLyricColorUi() {
        val selected = overlayPrefs.getString(
            LyricsOverlayService.PREF_LYRIC_COLOR,
            LyricsOverlayService.LYRIC_COLOR_DEFAULT
        ).orEmpty()
        val options = listOf(
            lyricColorWhite to "#FFFFFF",
            lyricColorBlue to "#9FD8FF",
            lyricColorBlack to "#111111",
            lyricColorPink to "#FFB6D5"
        )
        options.forEach { (option, color) ->
            val isSelected = color.equals(selected, ignoreCase = true)
            option.setBackgroundResource(
                if (isSelected) R.drawable.bg_ui_segment_selected else android.R.color.transparent
            )
            option.setTextColor(
                Color.parseColor(
                    if (isSelected) "#202331"
                    else if (color == "#111111") "#AEB3BF"
                    else color
                )
            )
            option.alpha = if (isSelected) 1f else 0.62f
        }
        val normalized = selected.uppercase(java.util.Locale.ROOT)
        val isCustom = options.none { (_, color) -> color == normalized }
        val customLabel = "无级调色 · $normalized"
        lyricColorCustom.text = SpannableString(customLabel).apply {
            val valueStart = customLabel.lastIndexOf(normalized)
            val actualColor = runCatching { Color.parseColor(normalized) }.getOrDefault(Color.WHITE)
            setSpan(
                ForegroundColorSpan(actualColor),
                valueStart,
                customLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        lyricColorCustom.setBackgroundResource(
            if (isCustom) R.drawable.bg_ui_segment_selected else R.drawable.bg_ui_pill
        )
        lyricColorCustom.setTextColor(Color.parseColor("#F7F7FA"))
    }

    private fun showColorPickerDialog() {
        val picker = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val preview = picker.findViewById<TextView>(R.id.color_picker_preview)
        val red = picker.findViewById<SeekBar>(R.id.seek_color_red)
        val green = picker.findViewById<SeekBar>(R.id.seek_color_green)
        val blue = picker.findViewById<SeekBar>(R.id.seek_color_blue)
        val redValue = picker.findViewById<TextView>(R.id.color_red_value)
        val greenValue = picker.findViewById<TextView>(R.id.color_green_value)
        val blueValue = picker.findViewById<TextView>(R.id.color_blue_value)
        val initialHex = overlayPrefs.getString(
            LyricsOverlayService.PREF_LYRIC_COLOR,
            LyricsOverlayService.LYRIC_COLOR_DEFAULT
        ).orEmpty().takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }
            ?: LyricsOverlayService.LYRIC_COLOR_DEFAULT
        val initial = Color.parseColor(initialHex)
        red.progress = Color.red(initial)
        green.progress = Color.green(initial)
        blue.progress = Color.blue(initial)
        var selectedHex = initialHex.uppercase(java.util.Locale.ROOT)

        fun updatePreview() {
            val r = red.progress
            val g = green.progress
            val b = blue.progress
            selectedHex = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", r, g, b)
            redValue.text = r.toString()
            greenValue.text = g.toString()
            blueValue.text = b.toString()
            preview.text = selectedHex
            preview.setTextColor(if (r * 299 + g * 587 + b * 114 > 150_000) Color.BLACK else Color.WHITE)
            preview.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(Color.rgb(r, g, b))
                setStroke((resources.displayMetrics.density + .5f).toInt(), Color.parseColor("#33FFFFFF"))
            }
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        red.setOnSeekBarChangeListener(listener)
        green.setOnSeekBarChangeListener(listener)
        blue.setOnSeekBarChangeListener(listener)
        updatePreview()

        val dialog = Dialog(this).apply {
            setContentView(picker)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setDimAmount(.64f)
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

    private fun updateSegmentOptions(options: List<Pair<TextView, String>>, selected: String) {
        options.forEach { (option, value) ->
            val isSelected = value == selected
            option.setBackgroundResource(
                if (isSelected) R.drawable.bg_ui_segment_selected else android.R.color.transparent
            )
            option.setTextColor(
                android.graphics.Color.parseColor(if (isSelected) "#202331" else "#9DA4B5")
            )
            option.typeface = android.graphics.Typeface.create(
                "sans-serif",
                if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    private fun updateOverlayUi() {
        val listenerGranted = hasNotificationListenerAccess()
        val overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
        val running = LyricsOverlayService.isRunning

        btnListenerPermission.text = if (listenerGranted) "✓ 通知使用权" else "通知使用权"
        btnOverlayPermission.text = if (overlayGranted) "✓ 悬浮窗权限" else "悬浮窗权限"
        btnOverlay.text = if (running) "关闭歌词悬浮窗" else "开启歌词悬浮窗"
        btnOverlay.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (running) "#C92842" else "#FA2D48")
        )

        val permissionsReady = listenerGranted && overlayGranted
        tvRuntimeBadge.text = when {
            running -> "运行中"
            permissionsReady -> "准备就绪"
            else -> "待授权"
        }
        tvRuntimeBadge.setTextColor(
            android.graphics.Color.parseColor(
                when {
                    running -> "#FF7388"
                    permissionsReady -> "#B8B8BE"
                    else -> "#FF9DAA"
                }
            )
        )
        tvRuntimeBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(
                when {
                    running -> "#264F1721"
                    permissionsReady -> "#262F2F34"
                    else -> "#263A151C"
                }
            )
        )

        tvOverlayStatus.text = when {
            running -> "已运行：系统回调实时同步，歌词进度在本机按帧推进"
            !listenerGranted && !overlayGranted -> "还需要授予“通知使用权”和“悬浮窗权限”"
            !listenerGranted -> "还需要通知使用权（读取第三方 MediaSession）"
            !overlayGranted -> "还需要悬浮窗权限"
            else -> "权限齐全，可以开启；无需给音乐 App 单独打开通知显示"
        }
    }
}
