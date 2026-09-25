package com.tcrrry.desktoplyrics

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class TranslationSettingsActivity : AppCompatActivity() {
    private fun col(res: Int) = androidx.core.content.ContextCompat.getColor(this, res)
    private val prefs by lazy { getSharedPreferences("supplement_translation", Context.MODE_PRIVATE) }
    private val manager by lazy { RemoteModelManager.getInstance() }
    private lateinit var languages: LinearLayout
    private lateinit var search: EditText
    private var selectedMode = 0
    private lateinit var modeSegments: List<TextView>
    private lateinit var apiBox: LinearLayout
    private lateinit var offlineBox: LinearLayout
    private lateinit var endpoint: EditText
    private lateinit var model: EditText
    private lateinit var key: EditText
    private lateinit var apiProfileButton: LinearLayout
    private lateinit var apiProfileLabel: TextView
    private var currentApiProfileId = "glm"
    private lateinit var status: TextView
    private lateinit var apiVerifyButton: Button
    private lateinit var downloadProgress: ProgressBar
    private val apiExecutor = Executors.newSingleThreadExecutor()
    private val downloadTimeouts = mutableMapOf<String, Runnable>()
    private var downloaded = emptySet<String>()
    private val downloading = mutableSetOf<String>()
    private val downloadStartedAt = mutableMapOf<String, Long>()
    private val downloadTicker = Handler(Looper.getMainLooper())
    private val downloadTick = object : Runnable {
        override fun run() {
            if (downloading.isNotEmpty()) {
                val seconds = downloading.mapNotNull { downloadStartedAt[it] }
                    .minOfOrNull { (System.currentTimeMillis() - it) / 1000 } ?: 0
                status.text = "语言包下载中… 已用时 ${seconds}s（系统未提供百分比/速度）"
                downloadTicker.postDelayed(this, 1000)
            }
        }
    }
    private val codes = listOf("en", "ja", "ko", "zh") + TranslateLanguage.getAllLanguages()
        .filter { it !in setOf("en", "ja", "ko", "zh") }.sortedBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun title(code: String) = Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) + " · $code"
    private fun label(text: String, size: Float = 14f) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(col(R.color.text_secondary)); setPadding(0, dp(10), 0, dp(8))
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setTextColor(col(R.color.text_primary))
        setBackgroundResource(R.drawable.bg_ui_pill)
        stateListAnimator = null
        setOnClickListener { action() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.975f).scaleY(.975f).alpha(.86f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150).start()
            }
            false
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) }
    }
    private fun field(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint; setText(value); textSize = 14f; setTextColor(col(R.color.text_primary))
        setHintTextColor(col(R.color.text_tertiary)); isSingleLine = true
        setBackgroundResource(R.drawable.bg_ui_input)
        minHeight = dp(50)
    }
    private fun spinner(items: List<String>) = Spinner(this).apply {
        setBackgroundResource(R.drawable.bg_ui_pill)
        setPadding(dp(12), 0, dp(12), 0)
        adapter = object : ArrayAdapter<String>(this@TranslationSettingsActivity, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).apply { (this as? TextView)?.setTextColor(col(R.color.text_primary)) }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getDropDownView(position, convertView, parent).apply {
                    setBackgroundColor(col(R.color.app_surface)); (this as? TextView)?.setTextColor(col(R.color.text_primary))
                }
        }
    }
    private fun card(title: String, subtitle: String = "") = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(18))
        setBackgroundResource(R.drawable.bg_ui_card)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        addView(label(title, 19f).apply { setTextColor(col(R.color.text_primary)) })
        if (subtitle.isNotBlank()) addView(label(subtitle, 12f).apply { setTextColor(col(R.color.text_secondary)); setPadding(0, 0, 0, dp(8)) })
    }
    private fun showStatus(message: String, success: Boolean = false) {
        status.text = message
        status.setTextColor(col(R.color.text_secondary))
        status.visibility = View.VISIBLE
        status.alpha = 0f
        status.translationY = dp(5).toFloat()
        status.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }
    private fun updateModeUi(animate: Boolean) {
        modeSegments.forEachIndexed { index, view ->
            view.setBackgroundResource(if (index == selectedMode) R.drawable.bg_ui_segment_selected else android.R.color.transparent)
            view.setTextColor(if (index == selectedMode) col(R.color.text_on_accent) else col(R.color.text_secondary))
        }
        val showApi = selectedMode == 2
        setPanelVisible(apiBox, showApi, animate)
        setPanelVisible(offlineBox, selectedMode == 1, animate)
    }

    private fun setPanelVisible(panel: View, visible: Boolean, animate: Boolean) {
        panel.animate().cancel()
        if (!animate) {
            panel.visibility = if (visible) View.VISIBLE else View.GONE
            panel.alpha = 1f
            panel.translationY = 0f
        } else if (visible) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
            panel.translationY = -dp(8).toFloat()
            panel.animate().alpha(1f).translationY(0f).setDuration(220).start()
        } else if (panel.visibility == View.VISIBLE) {
            panel.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(150)
                .withEndAction { panel.visibility = View.GONE }.start()
        }
    }

    private fun applyMode(index: Int) {
        selectedMode = index.coerceIn(0, 2)
        val selected = listOf("off", "offline", "api")[selectedMode]
        prefs.edit().putString("mode", selected).putString("language", "auto").apply()
        updateModeUi(true)
        LyricsOverlayService.instance?.refreshSupplementTranslation()
        showStatus(when (selected) {
            "off" -> "补充翻译已关闭"
            "offline" -> "离线机翻已应用，请确认所需语言包已下载"
            else -> "自定义 API 已应用；修改配置后请确认并验证连通性"
        }, selected != "api")
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!prefs.contains("active_api_profile")) {
            val legacyEndpoint = prefs.getString("endpoint", "").orEmpty()
            val legacyModel = prefs.getString("model", "").orEmpty()
            val legacyKey = SecretStorage(this, "custom").read()
            currentApiProfileId = if (legacyEndpoint.isNotBlank() || legacyModel.isNotBlank() || legacyKey.isNotBlank()) {
                val legacy = TranslationApiProfiles.add(this, "原自定义服务")
                prefs.edit()
                    .putString(TranslationApiProfiles.endpointKey(legacy.id), legacyEndpoint)
                    .putString(TranslationApiProfiles.modelKey(legacy.id), legacyModel).apply()
                if (legacyKey.isNotBlank()) SecretStorage(this, legacy.id).save(legacyKey)
                legacy.id
            } else "glm"
            prefs.edit().putString("active_api_profile", currentApiProfileId).apply()
        } else {
            currentApiProfileId = TranslationApiProfiles.find(this, prefs.getString("active_api_profile", null)).id
        }
        val content = LinearLayout(this).apply {
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

        content.addView(TextView(this).apply {
            text = "‹  补充翻译"
            textSize = 21f
            setTextColor(col(R.color.text_primary))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), 0, dp(10))
            setOnClickListener { animate().translationX(-dp(4).toFloat()).setDuration(90).withEndAction { finish() }.start() }
        })
        content.addView(label("平台译文优先，只在缺少译文时补充。译文会缓存到本机。", 12f).apply {
            setPadding(dp(2), 0, 0, dp(3)); setTextColor(col(R.color.text_tertiary))
        })

        val settingsCard = card("翻译方式", "点击对应方式立即应用；平台自带译文始终优先。")
        selectedMode = listOf("off", "offline", "api").indexOf(prefs.getString("mode", "off")).coerceAtLeast(0)
        val segmentRail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundResource(R.drawable.bg_ui_pill)
            layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) }
        }
        modeSegments = listOf("关闭", "离线机翻", "自定义 API").mapIndexed { index, text ->
            TextView(this).apply {
                this.text = text
                textSize = 12f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (index > 0) marginStart = dp(3)
                }
                setOnClickListener {
                    if (selectedMode != index) {
                        applyMode(index)
                    }
                }
            }
        }
        modeSegments.forEach(segmentRail::addView)
        settingsCard.addView(segmentRail)

        apiBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_ui_panel)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        apiBox.addView(label("兼容 Chat Completions 的服务", 14f).apply { setTextColor(col(R.color.text_primary)) })
        apiProfileLabel = label("", 14f).apply { setTextColor(col(R.color.text_primary)); setPadding(0, 0, 0, 0) }
        apiProfileButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(14), 0)
            setBackgroundResource(R.drawable.bg_ui_pill)
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
            addView(apiProfileLabel, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("⌄", 19f).apply { setTextColor(col(R.color.text_secondary)); setPadding(dp(8), 0, 0, dp(4)) })
            setOnClickListener { showApiProfileMenu() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).alpha(.88f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
                }
                false
            }
        }
        apiBox.addView(apiProfileButton)
        endpoint = field("HTTPS 服务地址，例如 …/v1")
        model = field("模型名称")
        key = field("API Key").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        apiBox.addView(endpoint); apiBox.addView(model); apiBox.addView(key)
        apiBox.addView(label("可随时切换服务；每套地址、模型和密钥独立保存。缺少译文的歌词才会发送，密钥加密保存在本机。", 11f))
        apiBox.addView(button("删除已保存的密钥") {
            SecretStorage(this, currentApiProfileId).save(""); key.setText(""); key.hint = "API Key"
            showStatus("已删除当前服务保存的 API Key", true)
        })
        apiVerifyButton = button("确认配置并验证连通性") { confirmApi() }.apply {
            setBackgroundResource(R.drawable.bg_ui_primary_button)
        }
        apiBox.addView(apiVerifyButton)
        settingsCard.addView(apiBox)
        loadApiProfile(currentApiProfileId)

        offlineBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
            setBackgroundResource(R.drawable.bg_ui_panel)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        offlineBox.addView(label("离线语言包 · 需要代理", 16f).apply { setTextColor(col(R.color.text_primary)) })
        offlineBox.addView(label("自动识别源语言。单个模型约 30 MB；翻译成中文还需要共用中文模型。系统不提供下载百分比和速度。", 11f).apply {
            setTextColor(col(R.color.text_tertiary))
        })
        downloadProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.control_active))
        }
        offlineBox.addView(downloadProgress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
        search = field("搜索更多语言，例如法语、德语、西班牙语")
        offlineBox.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        val languageActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        }
        languageActions.addView(button("刷新状态") {
            downloading.clear(); downloadProgress.visibility = View.VISIBLE
            showStatus("正在读取已下载语言包…"); refreshModels()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
        languageActions.addView(button("清理译文缓存") {
            File(cacheDir, "translations").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            showStatus("补充翻译缓存已清理", true)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
        offlineBox.addView(languageActions)
        languages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        offlineBox.addView(languages)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderLanguages() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        settingsCard.addView(offlineBox)

        status = label("").apply {
            visibility = View.GONE
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setBackgroundResource(R.drawable.bg_ui_status_badge)
        }
        settingsCard.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(settingsCard)
        updateModeUi(false)
        refreshModels()
    }
    override fun onResume() {
        super.onResume()
        if (!::apiProfileButton.isInitialized) return
        val requested = prefs.getString("active_api_profile", currentApiProfileId)
        val resolved = TranslationApiProfiles.find(this, requested)
        if (resolved.id != currentApiProfileId) {
            currentApiProfileId = resolved.id
            loadApiProfile(currentApiProfileId)
        }
        updateApiProfileButton()
    }
    override fun onDestroy() {
        downloadTicker.removeCallbacks(downloadTick)
        downloadTimeouts.values.forEach(downloadTicker::removeCallbacks)
        apiExecutor.shutdownNow()
        super.onDestroy()
    }
    private fun loadApiProfile(profileId: String) {
        val profile = TranslationApiProfiles.find(this, profileId)
        endpoint.setText(prefs.getString(TranslationApiProfiles.endpointKey(profile.id), profile.defaultEndpoint).orEmpty())
        model.setText(prefs.getString(TranslationApiProfiles.modelKey(profile.id), profile.defaultModel).orEmpty())
        key.setText(SecretStorage(this, profile.id).read())
        key.setSelection(key.text.length)
        updateApiProfileButton()
    }
    private fun updateApiProfileButton() {
        if (::apiProfileButton.isInitialized) {
            apiProfileLabel.text = TranslationApiProfiles.find(this, currentApiProfileId).label
        }
    }
    private fun showApiProfileMenu() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(18))
            setBackgroundResource(R.drawable.bg_ui_card)
            addView(label("选择翻译 API", 20f).apply { setTextColor(col(R.color.text_primary)) })
            addView(label("地址、模型和密钥会按配置分别保存。", 12f).apply {
                setTextColor(col(R.color.text_secondary)); setPadding(0, 0, 0, dp(5))
            })
        }
        TranslationApiProfiles.all(this).forEach { profile ->
            panel.addView(button(if (profile.id == currentApiProfileId) "✓  ${profile.label}" else profile.label) {
                dialog.dismiss(); switchApiProfile(profile)
            })
        }
        panel.addView(button("＋ 添加 API   ›") {
            dialog.dismiss(); saveApiProfileDraft()
            startActivity(Intent(this, ApiProfileManagerActivity::class.java))
        }.apply { setBackgroundResource(R.drawable.bg_flat_button); setTextColor(col(R.color.text_on_accent)) })
        panel.addView(button("取消") { dialog.dismiss() })
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply { width = resources.displayMetrics.widthPixels - dp(36) }
        panel.alpha = 0f; panel.scaleX = .96f; panel.scaleY = .96f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start()
    }
    private fun switchApiProfile(selected: TranslationApiProfile) {
        if (selected.id == currentApiProfileId) return
        saveApiProfileDraft()
        currentApiProfileId = selected.id
        prefs.edit().putString("active_api_profile", currentApiProfileId).putString("mode", "api").apply()
        selectedMode = 2
        loadApiProfile(currentApiProfileId)
        updateModeUi(false)
        LyricsOverlayService.instance?.refreshSupplementTranslation()
        showStatus("已切换到 ${selected.label}；首次使用请填写密钥并验证")
    }
    private fun saveApiProfileDraft() {
        prefs.edit()
            .putString(TranslationApiProfiles.endpointKey(currentApiProfileId), endpoint.text.toString().trim())
            .putString(TranslationApiProfiles.modelKey(currentApiProfileId), model.text.toString().trim())
            .apply()
        if (key.text.isNotBlank()) SecretStorage(this, currentApiProfileId).save(key.text.toString().trim())
    }
    private fun confirmApi() {
        val address = endpoint.text.toString().trim()
        val modelName = model.text.toString().trim()
        if (!address.startsWith("https://") || modelName.isBlank() ||
                (key.text.isBlank() && SecretStorage(this, currentApiProfileId).read().isBlank())) {
            showStatus("请完整填写 HTTPS 服务地址、模型和 API Key"); return
        }
        runCatching {
            if (key.text.isNotBlank()) SecretStorage(this, currentApiProfileId).save(key.text.toString().trim())
            selectedMode = 2
            prefs.edit().putString("mode", "api").putString("active_api_profile", currentApiProfileId)
                .putString(TranslationApiProfiles.endpointKey(currentApiProfileId), address)
                .putString(TranslationApiProfiles.modelKey(currentApiProfileId), modelName)
                .putString("language", "auto").apply()
            updateModeUi(false)
            LyricsOverlayService.instance?.refreshSupplementTranslation()
            apiVerifyButton.isEnabled = false
            apiVerifyButton.text = "正在验证 API…"
            showStatus("配置已保存，正在连接翻译服务…")
            val apiKey = key.text.toString().trim().ifBlank { SecretStorage(this, currentApiProfileId).read() }
            apiExecutor.execute {
                val tested = runCatching { SupplementTranslation(this).testApi(address, modelName, apiKey) }
                runOnUiThread {
                    apiVerifyButton.isEnabled = true
                    if (tested.isSuccess) {
                        showStatus("API 连接成功，测试翻译：${tested.getOrNull()}", true)
                        apiVerifyButton.text = "连接成功  ✓"
                        LyricsOverlayService.instance?.refreshSupplementTranslation()
                    } else {
                        val reason = tested.exceptionOrNull()?.message.orEmpty().ifBlank { "未知错误" }
                        showStatus("API 获取失败：${reason.take(180)}")
                        apiVerifyButton.text = "重试验证"
                    }
                    apiVerifyButton.postDelayed({ apiVerifyButton.text = "确认配置并验证连通性" }, 2200)
                }
            }
        }.onFailure { showStatus("设置保存失败，请重试") }
    }
    private fun refreshModels() {
        manager.getDownloadedModels(TranslateRemoteModel::class.java).addOnSuccessListener(this) { result ->
            downloaded = result.map { it.language }.toSet()
            downloadProgress.visibility = if (downloading.isEmpty()) View.GONE else View.VISIBLE
            renderLanguages()
        }.addOnFailureListener(this) {
            downloadProgress.visibility = View.GONE
            showStatus("暂时无法读取语言包状态")
        }
    }
    private fun needed(code: String) = setOf(code, "zh").filter { it != "en" }
    private fun showDownloadDialog(code: String) {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            setBackgroundResource(R.drawable.bg_ui_card)
            addView(label("下载 ${title(code)}", 20f).apply { setTextColor(col(R.color.text_primary)) })
            addView(label("将下载该语言及缺失的共用中文模型。请先确保手机代理可用；每个模型约 30 MB。", 12f).apply {
                setTextColor(col(R.color.text_secondary)); setPadding(0, 0, 0, dp(8))
            })
            addView(button("仅 Wi-Fi 下载") { dialog.dismiss(); download(code, true) }.apply {
                setBackgroundResource(R.drawable.bg_flat_button); setTextColor(col(R.color.text_on_accent))
            })
            addView(button("允许当前网络") { dialog.dismiss(); download(code, false) })
            addView(button("取消") { dialog.dismiss() })
        }
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { width = resources.displayMetrics.widthPixels - dp(36) }
        }
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply { width = resources.displayMetrics.widthPixels - dp(36) }
        panel.alpha = 0f; panel.scaleX = .96f; panel.scaleY = .96f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start()
    }
    private fun showDeleteDialog(code: String, removable: String) {
        val dialog = Dialog(this)
        val message = if (removable == "zh") {
            "删除共用中文模型后，所有离线中文翻译都需要重新下载。已缓存译文仍会保留。"
        } else {
            "删除 ${title(code)} 语言包？已缓存译文仍会保留。"
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            setBackgroundResource(R.drawable.bg_ui_card)
            addView(label("删除语言包", 20f).apply { setTextColor(col(R.color.text_primary)) })
            addView(label(message, 12f).apply {
                setTextColor(col(R.color.text_secondary)); setPadding(0, 0, 0, dp(8))
            })
            addView(button("确认删除") {
                dialog.dismiss()
                showStatus("正在删除语言包…")
                manager.deleteDownloadedModel(TranslateRemoteModel.Builder(removable).build())
                    .addOnSuccessListener(this@TranslationSettingsActivity) {
                        showStatus("语言包已删除", true); refreshModels()
                    }
                    .addOnFailureListener(this@TranslationSettingsActivity) {
                        showStatus("删除失败，请停止离线翻译后重试")
                    }
            }.apply { setBackgroundResource(R.drawable.bg_flat_button); setTextColor(col(R.color.text_on_accent)) })
            addView(button("取消") { dialog.dismiss() })
        }
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(.62f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
        dialog.window?.attributes = dialog.window?.attributes?.apply {
            width = resources.displayMetrics.widthPixels - dp(36)
        }
        panel.alpha = 0f; panel.scaleX = .96f; panel.scaleY = .96f
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).start()
    }
    private fun renderLanguages() {
        languages.removeAllViews()
        val query = search.text.toString().trim()
        val filtered = if (query.isBlank()) codes.filter { it in setOf("en", "ja", "ko", "zh") }
            else codes.filter { title(it).contains(query, true) }
        filtered.forEach { code ->
            val ready = downloaded.containsAll(needed(code))
            val label = title(code) + when {
                code in downloading -> " · 下载中…"
                ready -> " · 已下载"
                code in downloaded -> " · 缺中文模型"
                else -> " · 下载"
            }
            languages.addView(button(label) {
                if (ready || code in downloaded) {
                    val removable = if (code == "en") "zh" else code
                    showDeleteDialog(code, removable)
                } else {
                    showDownloadDialog(code)
                }
            }.apply { isEnabled = code !in downloading })
        }
    }
    private fun download(code: String, wifi: Boolean) {
        downloading += code
        downloadStartedAt[code] = System.currentTimeMillis()
        downloadProgress.visibility = View.VISIBLE
        showStatus("正在连接语言包服务…")
        downloadTicker.removeCallbacks(downloadTick)
        downloadTicker.post(downloadTick)
        renderLanguages()
        val timeout = Runnable {
            if (code in downloading) {
                downloading -= code
                downloadStartedAt.remove(code)
                downloadProgress.visibility = if (downloading.isEmpty()) View.GONE else View.VISIBLE
                showStatus("下载等待超过 2 分钟，请检查手机代理后重试；系统后台若继续完成，可点“刷新状态”确认")
                renderLanguages()
            }
            downloadTimeouts.remove(code)
        }
        downloadTimeouts[code]?.let(downloadTicker::removeCallbacks)
        downloadTimeouts[code] = timeout
        downloadTicker.postDelayed(timeout, 120_000)
        val conditions = DownloadConditions.Builder().apply { if (wifi) requireWifi() }.build()
        val tasks = needed(code).map { manager.download(TranslateRemoteModel.Builder(it).build(), conditions) }
        com.google.android.gms.tasks.Tasks.whenAll(tasks).addOnCompleteListener(this) { result ->
            downloadTimeouts.remove(code)?.let(downloadTicker::removeCallbacks)
            downloading -= code
            downloadStartedAt.remove(code)
            downloadProgress.visibility = if (downloading.isEmpty()) View.GONE else View.VISIBLE
            if (result.isSuccessful) showStatus("语言包已就绪，可以立即使用离线机翻", true)
            else showStatus("下载未完成，请确认手机当前网络可访问 Google ML Kit；电脑代理不会自动作用于手机")
            refreshModels()
            if (result.isSuccessful) LyricsOverlayService.instance?.refreshSupplementTranslation()
        }
    }
}
