package com.tcrrry.desktoplyrics

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 主页 = 全屏 Apple Music 风格歌词页。
 * WebView 加载 home_lyrics.html，本 Activity 负责：
 *   - 读取当前 MediaSession（标题/歌手/封面/进度）
 *   - 用 DirectLyricsRepository 拉歌词并喂给页面
 *   - 底部 ⋯ / 权限浮层的“设置”入口进入 SettingsActivity
 *   - 应用主题（跟随/亮/暗）
 * 悬浮窗仍由 LyricsOverlayService 负责，本页只做“播放器歌词页”。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var gate: View
    private lateinit var gateStatus: TextView

    private val overlayPrefs by lazy {
        getSharedPreferences(LyricsOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val appPrefs by lazy {
        getSharedPreferences(ThemePrefs.PREFS, Context.MODE_PRIVATE)
    }

    private val sessionManager by lazy {
        getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }
    private val listenerComponent by lazy {
        ComponentName(this, MediaListenerService::class.java)
    }

    private val repository = DirectLyricsRepository()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    private var webReady = false
    private var controller: MediaController? = null
    private var lastTrackKey = ""
    private var lyricRequestId = 0
    // 切歌时刻（elapsedRealtime 时基）。用于判断某份 PlaybackState 是新歌的还是旧歌的残留：
    // 只有 lastPositionUpdateTime >= 此值的状态才算“属于当前这首歌”，才允许墙钟外推位置。
    // 这样切歌瞬间播放器乐观上报 STATE_PLAYING（音频还没响）时不会把歌词冲到前面去。
    private var trackChangedAtElapsed = 0L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = pushSnapshot()
        override fun onPlaybackStateChanged(state: PlaybackState?) = pushSnapshot()
        override fun onSessionDestroyed() { controller = null; pushSnapshot() }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { refreshController() }

    // 高频进度推送
    private val progressTick = object : Runnable {
        override fun run() {
            pushProgressOnly()
            mainHandler.postDelayed(this, 500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题先于 setContentView
        ThemePrefs.apply(appPrefs.getString(ThemePrefs.KEY, ThemePrefs.FOLLOW))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.lyric_web)
        gate = findViewById(R.id.gate_overlay)
        gateStatus = findViewById(R.id.gate_status)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        web.setBackgroundColor(0)
        web.addJavascriptInterface(HomeBridge(), "LyricHomeNative")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webReady = true
                applyThemeToWeb()
                pushSnapshot()
            }
        }
        web.loadUrl("file:///android_asset/home_lyrics.html")

        // 权限浮层按钮
        findViewById<Button>(R.id.gate_listener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.gate_overlay_perm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
        findViewById<Button>(R.id.gate_start).setOnClickListener { tryStartOverlay() }
        findViewById<TextView>(R.id.gate_settings_link).setOnClickListener { openSettings() }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.resumeTimers()
        ThemePrefs.apply(appPrefs.getString(ThemePrefs.KEY, ThemePrefs.FOLLOW))
        updateGate()
        startSessionMonitor()
        mainHandler.post(progressTick)
        if (webReady) { applyThemeToWeb(); pushSnapshot() }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(progressTick)
        stopSessionMonitor()
        web.onPause()
        web.pauseTimers()   // 停掉 WebView 的 rAF/定时器，释放 CPU，避免开设置页/切主题卡顿
        super.onPause()
    }

    override fun onDestroy() {
        ioScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 声明了 android:configChanges="uiMode"，亮暗切换不再重建 Activity，
        // 手动把新主题喂给 WebView，消除重建带来的卡顿/闪烁。
        if (webReady) applyThemeToWeb()
    }

    // ---------- 权限浮层 ----------
    private fun hasListener(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun hasOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun updateGate() {
        val listenerOk = hasListener()
        val overlayOk = hasOverlay()
        // 只要有通知使用权就能读歌显示歌词页；两者都齐才能开悬浮窗
        if (listenerOk) {
            gate.visibility = View.GONE
        } else {
            gate.visibility = View.VISIBLE
            gateStatus.text = when {
                !listenerOk && !overlayOk -> "需要通知使用权读取播放信息；开悬浮窗还需悬浮窗权限"
                !listenerOk -> "需要通知使用权，用于读取当前播放的歌曲信息"
                else -> "准备就绪"
            }
        }
    }

    private fun tryStartOverlay() {
        if (!hasListener()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); return
        }
        if (!hasOverlay()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            ); return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, LyricsOverlayService::class.java).apply {
                action = LyricsOverlayService.ACTION_START
            }
        )
        updateGate()
    }

    private fun openSettings() {
        // 转场前先静默 WebView，避免全屏歌词的合成层在切页动画那几帧继续烧 GPU 造成卡顿
        if (webReady) { web.onPause(); web.pauseTimers() }
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---------- MediaSession ----------
    private var monitoring = false
    private fun startSessionMonitor() {
        if (monitoring) return
        if (!hasListener()) return
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, listenerComponent)
            monitoring = true
            refreshController()
        } catch (_: SecurityException) {
            // 无通知使用权
        }
    }

    private fun stopSessionMonitor() {
        if (!monitoring) return
        try { sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener) } catch (_: Exception) {}
        controller?.unregisterCallback(controllerCallback)
        monitoring = false
    }

    private fun refreshController() {
        val best = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) { emptyList() }
            .asSequence()
            .filter { it.packageName != packageName }
            .maxByOrNull { score(it) }

        if (best?.sessionToken == controller?.sessionToken) { pushSnapshot(); return }
        controller?.unregisterCallback(controllerCallback)
        controller = best
        best?.registerCallback(controllerCallback, mainHandler)
        pushSnapshot()
    }

    private fun score(c: MediaController): Int {
        val s = when (c.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> 1000
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> 800
            PlaybackState.STATE_PAUSED -> 600
            else -> 100
        }
        val hasTitle = !c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        return s + if (hasTitle) 100 else 0
    }

    private fun positionOf(state: PlaybackState?, duration: Long): Long {
        if (state == null) return 0L
        var pos = state.position.coerceAtLeast(0L)
        if (state.state == PlaybackState.STATE_PLAYING && state.playbackSpeed > 0f) {
            val now = SystemClock.elapsedRealtime()
            val updateTime = state.lastPositionUpdateTime
            val elapsed = (now - updateTime).coerceAtLeast(0L)
            // 只有当这份 PlaybackState 的位置更新发生在“切歌之后”，且外推的经过时间不夸张时，
            // 才按墙钟外推。否则视为切歌瞬间的乐观 PLAYING / 上一首残留状态，直接用其上报的原始位置，
            // 避免歌词先跑、音频响起后又倒回的抖动。
            val trustable = updateTime >= trackChangedAtElapsed && elapsed <= MAX_EXTRAPOLATE_MS
            if (trustable) {
                pos += (elapsed * state.playbackSpeed).toLong()
            }
        }
        return if (duration > 0) pos.coerceAtMost(duration) else pos
    }

    private fun firstString(md: MediaMetadata?, vararg keys: String): String {
        if (md == null) return ""
        for (k in keys) md.getString(k)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun pushSnapshot() {
        if (!webReady) return
        val c = controller
        if (c == null) {
            evalJs("window.LyricHome && window.LyricHome.setSnapshot(${jsonStr(JSONObject().put("track", "").toString())});")
            return
        }
        val md = c.metadata
        val pb = c.playbackState
        val title = firstString(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = firstString(
            md,
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_AUTHOR,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE
        )
        val album = firstString(md, MediaMetadata.METADATA_KEY_ALBUM)
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val stateStr = when (pb?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            else -> "paused"
        }
        val cover = coverDataUrl(md)
        val actions = pb?.actions ?: 0L
        val canPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        val canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        val snapshot = JSONObject()
            .put("track", title)
            .put("artist", artist)
            .put("cover", cover)
            .put("positionMs", positionOf(pb, duration))
            .put("durationMs", duration.coerceAtLeast(0L))
            .put("state", stateStr)
            .put("canPrev", canPrev)
            .put("canNext", canNext)
        evalJs("window.LyricHome && window.LyricHome.setSnapshot(${jsonStr(snapshot.toString())});")

        // 换歌 → 拉歌词
        val key = "$title\u0000$artist\u0000$album"
        if (title.isNotBlank() && key != lastTrackKey) {
            lastTrackKey = key
            // 记录切歌时刻：此后只有“更新时间晚于切歌时刻”的 PlaybackState 才允许墙钟外推位置
            trackChangedAtElapsed = SystemClock.elapsedRealtime()
            fetchLyrics(title, artist, album, duration)
        } else if (title.isBlank()) {
            lastTrackKey = ""
        }
    }

    private fun pushProgressOnly() {
        if (!webReady) return
        val c = controller ?: return
        val pb = c.playbackState ?: return
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = pb.state == PlaybackState.STATE_PLAYING
        evalJs("window.LyricHome && window.LyricHome.setProgress(${positionOf(pb, duration)}, $playing);")
    }

    private var coverCacheKey = ""
    private var coverCacheUrl = ""
    private fun coverDataUrl(md: MediaMetadata?): String {
        val key = firstString(md, MediaMetadata.METADATA_KEY_TITLE) + "|" +
            firstString(md, MediaMetadata.METADATA_KEY_ARTIST)
        if (key == coverCacheKey && coverCacheUrl.isNotEmpty()) return coverCacheUrl
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return ""
        val out = java.io.ByteArrayOutputStream()
        val maxSide = maxOf(bmp.width, bmp.height)
        val scaled = if (maxSide > 400) {
            val r = 400f / maxSide
            android.graphics.Bitmap.createScaledBitmap(
                bmp, (bmp.width * r).toInt().coerceAtLeast(1), (bmp.height * r).toInt().coerceAtLeast(1), true
            )
        } else bmp
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bmp) scaled.recycle()
        coverCacheKey = key
        coverCacheUrl = "data:image/jpeg;base64," +
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        return coverCacheUrl
    }

    // ---------- 歌词拉取（带本地缓存） ----------
    private val lyricCachePrefs by lazy {
        getSharedPreferences("home_lyric_cache_v1", Context.MODE_PRIVATE)
    }

    private fun cacheKeyOf(track: String, artist: String, album: String) =
        "$track\u0000$artist\u0000$album"

    private fun applyLyricPayload(lrc: String, trans: String, word: String) {
        if (lrc.isBlank()) { evalJs("window.LyricHome && window.LyricHome.setLyrics([]);"); return }
        evalJs("window.LyricHome && window.LyricHome.setLyricsFromLrc(${jsonStr(lrc)},${jsonStr(trans)},${jsonStr(word)});")
    }

    private fun fetchLyrics(track: String, artist: String, album: String, durationMs: Long) {
        val reqId = ++lyricRequestId
        val cacheKey = cacheKeyOf(track, artist, album)

        // 1) 命中缓存立即上屏（无空白/无闪烁），随后不再打网络
        val cached = runCatching {
            lyricCachePrefs.getString(cacheKey, null)?.let { JSONObject(it) }
        }.getOrNull()
        if (cached != null && cached.optString("lyrics").isNotBlank()) {
            applyLyricPayload(
                cached.optString("lyrics"),
                cached.optString("translated"),
                cached.optString("word")
            )
            // 命中的旧缓存若已带来源信息，补一条歌词源管理记录（供“歌词源管理”页展示）
            mirrorToMatchMemory(
                track, artist, album, durationMs,
                cached.optString("source"), cached.optString("recordId"),
                cached.optString("lyrics"), cached.optString("translated"), cached.optString("word")
            )
            return
        }

        // 2) 未命中才清空并联网
        evalJs("window.LyricHome && window.LyricHome.clear();")
        ioScope.launch {
            val result = runCatching {
                repository.resolveLyrics(track, artist, album, durationMs)
            }.getOrNull()
            if (reqId != lyricRequestId) return@launch
            val lrc = result?.lyrics.orEmpty()
            val trans = result?.translatedLyrics.orEmpty()
            val word = result?.wordLyrics.orEmpty()
            val source = result?.source.orEmpty()
            val recordId = result?.recordId.orEmpty()
            if (lrc.isNotBlank()) {
                // 写缓存（限量，避免无限膨胀）
                runCatching {
                    val obj = JSONObject()
                        .put("lyrics", lrc).put("translated", trans).put("word", word)
                        .put("source", source).put("recordId", recordId)
                        .put("at", System.currentTimeMillis())
                    val editor = lyricCachePrefs.edit().putString(cacheKey, obj.toString())
                    if (lyricCachePrefs.all.size > 60) {
                        // 简单清理：删掉最旧的一半
                        lyricCachePrefs.all.entries
                            .sortedBy { (runCatching { JSONObject(it.value as String).optLong("at") }.getOrDefault(0L)) }
                            .take(30).forEach { editor.remove(it.key) }
                    }
                    editor.apply()
                }
                // 镜像到歌词源管理记忆（与悬浮窗共用同一份 match_memory_v2）
                mirrorToMatchMemory(track, artist, album, durationMs, source, recordId, lrc, trans, word)
            }
            withContext(Dispatchers.Main) {
                if (reqId != lyricRequestId) return@withContext
                applyLyricPayload(lrc, trans, word)
            }
        }
    }

    /**
     * 把主页拉到的歌词写进「歌词源管理」共用的 match_memory_v2（存于悬浮窗 prefs）。
     * 仅在该歌+来源尚无记录时新增，绝不覆盖用户在悬浮窗里手动选过的版本。
     * key 格式与悬浮窗 matchMemoryKey 完全一致：normalizedKey|durationSec|source。
     */
    private fun mirrorToMatchMemory(
        track: String, artist: String, album: String, durationMs: Long,
        source: String, recordId: String, lrc: String, trans: String, word: String
    ) {
        if (track.isBlank() || source.isBlank() || lrc.isBlank()) return
        runCatching {
            val normKey = ("$track\u0000$artist").trim().lowercase(java.util.Locale.ROOT)
            val durSec = Math.round(durationMs / 1000.0)
            val key = "$normKey|$durSec|$source"

            val arr = org.json.JSONArray(
                overlayPrefs.getString("match_memory_v2", "[]").orEmpty().ifBlank { "[]" }
            )
            // 已存在同 key（含悬浮窗手动选择）则不动
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("key") == key) return
            }

            val candidate = JSONObject()
                .put("lyrics", lrc)
                .put("translatedLyrics", trans)
                .put("wordLyrics", word)
                .put("duration", durationMs)
                .put("cover", "")
                .put("source", source)
                .put("recordId", recordId)
                .put("title", track)
                .put("artist", artist)
                .put("matchScore", 0)
            val entry = JSONObject()
                .put("key", key)
                .put("at", System.currentTimeMillis())
                .put("title", track)
                .put("artist", artist)
                .put("album", album)
                .put("duration", durationMs)
                .put("source", source)
                .put("candidate", candidate)
                .put("original", candidate)
                .put("history", org.json.JSONArray().put(candidate))
                .put("needsReview", false)
            arr.put(entry)
            overlayPrefs.edit().putString("match_memory_v2", arr.toString()).apply()
        }
    }

    // ---------- 主题喂给 WebView ----------
    private fun applyThemeToWeb() {
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        evalJs("window.LyricHome && window.LyricHome.setTheme('${if (night) "dark" else "light"}');")
    }

    // ---------- JS 桥 ----------
    inner class HomeBridge {
        @JavascriptInterface
        fun openMore() {
            runOnUiThread { openSettings() }
        }

        @JavascriptInterface
        fun seekTo(positionMs: Double) {
            if (!positionMs.isFinite()) return
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                runCatching { c.transportControls.seekTo(positionMs.toLong().coerceAtLeast(0L)) }
                mainHandler.postDelayed({ pushSnapshot() }, 180)
            }
        }

        @JavascriptInterface
        fun togglePlay() {
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                runCatching {
                    if (playing) c.transportControls.pause() else c.transportControls.play()
                }
                mainHandler.postDelayed({ pushSnapshot() }, 120)
            }
        }

        @JavascriptInterface
        fun skipPrev() {
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                runCatching { c.transportControls.skipToPrevious() }
                mainHandler.postDelayed({ pushSnapshot() }, 180)
            }
        }

        @JavascriptInterface
        fun skipNext() {
            runOnUiThread {
                val c = controller ?: return@runOnUiThread
                runCatching { c.transportControls.skipToNext() }
                mainHandler.postDelayed({ pushSnapshot() }, 180)
            }
        }
    }

    // ---------- helpers ----------
    private fun evalJs(js: String) {
        if (!webReady) return
        web.evaluateJavascript(js, null)
    }

    /** 把字符串安全地作为 JS 字符串字面量传入。 */
    private fun jsonStr(s: String): String = JSONObject.quote(s)

    companion object {
        // 墙钟外推的经过时间上限；超过它说明这份 PlaybackState 已陈旧（如切歌残留），不外推。
        private const val MAX_EXTRAPOLATE_MS = 1500L
    }
}
