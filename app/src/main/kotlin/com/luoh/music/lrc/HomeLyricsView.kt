package com.luoh.music.lrc

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import kotlin.math.abs

/** All visible home UI is native; the activity supplies data and executes media actions. */
class HomeLyricsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    data class Snapshot(
        val track: String = "",
        val artist: String = "",
        val cover: Bitmap? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val playing: Boolean = false,
        val speed: Float = 1f,
        val canPrevious: Boolean = false,
        val canNext: Boolean = false
    )

    interface Actions {
        fun toggleOverlay()
        fun openSettings()
        fun seekTo(positionMs: Long)
        fun togglePlay()
        fun skipPrev()
        fun skipNext()
        fun editCustomLyrics()
        fun manageCustomLyrics()
    }

    var actions: Actions? = null
    private val clock = LyricClock()
    private var snapshot = Snapshot()
    private var document = LyricDocument(emptyList(), true)
    private var translationMode = "bilingual"
    private var lyricOffsetMs = 0L
    private var custom = false
    private var lyricStatus = "loading"
    private var active = false
    private var activeIndex = -2
    private var dragging = false
    private var overlayRunning = false
    private var lastProgressPaint = 0L
    private var manualScrollUntil = 0L
    private var scrollAnimation: ValueAnimator? = null
    private var menu: BottomSheetDialog? = null
    private val lyricRows = mutableListOf<LyricLineView>()

    private val stage = FrameLayout(context)
    private val scroll = ScrollView(context).apply {
        id = R.id.home_lyrics_scroll
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
    }
    private val lyricsTrack = LinearLayout(context).apply {
        id = R.id.home_lyrics_track
        orientation = VERTICAL
    }
    private val empty = label("未在播放", 19f).apply {
        id = R.id.home_empty
        gravity = Gravity.CENTER
        setPadding(dp(32), 0, dp(32), 0)
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    private val interlude = label("···", 32f).apply {
        visibility = GONE
        contentDescription = "间奏"
        letterSpacing = .18f
    }
    private val topFade = View(context)
    private val bottomFade = View(context)
    private val divider = View(context)
    private val cover = ShapeableImageView(context).apply {
        id = R.id.home_cover
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(11).toFloat()).build()
        strokeWidth = dp(1).toFloat()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val song = label("未在播放", 16f).apply {
        id = R.id.home_song
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val artist = label("", 13f).apply {
        id = R.id.home_artist
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val overlay = iconButton(R.drawable.ic_home_lyrics, "显示桌面歌词").apply {
        id = R.id.home_overlay
        setOnClickListener { actions?.toggleOverlay() }
    }
    private val more = iconButton(R.drawable.ic_home_more, "更多").apply {
        id = R.id.home_more
        setOnClickListener { showMore() }
    }
    private val progress = Slider(context).apply {
        id = R.id.home_progress
        valueFrom = 0f
        valueTo = 1f
        stepSize = 0f
        labelBehavior = LabelFormatter.LABEL_GONE
        trackHeight = dp(3)
        thumbRadius = dp(6)
        haloRadius = dp(16)
        contentDescription = "播放进度"
    }
    private val timeCurrent = label("0:00", 12f).apply { id = R.id.home_time_current }
    private val timeDuration = label("0:00", 12f).apply { id = R.id.home_time_duration }
    private val previous = iconButton(R.drawable.ic_home_previous, "上一首").apply {
        id = R.id.home_previous
        iconSize = dp(30)
        setOnClickListener { actions?.skipPrev() }
    }
    private val play = iconButton(R.drawable.ic_home_play, "播放").apply {
        id = R.id.home_play
        iconSize = dp(26)
        cornerRadius = dp(28)
        strokeWidth = dp(1)
        setOnClickListener { actions?.togglePlay() }
    }
    private val next = iconButton(R.drawable.ic_home_next, "下一首").apply {
        id = R.id.home_next
        iconSize = dp(30)
        setOnClickListener { actions?.skipNext() }
    }
    private val frame = object : Runnable {
        override fun run() {
            if (!active || !isAttachedToWindow) return
            renderFrame()
            postOnAnimation(this)
        }
    }

    init {
        orientation = VERTICAL
        stage.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        scroll.addView(lyricsTrack, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stage.addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.addView(interlude, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(26) })
        stage.addView(topFade, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP))
        stage.addView(bottomFade, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46), Gravity.BOTTOM))
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(divider, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))

        val bar = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(18))
        }
        val metadata = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        metadata.addView(cover, LayoutParams(dp(50), dp(50)))
        val text = LinearLayout(context).apply { orientation = VERTICAL }
        text.addView(song, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        text.addView(artist, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        metadata.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(13) })
        metadata.addView(overlay, LayoutParams(dp(48), dp(48)))
        metadata.addView(more, LayoutParams(dp(48), dp(48)).apply { rightMargin = -dp(8) })
        bar.addView(metadata, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bar.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        val times = LinearLayout(context)
        times.addView(timeCurrent, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        times.addView(timeDuration, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        bar.addView(times)
        val transport = LinearLayout(context).apply { gravity = Gravity.CENTER }
        transport.addView(previous, LayoutParams(dp(48), dp(48)))
        transport.addView(play, LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(24); rightMargin = dp(24) })
        transport.addView(next, LayoutParams(dp(48), dp(48)))
        bar.addView(transport, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        stage.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                lyricsTrack.setPadding(dp(26), ((bottom - top) * .42f).toInt(), dp(26), ((bottom - top) * .58f).toInt())
                activeIndex = -2
            }
        }
        scroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                scrollAnimation?.cancel()
                manualScrollUntil = SystemClock.uptimeMillis() + 4000L
            }
            false
        }
        progress.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                timeCurrent.text = formatTime(value.toLong())
                if (!dragging) seek(value.toLong())
            }
        }
        progress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) { dragging = true }
            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                seek(slider.value.toLong())
            }
        })
        progress.setOnKeyListener { _, key, event ->
            if (snapshot.durationMs > 0L && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if (event.action == KeyEvent.ACTION_DOWN) seek(clock.positionMs() + if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 5000L else -5000L)
                true
            } else false
        }
        applyTheme()
        renderTransport()
    }

    fun setSnapshot(value: Snapshot, forcePosition: Boolean = false) {
        snapshot = value
        clock.durationMs = value.durationMs
        clock.sync(value.positionMs, value.playing, value.speed, forcePosition)
        song.text = value.track.ifBlank { "未在播放" }
        if (value.cover != null) {
            cover.imageTintList = null
            cover.setPadding(0, 0, 0, 0)
            cover.setImageBitmap(value.cover)
        } else {
            cover.setImageResource(R.drawable.ic_home_music)
            cover.imageTintList = ColorStateList.valueOf(color(R.color.text_tertiary))
            cover.setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        if (value.track.isBlank() || forcePosition) dragging = false
        if (value.track.isBlank()) {
            clock.reset()
            document = LyricDocument(emptyList(), true)
            custom = false
            lyricStatus = "loading"
            renderLines()
        } else if (forcePosition) {
            showLoading()
        }
        renderArtist()
        renderTransport()
        renderEmpty()
        paintProgress()
        renderFrame()
    }

    fun setProgress(positionMs: Long, playing: Boolean, speed: Float = 1f) {
        val wasPlaying = clock.playing
        clock.sync(positionMs, playing, speed)
        if (playing != wasPlaying) renderTransport()
        paintProgress()
    }

    fun setLyrics(value: LyricDocument, isCustom: Boolean = false) {
        document = value
        custom = isCustom
        lyricStatus = if (value.lines.isEmpty()) "empty" else "ready"
        renderArtist()
        renderLines()
        renderEmpty()
    }

    fun showLoading() {
        document = LyricDocument(emptyList(), true)
        custom = false
        lyricStatus = "loading"
        renderArtist()
        renderLines()
        renderEmpty()
    }

    fun setTranslationMode(mode: String) {
        val normalized = if (mode == "original" || mode == "translated") mode else "bilingual"
        if (normalized == translationMode) return
        translationMode = normalized
        renderLines()
    }

    fun setLyricOffset(offsetMs: Int) {
        lyricOffsetMs = offsetMs.toLong()
        activeIndex = -2
        renderFrame()
    }

    fun setOverlayState(running: Boolean) {
        overlayRunning = running
        overlay.isSelected = running
        overlay.backgroundTintList = ColorStateList.valueOf(
            if (running) ColorUtils.setAlphaComponent(color(R.color.text_primary), 31) else Color.TRANSPARENT
        )
        overlay.contentDescription = if (running) "关闭桌面歌词" else "显示桌面歌词"
        ViewCompat.setStateDescription(overlay, if (running) "已开启" else "已关闭")
    }

    fun setActive(on: Boolean) {
        active = on
        removeCallbacks(frame)
        if (on && isAttachedToWindow) postOnAnimation(frame)
        else {
            scrollAnimation?.cancel()
            lyricRows.forEach { it.animate().cancel() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) { removeCallbacks(frame); postOnAnimation(frame) }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame)
        scrollAnimation?.cancel()
        lyricRows.forEach { it.animate().cancel() }
        menu?.dismiss()
        menu = null
        super.onDetachedFromWindow()
    }

    fun applyTheme() {
        val primary = color(R.color.text_primary)
        val secondary = color(R.color.text_secondary)
        setBackgroundColor(color(R.color.app_bg))
        song.setTextColor(primary)
        artist.setTextColor(secondary)
        empty.setTextColor(secondary)
        interlude.setTextColor(primary)
        timeCurrent.setTextColor(secondary)
        timeDuration.setTextColor(secondary)
        divider.setBackgroundColor(color(R.color.app_line_soft))
        cover.setBackgroundColor(color(R.color.app_line_soft))
        cover.strokeColor = ColorStateList.valueOf(color(R.color.app_line))
        if (snapshot.cover == null) cover.imageTintList = ColorStateList.valueOf(color(R.color.text_tertiary))
        val iconColors = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(primary, 72), primary))
        listOf(overlay, more, previous, play, next).forEach {
            it.iconTint = iconColors
            it.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 31))
        }
        play.strokeColor = ColorStateList.valueOf(color(R.color.app_line))
        progress.thumbTintList = ColorStateList.valueOf(primary)
        progress.trackActiveTintList = ColorStateList.valueOf(primary)
        progress.trackInactiveTintList = ColorStateList.valueOf(color(R.color.control_track))
        progress.haloTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 31))
        topFade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color(R.color.app_bg), ColorUtils.setAlphaComponent(color(R.color.app_bg), 0)))
        bottomFade.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(ColorUtils.setAlphaComponent(color(R.color.app_bg), 0), color(R.color.app_bg)))
        setOverlayState(overlayRunning)
        lyricRows.forEach { it.setTextColor(primary) }
    }

    private fun renderArtist() {
        artist.text = if (custom) snapshot.artist.takeIf { it.isNotBlank() }?.plus(" · 自定义歌词") ?: "自定义歌词" else snapshot.artist
    }

    private fun renderTransport() {
        val hasTrack = snapshot.track.isNotBlank()
        previous.isEnabled = hasTrack && snapshot.canPrevious
        next.isEnabled = hasTrack && snapshot.canNext
        play.isEnabled = hasTrack
        play.setIconResource(if (clock.playing) R.drawable.ic_home_pause else R.drawable.ic_home_play)
        play.contentDescription = if (clock.playing) "暂停" else "播放"
        progress.isEnabled = hasTrack && snapshot.durationMs > 0L
    }

    private fun renderEmpty() {
        empty.visibility = if (document.lines.isEmpty()) VISIBLE else GONE
        empty.text = when {
            snapshot.track.isBlank() -> "未在播放"
            lyricStatus == "empty" -> "找不到歌词"
            else -> "正在获取歌词…"
        }
    }

    private fun renderLines() {
        scrollAnimation?.cancel()
        lyricsTrack.removeAllViews()
        lyricRows.clear()
        activeIndex = -2
        manualScrollUntil = 0L
        interlude.visibility = GONE
        val position = clock.positionMs() + lyricOffsetMs
        document.lines.forEach { line ->
            val row = LyricLineView(context).apply {
                textSize = 27f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
                setPadding(0, dp(11), 0, dp(11))
                setLineSpacing(0f, 1.18f)
                bind(line, translationMode)
                setPlaybackPosition(position)
                if (document.timed) {
                    isFocusable = true
                    setOnClickListener { seek((line.startMs - lyricOffsetMs).coerceAtLeast(0L)) }
                }
            }
            lyricRows += row
            lyricsTrack.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        post { renderFrame() }
    }

    private fun renderFrame() {
        if (document.lines.isEmpty()) return
        val position = clock.positionMs() + lyricOffsetMs
        val index = if (document.timed) LyricTiming.activeIndex(document.lines, position) else {
            if (clock.durationMs <= 0L) 0 else ((clock.positionMs().toDouble() / clock.durationMs) * document.lines.size).toInt().coerceIn(0, document.lines.lastIndex)
        }
        val now = SystemClock.uptimeMillis()
        val resumeFollowing = manualScrollUntil != 0L && now >= manualScrollUntil
        if (resumeFollowing) manualScrollUntil = 0L
        if (activeIndex != index || resumeFollowing) {
            lyricRows.getOrNull(activeIndex)?.setPlaybackPosition(position)
            activeIndex = index
            lyricRows.forEachIndexed { i, row ->
                val distance = if (index < 0) i + 1 else abs(i - index)
                val scale = when (distance) { 0 -> 1f; 1 -> .92f; 2 -> .87f; else -> .82f }
                val opacity = when (distance) { 0 -> 1f; 1 -> .7f; 2 -> .55f; 3 -> .4f; else -> .26f }
                row.pivotX = 0f
                row.pivotY = row.height / 2f
                if (active) row.animate().scaleX(scale).scaleY(scale).alpha(opacity).setDuration(380L).start()
                else { row.scaleX = scale; row.scaleY = scale; row.alpha = opacity }
            }
            if (manualScrollUntil == 0L) centerLine(index.coerceAtLeast(0))
        }
        lyricRows.getOrNull(index)?.setPlaybackPosition(position)
        renderInterlude(position, index)
        if (now - lastProgressPaint >= 250L) {
            lastProgressPaint = now
            paintProgress()
        }
    }

    private fun centerLine(index: Int) {
        val row = lyricRows.getOrNull(index) ?: return
        if (row.height == 0 || stage.height == 0) {
            activeIndex = -2
            return
        }
        val target = (row.top + row.height / 2 - (stage.height * .42f).toInt()).coerceAtLeast(0)
        scrollAnimation?.cancel()
        if (!active || !ViewCompat.isLaidOut(this)) scroll.scrollTo(0, target)
        else scrollAnimation = ValueAnimator.ofInt(scroll.scrollY, target).apply {
            duration = 600L
            interpolator = DecelerateInterpolator()
            addUpdateListener { scroll.scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    private fun renderInterlude(positionMs: Long, index: Int) {
        if (!document.timed) { interlude.visibility = GONE; return }
        val line = document.lines.getOrNull(index)
        val gapStart = line?.let(LyricTiming::lineEnd) ?: 0L
        val gapEnd = document.lines.getOrNull(index + 1)?.startMs ?: clock.durationMs
        val visible = gapEnd - gapStart > (if (index < 0) 4000L else 4500L) &&
            positionMs > gapStart + (if (index < 0) 0L else 300L) && positionMs < gapEnd - 250L
        interlude.visibility = if (visible) VISIBLE else GONE
        if (visible) {
            val anchor = lyricRows.getOrNull(index.coerceAtLeast(0)) ?: return
            interlude.translationY = (anchor.bottom - scroll.scrollY + dp(2)).toFloat()
            val ratio = ((positionMs - gapStart).toFloat() / (gapEnd - gapStart)).coerceIn(0f, 1f)
            interlude.alpha = .35f + ratio * .65f
        }
    }

    private fun paintProgress() {
        if (dragging) return
        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val position = clock.positionMs().coerceAtMost(duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val upper = duration.coerceAtLeast(1L).toFloat()
        if (progress.value > upper) progress.value = 0f
        progress.valueTo = upper
        progress.value = position.toFloat().coerceIn(0f, upper)
        timeCurrent.text = formatTime(position)
        timeDuration.text = formatTime(duration)
        progress.contentDescription = "播放进度 ${formatTime(position)} / ${formatTime(duration)}"
    }

    private fun seek(positionMs: Long) {
        if (snapshot.track.isBlank()) return
        val target = if (snapshot.durationMs > 0L) positionMs.coerceIn(0L, snapshot.durationMs) else positionMs.coerceAtLeast(0L)
        actions?.seekTo(target)
        clock.sync(target, clock.playing, clock.speed, force = true)
        activeIndex = -2
        manualScrollUntil = 0L
        paintProgress()
        renderFrame()
    }

    private fun showMore() {
        menu?.dismiss()
        val dialog = BottomSheetDialog(context)
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        fun action(title: String, callback: () -> Unit) {
            content.addView(MaterialButton(context).apply {
                text = title
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(color(R.color.text_primary))
                strokeWidth = 0
                minHeight = dp(52)
                cornerRadius = dp(12)
                setOnClickListener { dialog.dismiss(); callback() }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        action(if (custom) "编辑这首歌的自定义歌词" else if (snapshot.track.isBlank()) "添加自定义 LRC 歌词" else "为这首歌指定 LRC 歌词") { actions?.editCustomLyrics() }
        action("管理自定义歌词") { actions?.manageCustomLyrics() }
        content.addView(View(context).apply { setBackgroundColor(color(R.color.app_line_soft)) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        action("设置") { actions?.openSettings() }
        action("取消") { }
        dialog.setContentView(content)
        dialog.setOnDismissListener { if (menu === dialog) menu = null }
        menu = dialog
        dialog.show()
    }

    private fun iconButton(iconRes: Int, description: String) = MaterialButton(context).apply {
        text = ""
        setIconResource(iconRes)
        iconSize = dp(22)
        iconPadding = 0
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        gravity = Gravity.CENTER
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        strokeWidth = 0
        cornerRadius = dp(12)
        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        stateListAnimator = null
        elevation = 0f
        contentDescription = description
    }

    private fun label(value: String, size: Float) = MaterialTextView(context).apply {
        text = value
        textSize = size
        includeFontPadding = false
    }
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun formatTime(value: Long): String {
        val seconds = value.coerceAtLeast(0L) / 1000L
        return "${seconds / 60L}:${(seconds % 60L).toString().padStart(2, '0')}"
    }
}
