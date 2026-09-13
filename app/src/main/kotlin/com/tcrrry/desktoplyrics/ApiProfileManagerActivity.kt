package com.tcrrry.desktoplyrics

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ApiProfileManagerActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("supplement_translation", Context.MODE_PRIVATE) }
    private lateinit var name: EditText
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var save: Button
    private var editingId: String? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 14f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, dp(9), 0, dp(7))
    }
    private fun field(hintText: String) = EditText(this).apply {
        hint = hintText; textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(119,127,147))
        isSingleLine = true; minHeight = dp(50); setBackgroundResource(R.drawable.bg_ui_input)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(Color.WHITE); stateListAnimator = null
        setBackgroundResource(R.drawable.bg_ui_pill); setOnClickListener { action() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.975f).scaleY(.975f).alpha(.86f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()
            }
            false
        }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(13), dp(18), dp(18))
        setBackgroundResource(R.drawable.bg_ui_card)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    }
    private fun showStatus(message: String, success: Boolean = false) {
        status.text = message
        status.setTextColor(if (success) Color.rgb(255,138,155) else Color.rgb(255,115,136))
        status.alpha = 0f; status.animate().alpha(1f).setDuration(180).start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(30))
            setBackgroundResource(R.drawable.bg_main_screen)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true; overScrollMode = ScrollView.OVER_SCROLL_NEVER
            setBackgroundResource(R.drawable.bg_main_screen); addView(content)
        })
        content.addView(TextView(this).apply {
            text = "‹  API 配置"; textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), 0, dp(10)); setOnClickListener { finish() }
        })
        content.addView(label("可添加多套兼容 Chat Completions 的服务，并随时切换。", 12f).apply {
            setTextColor(Color.rgb(144,151,169)); setPadding(dp(2), 0, 0, dp(3))
        })

        val editor = card()
        editor.addView(label("添加 API", 19f).apply { setTextColor(Color.WHITE) })
        name = field("配置名称，例如 OpenAI、硅基流动")
        endpoint = field("HTTPS 服务地址，例如 https://example.com/v1")
        model = field("模型名称")
        key = field("API Key").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        editor.addView(name); editor.addView(endpoint); editor.addView(model); editor.addView(key)
        save = button("保存并使用") { saveProfile() }.apply {
            setBackgroundResource(R.drawable.bg_ui_primary_button)
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) }
        }
        editor.addView(save)
        status = label("填写完整后保存；密钥会加密保存在本机。", 12f).apply {
            setTextColor(Color.rgb(150,158,177)); setPadding(dp(5), dp(10), dp(5), 0)
        }
        editor.addView(status)
        content.addView(editor)

        val saved = card()
        saved.addView(label("API 配置", 19f).apply { setTextColor(Color.WHITE) })
        saved.addView(label("内置与自行添加的配置都可以编辑或删除。", 12f).apply {
            setTextColor(Color.rgb(144,151,169)); setPadding(0, 0, 0, dp(4))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saved.addView(list)
        content.addView(saved)
        renderProfiles()
    }

    private fun saveProfile() {
        val label = name.text.toString().trim()
        val address = endpoint.text.toString().trim()
        val modelName = model.text.toString().trim()
        val secret = key.text.toString().trim()
        if (label.isBlank() || !address.startsWith("https://") || modelName.isBlank() ||
            (secret.isBlank() && editingId?.let { SecretStorage(this, it).read().isBlank() } != false)) {
            showStatus("请填写名称、HTTPS 地址、模型和 API Key")
            return
        }
        val profile = editingId?.let { id ->
            TranslationApiProfiles.rename(this, id, label)
            TranslationApiProfiles.find(this, id)
        } ?: TranslationApiProfiles.add(this, label)
        prefs.edit()
            .putString(TranslationApiProfiles.endpointKey(profile.id), address)
            .putString(TranslationApiProfiles.modelKey(profile.id), modelName)
            .putString("active_api_profile", profile.id)
            .putString("mode", "api").apply()
        if (secret.isNotBlank()) SecretStorage(this, profile.id).save(secret)
        editingId = profile.id
        save.text = "保存修改并使用"
        showStatus("${profile.label} 已保存并切换使用", true)
        renderProfiles()
        LyricsOverlayService.instance?.refreshSupplementTranslation()
    }

    private fun edit(profile: TranslationApiProfile) {
        editingId = profile.id
        name.setText(profile.label)
        endpoint.setText(prefs.getString(TranslationApiProfiles.endpointKey(profile.id), ""))
        model.setText(prefs.getString(TranslationApiProfiles.modelKey(profile.id), ""))
        key.setText(SecretStorage(this, profile.id).read())
        key.setSelection(key.text.length)
        save.text = "保存修改并使用"
        showStatus("正在编辑 ${profile.label}")
    }

    private fun renderProfiles() {
        list.removeAllViews()
        val profiles = TranslationApiProfiles.all(this)
        if (profiles.isEmpty()) {
            list.addView(label("还没有 API 配置，请在上方添加", 13f).apply { setTextColor(Color.rgb(133,141,160)) })
            return
        }
        profiles.forEach { profile ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(7) }
            }
            row.addView(button(profile.label + "   ›") { edit(profile) }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6) })
            row.addView(button("删除") { confirmDelete(profile) }, LinearLayout.LayoutParams(dp(82), dp(52)))
            list.addView(row)
        }
    }

    private fun confirmDelete(profile: TranslationApiProfile) {
        val dialog = Dialog(this)
        val panel = card().apply {
            addView(label("删除 ${profile.label}？", 20f).apply { setTextColor(Color.WHITE) })
            addView(label("地址、模型和本机加密密钥都会删除，此操作无法恢复。", 12f).apply { setTextColor(Color.rgb(158,165,182)) })
            addView(button("确认删除") {
                dialog.dismiss(); TranslationApiProfiles.remove(this@ApiProfileManagerActivity, profile.id)
                if (editingId == profile.id) clearEditor()
                if (prefs.getString("active_api_profile", "") == profile.id) {
                    prefs.edit().putString("active_api_profile", TranslationApiProfiles.all(this@ApiProfileManagerActivity).firstOrNull()?.id ?: "none").apply()
                }
                showStatus("已删除 ${profile.label}", true); renderProfiles()
            }.apply { setBackgroundResource(R.drawable.bg_ui_primary_button); layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) } })
            addView(button("取消") { dialog.dismiss() }.apply { alpha = .72f; layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7) } })
        }
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply { width = resources.displayMetrics.widthPixels - dp(36) }
    }

    private fun clearEditor() {
        editingId = null; name.setText(""); endpoint.setText(""); model.setText(""); key.setText("")
        save.text = "保存并使用"
    }
}
