package com.tcrrry.desktoplyrics

import androidx.appcompat.app.AppCompatDelegate

/** 主题偏好：跟随系统 / 亮 / 暗。 */
object ThemePrefs {
    const val PREFS = "app_theme_v1"
    const val KEY = "theme_mode"
    const val FOLLOW = "follow"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun apply(mode: String?) {
        val nightMode = when (mode) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
