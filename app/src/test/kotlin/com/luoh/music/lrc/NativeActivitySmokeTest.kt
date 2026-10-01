package com.luoh.music.lrc

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Catch Material theme/inflation errors before shipping a page that crashes on entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeActivitySmokeTest {
    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
        }
    }

    private fun <T : Activity> opensNatively(type: Class<T>, needsForm: Boolean = false) {
        val controller = Robolectric.buildActivity(type).create()
        try {
            val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
            assertTrue("${type.simpleName} must create its content", content.childCount > 0)
            assertFalse("${type.simpleName} must not display a WebView", views(content).any { it is WebView })
            if (needsForm) assertTrue("${type.simpleName} must use Material text fields", views(content).any { it is TextInputLayout })
        } finally {
            controller.destroy()
        }
    }

    @Test fun homeOpensNatively() = opensNatively(MainActivity::class.java)
    @Test fun settingsOpenNatively() = opensNatively(SettingsActivity::class.java)
    @Test fun lyricManagerOpensNatively() = opensNatively(CustomLyricsManagerActivity::class.java)
    @Test fun lyricEditorOpensWithMaterialFields() = opensNatively(CustomLyricsEditActivity::class.java, needsForm = true)
    @Test fun sourceManagerOpensNatively() = opensNatively(LyricSourceManagerActivity::class.java)
    @Test fun synchronizationManagerOpensNatively() = opensNatively(LyricOffsetMemoryActivity::class.java)
    @Test fun translationSettingsOpenNatively() {
        // Android initializes this provider at process startup; Robolectric does not run merged providers.
        com.google.mlkit.common.sdkinternal.MlKitContext.initialize(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
        opensNatively(TranslationSettingsActivity::class.java)
    }
    @Test fun apiManagerOpensNatively() = opensNatively(ApiProfileManagerActivity::class.java)
}
