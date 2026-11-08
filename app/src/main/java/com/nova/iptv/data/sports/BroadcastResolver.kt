package com.nova.iptv.data.sports

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.nova.iptv.data.db.ContentEntity
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.repo.ContentRepository
import com.nova.iptv.data.repo.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A channel from the user's own subscription that may carry the match, best first. */
data class ChannelPick(val channel: ContentEntity, val score: Int, val nowTitle: String, val epgMatch: Boolean)

data class Resolution(val picks: List<ChannelPick>, val autoPlay: ChannelPick?)

/**
 * Finds the channel for a match inside the user's subscription:
 *  - which network holds the regional rights for the competition (ranking hint, Arabic region),
 *  - the app language (Arabic feeds first for Arabic users, English feeds for English users; other languages pushed down),
 *  - the subscription's guide (a programme around kick-off naming one of the teams is a near-certain match),
 *  - what the user picked before for that competition (remembered channel family).
 */
@Singleton
class BroadcastResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val content: ContentRepository,
    private val settings: SettingsRepository,
) {
    private val prefs = context.getSharedPreferences("nova_match_channels", Context.MODE_PRIVATE)

    /** Regional (MENA) rights holders per competition, as search keywords, strongest first. */
    private val networkHints: Map<String, List<String>> = mapOf(
        "uefa.champions" to listOf("bein", "بي ان", "beln"),
        "uefa.europa" to listOf("bein", "بي ان"),
        "eng.1" to listOf("bein", "بي ان"),
        "esp.1" to listOf("bein", "بي ان"),
        "fra.1" to listOf("bein", "بي ان"),
        "ger.1" to listOf("mbc action", "ام بي سي اكشن", "shahid", "mbc"),
        "ita.1" to listOf("starz", "ad sport", "abu dhabi", "ابوظبي", "shasha"),
        "ksa.1" to listOf("thmanyah", "ثمانية", "ssc"),
        "afc.champions" to listOf("thmanyah", "ثمانية", "ssc", "alkass", "الكاس"),
        "fifa.worldq.afc" to listOf("ssc", "ksa sport", "السعودية الرياضية", "alkass", "bein"),
    )

    /** Short label shown on the card for the regional network. */
    fun networkLabel(slug: String, arabic: Boolean): String = when (slug) {
        "uefa.champions", "uefa.europa", "eng.1", "esp.1", "fra.1" -> "beIN SPORTS"
        "ger.1" -> if (arabic) "MBC أكشن · شاهد" else "MBC Action · Shahid"
        "ksa.1" -> if (arabic) "ثمانية" else "Thmanyah"
        else -> ""
    }

    fun isArabicUi(): Boolean {
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')
        return tag.substringBefore('-').ifBlank { Locale.getDefault().language } == "ar"
    }

    suspend fun resolve(m: Match): Resolution {
        val subId = settings.current().activeSubscriptionId
        if (subId == 0L) return Resolution(emptyList(), null)
        val arabic = isArabicUi()
        val hints = networkHints[m.leagueSlug].orEmpty()

        // Candidates: channels named after the rights holder, plus anything in a sports category.
        val bag = LinkedHashMap<String, ContentEntity>()
        for (h in hints) content.search(subId, h, ContentType.LIVE, "").take(80).forEach { bag.putIfAbsent(it.remoteId, it) }
        for (q in listOf("sport", "رياض")) content.search(subId, q, ContentType.LIVE, "").take(120).forEach { bag.putIfAbsent(it.remoteId, it) }
        if (bag.isEmpty()) return Resolution(emptyList(), null)

        val categoryNames = runCatching {
            content.categories(subId, ContentType.LIVE).first().associate { it.remoteId to it.name }
        }.getOrDefault(emptyMap())

        // Guide around kick-off.
        val offset = settings.current().epgOffsetMinutes * 60_000L
        val from = m.startMs - 20 * 60_000L - offset
        val to = m.startMs + 60 * 60_000L - offset
        val keys = bag.values.map { it.epgId }.filter { it.isNotEmpty() }.distinct()
        val guide = runCatching { content.programmesFor(subId, keys, from, to) }.getOrDefault(emptyMap())
        val teamTokens = (teamTokens(m.home) + teamTokens(m.away)).distinct()

        val learnedFamily = prefs.getString("$subId|${m.leagueSlug}", null)
        val learnedForMatch = prefs.getString("$subId|match|${m.id}", null)

        // Sports channels only: "beIN BOX OFFICE", "MBC 2" or a movies channel is never the match channel.
        val sportsRe = Regex("sport|سبورت|رياض|ssc|thmanyah|ثمانية|alkass|al kass|الكاس|action|اكشن|أكشن", RegexOption.IGNORE_CASE)
        val notSportsRe = Regex("box ?office|movie|cinema|سينما|افلام|أفلام|film|drama|دراما|kids|اطفال|أطفال|junior|jeem|news|اخبار|أخبار|series|مسلسل|documentar|وثائقي|gourmet|nat ?geo|discovery|music|موسيقى|quran|قرآن|comedy|كوميدي", RegexOption.IGNORE_CASE)
        val ranked = bag.values.filter { ch ->
            val n = ch.name.lowercase(Locale.ROOT)
            (sportsRe.containsMatchIn(n) || sportsRe.containsMatchIn(categoryNames[ch.categoryId].orEmpty())) && !notSportsRe.containsMatchIn(n)
        }.map { ch ->
            val text = (ch.name + " " + categoryNames[ch.categoryId].orEmpty()).lowercase(Locale.ROOT)
            var score = 0
            hints.forEachIndexed { i, h -> if (text.contains(h.lowercase(Locale.ROOT))) score = maxOf(score, 60 - i * 8) }
            score += languageScore(text, arabic)
            score += when {
                Regex("""\b(fhd|uhd|4k)\b""").containsMatchIn(text) -> 6
                Regex("""\bhd\b""").containsMatchIn(text) -> 4
                Regex("""\bsd\b""").containsMatchIn(text) -> -6
                else -> 0
            }
            if (Regex("""\b(backup|bk|test|\+1)\b""").containsMatchIn(text)) score -= 15
            val progs = guide[ch.epgId].orEmpty()
            val hit = progs.firstOrNull { p ->
                val t = (p.title + " " + p.description).lowercase(Locale.ROOT)
                teamTokens.count { t.contains(it) } >= 1
            }
            if (hit != null) score += 150
            if (learnedFamily != null && family(ch.name) == learnedFamily) score += 80
            if (learnedForMatch == ch.remoteId) score += 500
            val now = progs.firstOrNull { it.start <= System.currentTimeMillis() - offset && it.stop > System.currentTimeMillis() - offset }
            ChannelPick(ch, score, (hit ?: now)?.title.orEmpty(), hit != null)
        }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
        // Like the Apple / Samsung apps: only channels whose guide shows the match, or the competition's official
        // broadcaster (or the one you picked before). Anything else only when nothing is confirmed.
        val confirmed = ranked.filter { p ->
            p.epgMatch || p.channel.remoteId == learnedForMatch ||
                hints.any { h -> (p.channel.name + " " + categoryNames[p.channel.categoryId].orEmpty()).lowercase(Locale.ROOT).contains(h.lowercase(Locale.ROOT)) }
        }
        val picks = confirmed.ifEmpty { ranked }.take(12)

        val top = picks.firstOrNull()
        val auto = top?.takeIf { it.score >= 500 || (it.epgMatch && (picks.getOrNull(1)?.epgMatch != true || it.score - picks[1].score >= 40)) }
        return Resolution(picks, auto)
    }

    /** Remembers the user's choice for this match and the channel family for the competition. */
    fun remember(m: Match, subId: Long, ch: ContentEntity) {
        prefs.edit()
            .putString("$subId|match|${m.id}", ch.remoteId)
            .putString("$subId|${m.leagueSlug}", family(ch.name))
            .apply()
    }

    private fun languageScore(text: String, arabic: Boolean): Int {
        val isAr = Regex("""(^|[^a-z])(ar|arab|arabic)([^a-z]|$)""").containsMatchIn(text) || text.any { it in '؀'..'ۿ' }
        val isEn = Regex("""(^|[^a-z])(en|eng|english|uk)([^a-z]|$)""").containsMatchIn(text)
        val other = Regex("""(^|[^a-z])(es|esp|spain|spanish|fr|french|tr|turk|de|ger|it|ita|pt|por|in|hindi|ru|pl|nl|us|usa)([^a-z]|$)""").containsMatchIn(text)
        return when {
            arabic && isAr -> 40
            !arabic && isEn -> 40
            arabic && isEn -> -20
            !arabic && isAr -> -10
            other -> -80
            else -> 0
        }
    }

    private fun teamTokens(t: MatchTeam): List<String> =
        listOf(t.name, t.short).flatMap { it.lowercase(Locale.ROOT).split(' ', '-', '.') }
            .filter { it.length >= 4 && it !in setOf("real", "club", "city", "united", "sporting", "athletic", "football") }

    /** "AR: beIN Sports 3 HD" → "ar: bein sports # hd" so sibling channels share a family. */
    private fun family(name: String): String = name.lowercase(Locale.ROOT).replace(Regex("""\d+"""), "#").trim()
}
