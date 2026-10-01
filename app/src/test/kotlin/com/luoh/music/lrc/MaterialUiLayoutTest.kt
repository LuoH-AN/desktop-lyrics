package com.luoh.music.lrc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaterialUiLayoutTest {
    private fun context(): Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(), R.style.Theme_DesktopLyrics
    )

    private fun layout(view: View, widthDp: Int = 320, heightDp: Int = 640, preview: String? = null) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        if (preview != null) {
            val output = File("build/reports/material-ui/$preview.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
    }

    private fun assertNativeSettings(preview: String) {
        val root = LayoutInflater.from(context()).inflate(R.layout.activity_settings, null)
        layout(root, preview = preview)
        val toggle = root.findViewById<View>(R.id.overlay_toggle)
        assertTrue("Overlay switch must be Material", toggle is MaterialSwitch)
        val thumbBounds = requireNotNull((toggle as MaterialSwitch).thumbDrawable).bounds
        assertTrue("Material switch thumb must be drawn", thumbBounds.width() > 0)
        assertEquals("Switch thumb must remain circular", thumbBounds.width(), thumbBounds.height())
        assertTrue("Font slider must be Material", root.findViewById<View>(R.id.seek_font_size) is Slider)
        for (id in listOf(R.id.lyric_color_white, R.id.lyric_color_blue, R.id.lyric_color_black,
            R.id.lyric_color_pink, R.id.lyric_color_custom)) {
            val choice = root.findViewById<MaterialButton>(id)
            assertNotNull(choice)
            assertTrue("Color target must be measured", choice.width > 0)
            assertEquals("Color/add selector must stay square on narrow screens", choice.width, choice.height)
        }
        fun hasWebView(view: View): Boolean = view is WebView ||
            (view is ViewGroup && (0 until view.childCount).any { hasWebView(view.getChildAt(it)) })
        assertFalse("Settings must remain native", hasWebView(root))
    }

    @Test fun settingsInflateAndKeepCirclesOnSmallScreen() = assertNativeSettings("settings-light")

    @Test fun settingsInflateInDarkTheme() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-night")
        assertNativeSettings("settings-dark")
    }

    private class Actions : HomeLyricsView.Actions {
        var overlays = 0
        var seekPosition = -1L
        override fun toggleOverlay() { overlays++ }
        override fun openSettings() {}
        override fun seekTo(positionMs: Long) { seekPosition = positionMs }
        override fun togglePlay() {}
        override fun skipPrev() {}
        override fun skipNext() {}
        override fun editCustomLyrics() {}
        override fun manageCustomLyrics() {}
    }

    private fun assertHome(preview: String) {
        val home = HomeLyricsView(context())
        val actions = Actions()
        home.actions = actions
        val snapshot = HomeLyricsView.Snapshot(
            track = "这是一首名字很长的歌曲 · Live at Somewhere",
            artist = "歌手 / Artist", positionMs = 10000L, durationMs = 100000L,
            canPrevious = true, canNext = true
        )
        home.setSnapshot(snapshot, forcePosition = true)
        home.setLyrics(LyricDocument(listOf(
            LyricLine(0, "晚风轻轻吹过街角"),
            LyricLine(10000, "让歌词陪你走过每一天"),
            LyricLine(20000, "下一句，也在这里等你")
        ), true))
        home.setOverlayState(false)
        layout(home)
        val overlay = home.findViewById<MaterialButton>(R.id.home_overlay)
        val more = home.findViewById<MaterialButton>(R.id.home_more)
        assertEquals(more.width, overlay.width)
        assertEquals(more.height, overlay.height)
        assertEquals(more.top, overlay.top)
        assertEquals(more.iconSize, overlay.iconSize)
        assertEquals(0, overlay.strokeWidth)
        assertTrue("Actions must stay within the narrow screen", more.right <= (more.parent as View).width + 8 * home.resources.displayMetrics.density)
        overlay.performClick()
        assertEquals(1, actions.overlays)
        assertFalse("Home waits for the real service state", overlay.isSelected)
        home.setOverlayState(true)
        assertTrue(overlay.isSelected)
        assertTrue(android.graphics.Color.alpha(overlay.backgroundTintList!!.defaultColor) in 1..100)
        layout(home, preview = preview)
        home.setLyrics(LyricDocument(emptyList(), true))
        home.setSnapshot(snapshot.copy(playing = false))
        assertEquals("找不到歌词", home.findViewById<android.widget.TextView>(R.id.home_empty).text.toString())
        home.setSnapshot(HomeLyricsView.Snapshot())
        assertFalse(home.findViewById<MaterialButton>(R.id.home_play).isEnabled)
        assertTrue(overlay.isEnabled)
        assertTrue(more.isEnabled)
        overlay.performClick()
        assertEquals(2, actions.overlays)
        home.setActive(false)
    }

    @Test fun nativeHomeControlsAndEmptyStates() = assertHome("home-light")

    @Test fun nativeHomeControlsInDarkTheme() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-night")
        assertHome("home-dark")
    }

    @Test fun iconButtonsHaveEqualTargetsAndConfirmedTranslucentState() {
        val context = context()
        var requests = 0
        val lyrics = NativeUi.iconButton(context, android.R.drawable.ic_menu_edit, "显示桌面歌词") { requests++ }
        val more = NativeUi.iconButton(context, android.R.drawable.ic_menu_more, "更多") {}
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(lyrics)
            addView(more)
        }
        layout(row, heightDp = 64)
        assertEquals(lyrics.width, more.width)
        assertEquals(lyrics.height, more.height)
        assertEquals(lyrics.top, more.top)
        assertEquals(lyrics.iconSize, more.iconSize)
        assertEquals(0, lyrics.strokeWidth)
        assertEquals(0, more.strokeWidth)
        assertFalse(lyrics.isChecked)
        lyrics.performClick()
        assertEquals(1, requests)
        assertFalse("Wait for service state instead of optimistic selection", lyrics.isChecked)
        lyrics.isChecked = true
        val checkedColor = lyrics.backgroundTintList!!.getColorForState(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked), 0
        )
        val alpha = android.graphics.Color.alpha(checkedColor)
        assertTrue("Active fill must be visible but translucent", alpha in 1..100)
        lyrics.isChecked = false
        val normalColor = lyrics.backgroundTintList!!.getColorForState(
            intArrayOf(android.R.attr.state_enabled), 0
        )
        assertEquals("Inactive icon must have no background", 0, android.graphics.Color.alpha(normalColor))
    }
}
