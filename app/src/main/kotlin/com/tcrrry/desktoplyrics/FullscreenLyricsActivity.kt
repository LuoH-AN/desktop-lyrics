package com.tcrrry.desktoplyrics

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class FullscreenLyricsActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var contentLayer: FrameLayout
    private lateinit var host: FrameLayout
    private var attached = false
    private var contentRotated = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        contentRotated = state?.getBoolean(STATE_ROTATED) ?: false
        configureFullscreenWindow()

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(17, 18, 23))
            clipChildren = false
            clipToPadding = false
        }
        contentLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        host = FrameLayout(this)
        contentLayer.addView(host, FrameLayout.LayoutParams(-1, -1))
        root.addView(contentLayer, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
            addView(iconButton(R.drawable.ic_screen_rotation, "旋转全屏歌词") {
                contentRotated = !contentRotated
                applyContentRotation(true)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(View(this@FullscreenLyricsActivity), LinearLayout.LayoutParams(dp(6), dp(32)))
            addView(iconButton(R.drawable.ic_overlay_resize_down_left, "返回悬浮窗") {
                finish()
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
        }
        root.addView(controls, FrameLayout.LayoutParams(-2, dp(32), Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(12), dp(17), 0)
        })
        setContentView(root)

        attached = LyricsOverlayService.instance?.attachFullscreen(host) == true
        if (!attached) {
            finish()
            return
        }
        root.post { applyContentRotation(false) }
        immerse()
    }

    private fun configureFullscreenWindow() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun iconButton(drawableRes: Int, description: String, action: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        contentDescription = description
        val icon = AppCompatResources.getDrawable(this@FullscreenLyricsActivity, drawableRes)?.mutate()?.apply {
            setTint(Color.argb(225, 255, 255, 255))
        }
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
        setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.argb(190, 0, 0, 0))
        setOnClickListener { action() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.88f).scaleY(.88f).alpha(.72f).setDuration(70).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
            }
            false
        }
    }

    private fun applyContentRotation(animated: Boolean) {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) return
        val targetWidth = if (contentRotated) height else width
        val targetHeight = if (contentRotated) width else height
        contentLayer.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight, Gravity.CENTER)
        contentLayer.pivotX = targetWidth / 2f
        contentLayer.pivotY = targetHeight / 2f
        val targetRotation = if (contentRotated) 90f else 0f
        if (animated) {
            contentLayer.animate().rotation(targetRotation).setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction { LyricsOverlayService.instance?.updateFullscreenLayout() }.start()
        } else {
            contentLayer.animate().cancel()
            contentLayer.rotation = targetRotation
            contentLayer.post { LyricsOverlayService.instance?.updateFullscreenLayout() }
        }
    }

    private fun immerse() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_ROTATED, contentRotated)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immerse()
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        root.post { applyContentRotation(false) }
        immerse()
    }

    override fun onStop() {
        super.onStop()
        if (attached) {
            LyricsOverlayService.instance?.detachFullscreen(host)
            attached = false
        }
        finish()
    }

    override fun onDestroy() {
        if (attached) LyricsOverlayService.instance?.detachFullscreen(host)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_ROTATED = "fullscreen_rotated"
    }
}
