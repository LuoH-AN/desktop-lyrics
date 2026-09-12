package com.tcrrry.desktoplyrics

import android.icu.text.Transliterator
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves lyrics directly from public music services. No request is routed through
 * a Lobsta/Tcrrry-owned server. MediaSession artwork remains the preferred cover;
 * QQ Music and NetEase artwork are only used when the player did not publish one.
 */
class DirectLyricsRepository {
    private val latinTransliterator by lazy {
        Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    }
    data class Result(
        val lyrics: String = "",
        val translatedLyrics: String = "",
        val wordLyrics: String = "",
        val durationMs: Long = 0L,
        val cover: String = "",
        val source: String = "",
        val score: Int = 0,
        val alternatives: List<Result> = emptyList()
    ) {
        private fun candidateJson(): JSONObject = JSONObject()
            .put("lyrics", lyrics)
            .put("translatedLyrics", translatedLyrics)
            .put("wordLyrics", wordLyrics)
            .put("duration", durationMs)
            .put("cover", cover)
            .put("source", source)

        fun toJson(): JSONObject = candidateJson().put(
            "alternatives",
            JSONArray().apply {
                put(candidateJson())
                alternatives.forEach { put(it.candidateJson()) }
            }
        )
    }

    private data class JsonMatch(val value: JSONObject, val score: Int)
    private data class SearchPlan(val track: String, val query: String, val includesArtist: Boolean)
    private data class ResolvedIdentity(val track: String, val artist: String, val album: String)
    private val identityCache = ConcurrentHashMap<String, ResolvedIdentity>()

    private val executor = Executors.newFixedThreadPool(6) { runnable ->
        Thread(runnable, "direct-lyrics").apply { isDaemon = true }
    }

    fun resolveLyrics(
        track: String,
        artist: String,
        album: String = "",
        expectedDurationMs: Long = 0L
    ): Result {
        val direct = resolveFromProviders(track, artist, expectedDurationMs)
        if (isUsableLyrics(direct.lyrics)) return direct

        val identity = runCatching {
            resolveLocalizedIdentity(track, artist, album, expectedDurationMs)
        }.onFailure { error ->
            Log.w(
                LOG_TAG,
                "Lyrics identity bridge failed for ${track.take(48)}: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(80)}"
            )
        }.getOrNull() ?: return direct
        if (normalize(identity.track) == normalize(track) &&
            normalize(identity.artist) == normalize(artist)
        ) return direct

        Log.i(
            LOG_TAG,
            "Lyrics identity bridge ${track.take(40)} / ${artist.take(32)} -> " +
                "${identity.track.take(40)} / ${identity.artist.take(32)}"
        )
        return resolveFromProviders(identity.track, identity.artist, expectedDurationMs)
    }

    private fun resolveFromProviders(track: String, artist: String, expectedDurationMs: Long): Result {
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOf(
            completion.submit(Callable {
                querySource("LRCLIB", track) { queryLrcLib(track, artist, expectedDurationMs) }
            }),
            completion.submit(Callable {
                querySource("QQ", track) {
                    queryQqMusic(track, artist, includeLyrics = true, expectedDurationMs)
                }
            }),
            completion.submit(Callable {
                querySource("NetEase", track) {
                    queryNetEase(track, artist, includeLyrics = true, expectedDurationMs)
                }
            })
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LYRICS_DEADLINE_MS)
        val candidates = mutableListOf<Result>()
        var completed = 0
        var firstCandidateAt = 0L

        try {
            while (completed < futures.size) {
                val candidateDeadline = if (firstCandidateAt == 0L) {
                    deadline
                } else {
                    minOf(deadline, firstCandidateAt + TimeUnit.MILLISECONDS.toNanos(SOURCE_GRACE_MS))
                }
                val remaining = candidateDeadline - System.nanoTime()
                if (remaining <= 0L) break
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                completed++
                val candidate = runCatching { future.get() }.getOrNull()
                    ?.takeIf { isUsableLyrics(it.lyrics) && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null) {
                    candidates += candidate
                    if (firstCandidateAt == 0L) firstCandidateAt = System.nanoTime()
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        val ranked = candidates
            .distinctBy { "${it.source}\u0000${it.lyrics}" }
            .sortedByDescending(::qualityRank)
        val primary = ranked.firstOrNull() ?: return Result()
        return primary.copy(alternatives = ranked.drop(1))
    }

    fun resolveCover(track: String, artist: String): String {
        val completion = ExecutorCompletionService<Result?>(executor)
        val futures = listOf(
            completion.submit(Callable { queryQqMusic(track, artist, includeLyrics = false) }),
            completion.submit(Callable { queryNetEase(track, artist, includeLyrics = false) })
        )
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COVER_DEADLINE_MS)
        var best: Result? = null

        try {
            repeat(futures.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return@repeat
                val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: return@repeat
                val candidate = runCatching { future.get() }.getOrNull()
                    ?.takeIf { it.cover.isNotBlank() && it.score >= MIN_ACCEPTABLE_SCORE }
                if (candidate != null && (best == null || candidate.score > best!!.score)) {
                    best = candidate
                }
                if (candidate != null && candidate.score >= EXACT_MATCH_SCORE) {
                    return candidate.cover
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }
        return best?.cover.orEmpty()
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun querySource(source: String, track: String, block: () -> Result?): Result? {
        return try {
            val result = block()
            Log.d(
                LOG_TAG,
                "Lyrics provider=$source track=${track.take(48)} " +
                    "candidate=${result != null} score=${result?.score ?: 0}"
            )
            result
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(
                LOG_TAG,
                "Lyrics provider=$source track=${track.take(48)} failed: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(80)}"
            )
            null
        }
    }

    private fun queryLrcLib(track: String, artist: String, expectedDurationMs: Long): Result? {
        val url = "https://lrclib.net/api/search?track_name=${encode(track)}&artist_name=${encode(artist)}"
        val list = JSONArray(getText(url, mapOf("Accept" to "application/json")))
        var best: Result? = null
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val syncedLyrics = item.optString("syncedLyrics")
            val plainLyrics = item.optString("plainLyrics")
            val lyrics = if (isUsableLyrics(syncedLyrics)) syncedLyrics else plainLyrics
            if (!isUsableLyrics(lyrics)) continue
            val synced = isUsableLyrics(syncedLyrics)
            val durationMs = (item.optDouble("duration", 0.0) * 1000.0).toLong()
            val score = platformMatchScore(
                track,
                artist,
                item.optString("trackName"),
                item.optString("artistName"),
                expectedDurationMs,
                durationMs
            ) + if (synced) 5 else 0
            val result = Result(
                lyrics = lyrics,
                durationMs = durationMs,
                source = "LRCLIB",
                score = score
            )
            if (best == null || result.score > best.score) best = result
        }
        return best
    }

    private fun queryQqMusic(
        track: String,
        artist: String,
        includeLyrics: Boolean,
        expectedDurationMs: Long = 0L
    ): Result? {
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://y.qq.com/",
            "User-Agent" to USER_AGENT
        )
        planLoop@ for (plan in searchPlans(track, artist)) {
            val searchTrack = plan.track
            val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
                "?format=json&p=1&n=${searchResultLimit(searchTrack)}&w=${encode(plan.query)}"
            val root = JSONObject(getText(searchUrl, headers))
            val songs = root.optJSONObject("data")
                ?.optJSONObject("song")
                ?.optJSONArray("list") ?: continue
            val rejectedSongMids = mutableSetOf<String>()
            while (rejectedSongMids.size < MAX_LYRIC_CANDIDATES_PER_QUERY) {
                val match = firstJsonMatch(
                    songs,
                    track,
                    searchTrack,
                    artist,
                    allowRankFallback = plan.includesArtist,
                    expectedDurationMs = expectedDurationMs,
                    candidateDurationMs = { it.optLong("interval", 0L) * 1000L },
                    isUsable = { item ->
                        val mid = item.optString("songmid")
                        mid !in rejectedSongMids &&
                            (!includeLyrics || (mid.isNotBlank() && mid != "0"))
                    }
                ) { item ->
                    val singers = item.optJSONArray("singer").joinNames("name")
                    Triple(item.optString("songname").ifBlank { item.optString("songorig") }, singers, item)
                } ?: continue@planLoop
                val song = match.value
                val score = match.score
                val albumMid = song.optString("albummid")
                val albumId = song.optLong("albumid", 0L)
                val cover = when {
                    albumMid.isNotBlank() && !albumMid.all(Char::isDigit) ->
                        "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
                    albumId > 0L ->
                        "https://y.gtimg.cn/music/photo/album_500/${albumId % 100}/500_albumpic_${albumId}_0.jpg"
                    else -> ""
                }
                if (!includeLyrics) return Result(cover = cover, source = "QQ音乐", score = score)

                val songMid = song.optString("songmid")
                val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${encode(songMid)}&format=json&nobase64=1&trans=1"
                val lyricRoot = parseJsonFlexible(getBytes(lyricUrl, headers))
                val lyrics = lyricRoot?.optString("lyric")?.let(::unescapeHtml).orEmpty()
                if (!isUsableLyrics(lyrics)) {
                    rejectedSongMids += songMid
                    continue
                }
                return Result(
                    lyrics = lyrics,
                    translatedLyrics = unescapeHtml(lyricRoot?.optString("trans").orEmpty()),
                    durationMs = song.optLong("interval", 0L) * 1000L,
                    cover = cover,
                    source = "QQ音乐",
                    score = score + 5
                )
            }
        }
        return null
    }

    private fun queryNetEase(
        track: String,
        artist: String,
        includeLyrics: Boolean,
        expectedDurationMs: Long = 0L
    ): Result? {
        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "https://music.163.com/",
            "User-Agent" to USER_AGENT
        )
        planLoop@ for (plan in searchPlans(track, artist)) {
            val searchTrack = plan.track
            val searchUrl = "https://music.163.com/api/search/get/web" +
                "?type=1&limit=${searchResultLimit(searchTrack)}&s=${encode(plan.query)}"
            val root = JSONObject(getText(searchUrl, headers))
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: continue
            val rejectedSongIds = mutableSetOf<Long>()
            while (rejectedSongIds.size < MAX_LYRIC_CANDIDATES_PER_QUERY) {
                val match = firstJsonMatch(
                    songs,
                    track,
                    searchTrack,
                    artist,
                    allowRankFallback = plan.includesArtist,
                    expectedDurationMs = expectedDurationMs,
                    candidateDurationMs = { it.optLong("duration", it.optLong("dt", 0L)) },
                    isUsable = { item ->
                        val id = item.optLong("id", 0L)
                        id > 0L && id !in rejectedSongIds
                    }
                ) { item ->
                    val artists = (item.optJSONArray("artists") ?: item.optJSONArray("ar")).joinNames("name")
                    Triple(item.optString("name"), artists, item)
                } ?: continue@planLoop
                val song = match.value
                val score = match.score
                val album = song.optJSONObject("album") ?: song.optJSONObject("al")
                var cover = album?.optString("picUrl").orEmpty()
                val songId = song.optLong("id", 0L)

                if (cover.isBlank()) {
                    val detailUrl = "https://music.163.com/api/song/detail/?id=$songId&ids=[$songId]"
                    val detail = runCatching { JSONObject(getText(detailUrl, headers)) }.getOrNull()
                    val detailSong = detail?.optJSONArray("songs")?.optJSONObject(0)
                    cover = (detailSong?.optJSONObject("album") ?: detailSong?.optJSONObject("al"))
                        ?.optString("picUrl").orEmpty()
                }
                if (!includeLyrics) return Result(cover = cover, source = "网易云音乐", score = score)

                val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId" +
                    "&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1"
                val lyricRoot = JSONObject(getText(lyricUrl, headers))
                val lyrics = lyricRoot.optJSONObject("lrc")?.optString("lyric").orEmpty()
                if (!isUsableLyrics(lyrics)) {
                    rejectedSongIds += songId
                    continue
                }
                return Result(
                    lyrics = lyrics,
                    translatedLyrics = lyricRoot.optJSONObject("tlyric")?.optString("lyric").orEmpty(),
                    wordLyrics = lyricRoot.optJSONObject("yrc")?.optString("lyric")
                        .orEmpty()
                        .ifBlank { lyricRoot.optJSONObject("klyric")?.optString("lyric").orEmpty() },
                    durationMs = song.optLong("duration", song.optLong("dt", 0L)),
                    cover = cover,
                    source = "网易云音乐",
                    score = score + 5
                )
            }
        }
        return null
    }

    private fun firstJsonMatch(
        array: JSONArray,
        track: String,
        searchTrack: String,
        artist: String,
        allowRankFallback: Boolean = true,
        expectedDurationMs: Long = 0L,
        candidateDurationMs: (JSONObject) -> Long = { 0L },
        isUsable: (JSONObject) -> Boolean = { true },
        fields: (JSONObject) -> Triple<String, String, JSONObject>
    ): JsonMatch? {
        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (!isUsable(item)) continue
            val (title, singer, value) = fields(item)
            val originalScore = platformMatchScore(
                track, artist, title, singer, expectedDurationMs, candidateDurationMs(item)
            )
            val variantScore = if (normalize(searchTrack) != normalize(track)) {
                platformMatchScore(
                    searchTrack, artist, title, singer, expectedDurationMs, candidateDurationMs(item)
                )
            } else {
                originalScore
            }
            val rankScore = if (allowRankFallback) rankedSearchFallbackScore(
                searchTrack,
                artist,
                title,
                singer,
                expectedDurationMs,
                candidateDurationMs(item),
                index
            ) else 0
            val score = maxOf(
                originalScore,
                variantScore,
                rankScore
            )
            if (score >= MIN_ACCEPTABLE_SCORE && score > bestScore) {
                best = value
                bestScore = score
            }
        }
        return best?.let { JsonMatch(it, bestScore) }
    }

    private fun searchTrackVariants(track: String): List<String> {
        val simplified = track
            .replace(Regex("\\s*[（(][^）)]*[）)]\\s*"), " ")
            .replace(Regex("\\s*[【\\[].*?[】\\]]\\s*"), " ")
            .trim()
        return listOf(track.trim(), simplified)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun searchPlans(track: String, artist: String): List<SearchPlan> {
        val variants = searchTrackVariants(track)
        return buildList {
            variants.forEach { variant ->
                add(SearchPlan(variant, "$variant $artist".trim(), includesArtist = artist.isNotBlank()))
            }
            if (artist.isNotBlank()) {
                variants.forEach { variant ->
                    add(SearchPlan(variant, variant, includesArtist = false))
                }
            }
        }.distinctBy { it.query }
    }

    /**
     * The iTunes catalogue is used only as an identity bridge after all lyric
     * providers fail. It is especially useful when a player exposes localized
     * English or Chinese metadata while lyric providers index Japanese names.
     * No preview audio or artwork is downloaded.
     */
    private fun resolveLocalizedIdentity(
        track: String,
        artist: String,
        album: String,
        expectedDurationMs: Long
    ): ResolvedIdentity? {
        if (expectedDurationMs <= 0L) return null
        val cacheKey = listOf(track, artist, album, (expectedDurationMs / 2_000L).toString())
            .joinToString("\u0000") { normalize(it) }
        identityCache[cacheKey]?.let { return it }

        val query = "$track $artist".trim()
        val url = "https://itunes.apple.com/search?term=${encode(query)}" +
            "&media=music&entity=song&limit=20&country=jp&lang=ja_jp"
        val root = JSONObject(
            getText(
                url,
                mapOf("Accept" to "application/json", "User-Agent" to USER_AGENT)
            )
        )
        val results = root.optJSONArray("results") ?: return null
        val ranked = buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                if (item.optString("kind") != "song") continue
                val candidateTrack = item.optString("trackName")
                val candidateArtist = item.optString("artistName")
                val candidateAlbum = item.optString("collectionName")
                val candidateDuration = item.optLong("trackTimeMillis", 0L)
                val durationDelta = kotlin.math.abs(expectedDurationMs - candidateDuration)
                if (candidateTrack.isBlank() || candidateArtist.isBlank() || durationDelta > 15_000L) continue

                val inputVersions = versionTags(track)
                val candidateVersions = versionTags(candidateTrack)
                if (candidateVersions.any { it !in inputVersions }) continue

                val durationPoints = when (durationDelta) {
                    in 0L..3_000L -> 40
                    in 3_001L..8_000L -> 32
                    else -> 20
                }
                val titlePoints = (textSimilarity(coreTitle(track), coreTitle(candidateTrack)) * 35).toInt()
                val artistPoints = (textSimilarity(artist, candidateArtist) * 20).toInt()
                val albumPoints = (textSimilarity(album, candidateAlbum) * 20).toInt()
                val rankPoints = (10 - index).coerceAtLeast(0)
                add(
                    Pair(
                        ResolvedIdentity(candidateTrack, candidateArtist, candidateAlbum),
                        durationPoints + titlePoints + artistPoints + albumPoints + rankPoints
                    )
                )
            }
        }.groupBy { "${normalize(it.first.track)}\u0000${normalize(it.first.artist)}" }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.second } }
            .sortedByDescending { it.second }

        val best = ranked.firstOrNull() ?: return null
        val secondScore = ranked.getOrNull(1)?.second ?: Int.MIN_VALUE
        if (best.second < IDENTITY_MIN_SCORE) return null
        if (secondScore != Int.MIN_VALUE && best.second < IDENTITY_STRONG_SCORE &&
            best.second - secondScore < IDENTITY_MIN_MARGIN
        ) return null
        identityCache[cacheKey] = best.first
        return best.first
    }

    private fun coreTitle(value: String): String = value
        .replace(Regex("\\s*[（(][^）)]*[）)]\\s*"), " ")
        .replace(Regex("\\s*[【\\[].*?[】\\]]\\s*"), " ")
        .replace(Regex("\\s*(?:-|/)?\\s*(?:feat\\.?|ft\\.?|with)\\s+.+$", RegexOption.IGNORE_CASE), " ")
        .trim()

    private fun textSimilarity(first: String, second: String): Double {
        val left = normalize(first)
        val right = normalize(second)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left in right || right in left) {
            return minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length)
        }
        return latinSimilarity(first, second).coerceAtLeast(
            1.0 - editDistance(left, right).toDouble() / maxOf(left.length, right.length).toDouble()
        )
    }

    private fun versionTags(value: String): Set<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return buildSet {
            if (Regex("\\blive\\b|现场|ライヴ|ライブ").containsMatchIn(normalized)) add("live")
            if (Regex("instrumental|伴奏|纯音乐|純音楽|off\\s*vocal").containsMatchIn(normalized)) add("instrumental")
            if (Regex("remix|リミックス").containsMatchIn(normalized)) add("remix")
            if (Regex("acoustic|アコースティック").containsMatchIn(normalized)) add("acoustic")
            if (Regex("karaoke|カラオケ").containsMatchIn(normalized)) add("karaoke")
        }
    }

    private fun searchResultLimit(track: String): Int {
        val searchableLength = normalize(track).length
        return if (searchableLength <= 3) 30 else 12
    }

    /**
     * Provider rank is useful evidence only when duration is very close. It is
     * deliberately combined with either the queried title or the artist, so a
     * random first result can never pass on rank alone.
     */
    private fun rankedSearchFallbackScore(
        searchTrack: String,
        artist: String,
        candidateTrack: String,
        candidateArtist: String,
        expectedDurationMs: Long,
        candidateDurationMs: Long,
        resultIndex: Int
    ): Int {
        if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return 0
        val durationDelta = kotlin.math.abs(expectedDurationMs - candidateDurationMs)
        if (durationDelta > 8_000L) return 0

        val wantedTitle = titleIdentityKey(searchTrack)
        val foundTitle = titleIdentityKey(candidateTrack)
        val titleExact = wantedTitle.isNotBlank() && wantedTitle == foundTitle
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        val artistStrong = wantedArtist.isNotBlank() && foundArtist.isNotBlank() &&
            (wantedArtist == foundArtist || wantedArtist in foundArtist || foundArtist in wantedArtist ||
                latinSimilarity(artist, candidateArtist) >= 0.58)
        val durationBonus = durationScore(expectedDurationMs, candidateDurationMs)

        // Handles provider-localized stage names and symbolic titles such as
        // "MIREI" -> "當山みれい" and "^^", without accepting arbitrary hits.
        if (titleExact && resultIndex <= 8) {
            return 82 + durationBonus - resultIndex.coerceAtMost(8)
        }
        // Handles localized titles such as "Till I Know What Love Is" ->
        // "愛を知るまでは" when the artist, duration and top search rank agree.
        if (artistStrong && resultIndex <= 2) {
            return 84 + durationBonus - resultIndex
        }
        return 0
    }

    private fun matchScore(track: String, artist: String, candidateTrack: String, candidateArtist: String): Int {
        val wantedTrack = normalize(track)
        val foundTrack = normalize(candidateTrack)
        val wantedArtist = normalize(artist)
        val foundArtist = normalize(candidateArtist)
        if (wantedTrack.isBlank() || foundTrack.isBlank()) return 0
        if (
            wantedArtist.isNotBlank() &&
            foundArtist.isNotBlank() &&
            wantedArtist !in foundArtist &&
            foundArtist !in wantedArtist
        ) return 0

        val titleScore = when {
            wantedTrack == foundTrack -> 80
            wantedTrack.length >= 4 && (wantedTrack in foundTrack || foundTrack in wantedTrack) -> 62
            commonPrefixRatio(wantedTrack, foundTrack) >= 0.72 -> 50
            else -> 0
        }
        val artistScore = when {
            wantedArtist.isBlank() -> 12
            wantedArtist == foundArtist -> 20
            wantedArtist.length >= 2 && (wantedArtist in foundArtist || foundArtist in wantedArtist) -> 17
            else -> 0
        }
        return titleScore + artistScore
    }

    private fun platformMatchScore(
        track: String,
        artist: String,
        candidateTrack: String,
        candidateArtist: String,
        expectedDurationMs: Long = 0L,
        candidateDurationMs: Long = 0L
    ): Int {
        val strict = matchScore(track, artist, candidateTrack, candidateArtist)
        val durationBonus = durationScore(expectedDurationMs, candidateDurationMs)
        if (strict >= MIN_ACCEPTABLE_SCORE) return strict + durationBonus

        val wantedTitle = titleIdentityKey(track)
        val foundTitle = titleIdentityKey(candidateTrack)
        val titleExact = wantedTitle.isNotBlank() && wantedTitle == foundTitle
        val titleLatinSimilarity = latinSimilarity(track, candidateTrack)
        val artistLatinSimilarity = latinSimilarity(artist, candidateArtist)
        val bothArtistsUseCjk = containsCjk(artist) && containsCjk(candidateArtist)
        val durationReliable = expectedDurationMs > 0L && candidateDurationMs > 0L &&
            kotlin.math.abs(expectedDurationMs - candidateDurationMs) <= 12_000L

        if (!durationReliable) return strict
        if (titleExact && (artistLatinSimilarity >= 0.58 || bothArtistsUseCjk)) return 88 + durationBonus
        if (titleLatinSimilarity >= 0.56 &&
            (normalize(artist) == normalize(candidateArtist) || artistLatinSimilarity >= 0.58)
        ) return 84 + durationBonus
        return strict
    }

    private fun containsCjk(value: String): Boolean =
        Regex("[\\u3040-\\u30FF\\u3400-\\u9FFF\\uAC00-\\uD7AF]").containsMatchIn(value)

    private fun durationScore(expectedMs: Long, candidateMs: Long): Int {
        if (expectedMs <= 0L || candidateMs <= 0L) return 0
        return when (kotlin.math.abs(expectedMs - candidateMs)) {
            in 0L..3_000L -> 12
            in 3_001L..8_000L -> 8
            in 8_001L..15_000L -> 4
            in 15_001L..35_000L -> -5
            else -> -25
        }
    }

    private fun latinSimilarity(first: String, second: String): Double {
        val left = normalize(latinTransliterator.transliterate(first))
        val right = normalize(latinTransliterator.transliterate(second))
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        val distance = editDistance(left, right)
        return 1.0 - distance.toDouble() / maxOf(left.length, right.length).toDouble()
    }

    private fun editDistance(first: String, second: String): Int {
        var previous = IntArray(second.length + 1) { it }
        for (i in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = i + 1
            for (j in second.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (first[i] == second[j]) 0 else 1
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[（(\\[].*?(live|remaster|版|伴奏|纯音乐|翻唱).*?[）)\\]]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun titleIdentityKey(value: String): String {
        val alphanumeric = normalize(value)
        if (alphanumeric.isNotBlank()) return alphanumeric
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
    }

    private fun commonPrefixRatio(first: String, second: String): Double {
        val limit = minOf(first.length, second.length)
        var same = 0
        while (same < limit && first[same] == second[same]) same++
        return same.toDouble() / maxOf(first.length, second.length).toDouble()
    }

    private fun qualityRank(result: Result): Int {
        val confidenceBand = if (result.score >= EXACT_MATCH_SCORE) 2 else 1
        return confidenceBand * 100_000 +
        (if (isUsableLyrics(result.translatedLyrics)) 20_000 else 0) +
        (if (isUsableLyrics(result.wordLyrics)) 10_000 else 0) +
        result.score * 100 +
        lyricBodyScore(result.lyrics) +
        when (result.source) {
            "网易云音乐" -> 4
            "QQ音乐" -> 2
            else -> 0
        }
    }

    private fun isUsableLyrics(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.equals("null", true) ||
            normalized.equals("undefined", true)
        ) return false

        val body = normalized.lineSequence()
            .map { it.replace(Regex("^(\\[[^]]+])+"), "").trim() }
            .filter { it.isNotBlank() }
            .joinToString("")
            .replace(Regex("[\\s,，。.!！?？、]"), "")
        if (body.length <= 48 && PLACEHOLDER_LYRICS.any { it.matches(body) }) return false
        return true
    }

    private fun lyricBodyScore(lyrics: String): Int {
        val credit = Regex("^(作词|作詞|作曲|编曲|編曲|词|詞|曲|composer|lyricist|arranger)\\s*[:：]", RegexOption.IGNORE_CASE)
        val count = lyrics.lineSequence().count { raw ->
            val text = raw.replace(Regex("^(\\[[^]]+])+"), "").trim()
            text.isNotBlank() && !credit.containsMatchIn(text) &&
                !text.contains("纯音乐，请欣赏")
        }
        return count.coerceAtMost(40) * 3
    }

    private fun JSONArray?.joinNames(key: String): String {
        if (this == null) return ""
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.optString(key)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("/")
    }

    private fun getText(url: String, headers: Map<String, String>): String =
        getBytes(url, headers).toString(Charsets.UTF_8)

    private fun getBytes(url: String, headers: Map<String, String>): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            headers.forEach(connection::setRequestProperty)
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw IllegalStateException("Response too large")
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonFlexible(bytes: ByteArray): JSONObject? {
        return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
            runCatching { JSONObject(bytes.toString(Charset.forName("GBK"))) }.getOrNull()
        }
    }

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val LOG_TAG = "DesktopLyrics"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val LYRICS_DEADLINE_MS = 10_000L
        private const val SOURCE_GRACE_MS = 9_000L
        private const val COVER_DEADLINE_MS = 6_000L
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MIN_ACCEPTABLE_SCORE = 50
        private const val EXACT_MATCH_SCORE = 95
        private const val IDENTITY_MIN_SCORE = 50
        private const val IDENTITY_STRONG_SCORE = 75
        private const val IDENTITY_MIN_MARGIN = 5
        private const val MAX_LYRIC_CANDIDATES_PER_QUERY = 4
        private val PLACEHOLDER_LYRICS = listOf(
            Regex("^(?:此|该)?歌曲(?:为)?(?:一首)?(?:没有填词的|无歌词的)?纯音乐请您?欣赏$"),
            Regex("^纯音乐请您?欣赏$"),
            Regex("^(?:暂无|暂未匹配到|没有|无)歌词$"),
            Regex("^歌词暂无$")
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
