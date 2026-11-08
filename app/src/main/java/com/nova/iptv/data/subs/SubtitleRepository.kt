package com.nova.iptv.data.subs

import android.content.Context
import com.nova.iptv.data.ErrorKind
import com.nova.iptv.data.NovaException
import com.nova.iptv.data.security.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SubtitleConfig(
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val autoArabic: Boolean = true,
    val translate: Boolean = true,
    val size: Int = 1,
)

/** What to look for: a movie (tmdb id) or a TV episode (show tmdb id + season + episode). */
data class SubtitleTarget(val tmdbId: String, val season: Int = 0, val episode: Int = 0) {
    val isEpisode get() = season > 0 && episode > 0
}

enum class SubStep { SEARCHING, DOWNLOADING, TRANSLATING, ADDED, ADDED_TRANSLATED, NOT_FOUND, NO_TMDB, NO_KEY, LIMIT, AUTH, FAILED }

data class SubStatus(val step: SubStep, val progress: Int = 0, val fromLang: String = "")

@Singleton
class SubtitleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
    private val client: OpenSubtitlesClient,
) {
    private val prefs = context.getSharedPreferences("nova_subtitles", Context.MODE_PRIVATE)
    private val dir: File get() = File(context.filesDir, "subtitles").apply { mkdirs() }
    private var token: String? = null
    private var host: String = ""
    private var tokenAt = 0L

    fun config(): SubtitleConfig = SubtitleConfig(
        apiKey = crypto.decrypt(prefs.getString("key", "").orEmpty()),
        username = prefs.getString("user", "").orEmpty(),
        password = crypto.decrypt(prefs.getString("pass", "").orEmpty()),
        autoArabic = prefs.getBoolean("auto", true),
        translate = prefs.getBoolean("translate", true),
        size = prefs.getInt("size", 1),
    )

    fun save(c: SubtitleConfig) {
        prefs.edit()
            .putString("key", crypto.encrypt(c.apiKey.trim()))
            .putString("user", c.username.trim())
            .putString("pass", crypto.encrypt(c.password))
            .putBoolean("auto", c.autoArabic)
            .putBoolean("translate", c.translate)
            .putInt("size", c.size)
            .apply()
        token = null
    }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".ar.srt")

    fun cached(key: String): File? = file(key).takeIf { it.exists() && it.length() > 20 }

    fun clearCache() { dir.listFiles()?.forEach { it.delete() } }

    // ---- Manual linking (provider has no / wrong TMDB id, e.g. Arabic titles) ----
    fun linkedTmdb(subId: Long, kind: String, remoteId: String): String = prefs.getString("map|$subId|$kind|$remoteId", "").orEmpty()

    fun link(subId: Long, kind: String, remoteId: String, tmdbId: String) {
        prefs.edit().putString("map|$subId|$kind|$remoteId", tmdbId).apply()
    }

    suspend fun searchTitles(query: String, tv: Boolean): List<FeatureResult> {
        val c = config()
        if (c.apiKey.isBlank() || query.isBlank()) return emptyList()
        return runCatching { client.features(c.apiKey, query, tv) }.getOrDefault(emptyList())
    }

    private suspend fun tokenFor(c: SubtitleConfig): String? {
        if (c.username.isBlank() || c.password.isBlank()) return null
        if (token != null && System.currentTimeMillis() - tokenAt < 20 * 3_600_000L) return token
        val (t, h) = client.login(c.apiKey, c.username, c.password)
        token = t; host = h; tokenAt = System.currentTimeMillis()
        return t
    }

    /**
     * Finds Arabic subtitles for [target]; if none exist, translates Turkish (or English) ones to Arabic on device.
     * The result is cached under [key] so later plays load instantly.
     */
    suspend fun fetchArabic(key: String, target: SubtitleTarget, onStatus: (SubStatus) -> Unit): File? = withContext(Dispatchers.IO) {
        cached(key)?.let { return@withContext it }
        val c = config()
        if (c.apiKey.isBlank()) { onStatus(SubStatus(SubStep.NO_KEY)); return@withContext null }
        if (target.tmdbId.isBlank()) { onStatus(SubStatus(SubStep.NO_TMDB)); return@withContext null }
        try {
            val tk = runCatching { tokenFor(c) }.getOrNull()
            val params = if (target.isEpisode) mapOf(
                "parent_tmdb_id" to target.tmdbId,
                "season_number" to target.season.toString(),
                "episode_number" to target.episode.toString(),
            ) else mapOf("tmdb_id" to target.tmdbId)

            onStatus(SubStatus(SubStep.SEARCHING))
            val arabic = client.search(c.apiKey, tk, params, "ar").firstOrNull()
            if (arabic != null) {
                onStatus(SubStatus(SubStep.DOWNLOADING))
                val srt = client.download(c.apiKey, tk, host, arabic.fileId)
                val cues = Srt.parse(srt)
                if (cues.isNotEmpty()) {
                    val f = file(key); f.writeText(Srt.write(cues))
                    onStatus(SubStatus(SubStep.ADDED)); return@withContext f
                }
            }
            if (!c.translate) { onStatus(SubStatus(SubStep.NOT_FOUND)); return@withContext null }

            // Translate from Turkish first, then English, then any other language ML Kit can translate
            val source = client.search(c.apiKey, tk, params, "en,tr")
                .sortedBy { if (it.language.startsWith("tr")) 0 else 1 }
                .firstOrNull()
                ?: client.search(c.apiKey, tk, params, ANY_LANGUAGES)
                    .firstOrNull { com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(it.language.take(2)) != null }
            if (source == null) { onStatus(SubStatus(SubStep.NOT_FOUND)); return@withContext null }
            onStatus(SubStatus(SubStep.DOWNLOADING, fromLang = source.language))
            val cues = Srt.parse(client.download(c.apiKey, tk, host, source.fileId))
            if (cues.isEmpty()) { onStatus(SubStatus(SubStep.NOT_FOUND)); return@withContext null }
            val translated = SubtitleTranslator.toArabic(cues, source.language) { p ->
                onStatus(SubStatus(SubStep.TRANSLATING, p, source.language))
            }
            val f = file(key); f.writeText(Srt.write(translated))
            onStatus(SubStatus(SubStep.ADDED_TRANSLATED, fromLang = source.language))
            f
        } catch (e: NovaException) {
            onStatus(
                SubStatus(
                    when {
                        e.kind == ErrorKind.AUTH_FAILED -> SubStep.AUTH
                        e.httpCode == 406 || e.httpCode == 429 -> SubStep.LIMIT
                        else -> SubStep.FAILED
                    }
                )
            )
            null
        } catch (e: Exception) {
            onStatus(SubStatus(SubStep.FAILED))
            null
        }
    }

    companion object {
        private const val ANY_LANGUAGES = "es,fr,de,it,pt-br,pt-pt,fa,el,ru,ro,bg,hu,pl,nl,id,ms,ur,he,bs,hr,sr,sq,az"
    }
}
