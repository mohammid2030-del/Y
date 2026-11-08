package com.nova.iptv.data.sports

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nova.iptv.data.network.HttpFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

enum class MatchState { PRE, LIVE, HALFTIME, POST, POSTPONED }

data class MatchTeam(
    val id: String,
    val name: String,
    val short: String,
    val logo: String,
    val score: String,
    /** Club colours as ARGB (0 when unknown). */
    val color: Long = 0,
    val altColor: Long = 0,
)

data class Match(
    val id: String,
    val league: String,
    val leagueSlug: String,
    val leagueLogo: String,
    val priority: Int,
    val home: MatchTeam,
    val away: MatchTeam,
    val startMs: Long,
    val state: MatchState,
    /** Minute shown while live, e.g. "67'" or "45'+2'". */
    val clock: String,
    val broadcasters: List<String>,
    /** Involves one of the user's favourite teams. */
    val favorite: Boolean = false,
)

/** A competition the user can follow. */
data class Competition(val slug: String, val en: String, val ar: String, val priority: Int, val national: Boolean = false)

/** A team the user can follow (club or national team) — ESPN team id. */
data class FavTeam(val id: String, val name: String, val logo: String, val national: Boolean, val slug: String = "")

val ALL_COMPETITIONS = listOf(
    Competition("uefa.champions", "UEFA Champions League", "دوري أبطال أوروبا", 0),
    Competition("ksa.1", "Saudi Pro League", "دوري روشن السعودي", 1),
    Competition("eng.1", "Premier League", "الدوري الإنجليزي", 2),
    Competition("esp.1", "LaLiga", "الدوري الإسباني", 3),
    Competition("ita.1", "Serie A", "الدوري الإيطالي", 4),
    Competition("ger.1", "Bundesliga", "الدوري الألماني", 5),
    Competition("fra.1", "Ligue 1", "الدوري الفرنسي", 6),
    Competition("uefa.europa", "UEFA Europa League", "الدوري الأوروبي", 7),
    Competition("afc.champions", "AFC Champions League Elite", "دوري أبطال آسيا للنخبة", 8),
    Competition("uefa.europa.conf", "Conference League", "دوري المؤتمر الأوروبي", 10),
    Competition("eng.fa", "FA Cup", "كأس الاتحاد الإنجليزي", 11),
    Competition("esp.copa_del_rey", "Copa del Rey", "كأس ملك إسبانيا", 12),
    Competition("ned.1", "Eredivisie", "الدوري الهولندي", 13),
    Competition("por.1", "Liga Portugal", "الدوري البرتغالي", 14),
    Competition("tur.1", "Süper Lig", "الدوري التركي", 15),
    Competition("usa.1", "MLS", "الدوري الأمريكي", 16),
    Competition("fifa.world", "FIFA World Cup", "كأس العالم", 0, national = true),
    Competition("fifa.worldq.afc", "World Cup Qualifiers — Asia", "تصفيات كأس العالم — آسيا", 9, national = true),
    Competition("afc.asian.cup", "AFC Asian Cup", "كأس آسيا", 9, national = true),
    Competition("uefa.nations", "UEFA Nations League", "دوري الأمم الأوروبية", 12, national = true),
    Competition("fifa.worldq.uefa", "World Cup Qualifiers — Europe", "تصفيات كأس العالم — أوروبا", 13, national = true),
    Competition("caf.nations", "Africa Cup of Nations", "كأس أمم أفريقيا", 13, national = true),
    Competition("fifa.friendly", "International Friendlies", "مباريات ودية دولية", 20, national = true),
)

val DEFAULT_COMPETITIONS = setOf(
    "uefa.champions", "ksa.1", "eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "uefa.europa", "afc.champions", "fifa.worldq.afc",
)

/** Club continental competitions fetched so favourite clubs' matches appear wherever they play. */
private val CONTINENTAL = setOf("uefa.champions", "uefa.europa", "uefa.europa.conf", "afc.champions")

/**
 * Today's / tomorrow's matches in major competitions from ESPN's public scoreboard JSON
 * (no key; undocumented but widely used). Only league slugs and dates are sent.
 */
@Singleton
class MatchesRepository @Inject constructor(private val fetcher: HttpFetcher) {

    private data class League(val slug: String, val name: String, val priority: Int)

    private fun leagueOf(slug: String): League =
        ALL_COMPETITIONS.firstOrNull { it.slug == slug }?.let { League(it.slug, it.en, it.priority) } ?: League(slug, slug, 30)

    private val cache = HashMap<String, Pair<Long, List<Match>>>()

    /** Matches kicking off on the local day [dayOffset] (0 = today, 1 = tomorrow), most important first. */
    suspend fun matchesFor(
        dayOffset: Int,
        followed: Set<String> = DEFAULT_COMPETITIONS,
        favorites: List<FavTeam> = emptyList(),
        onlyFavorites: Boolean = false,
        force: Boolean = false,
    ): List<Match> = coroutineScope {
        val favIds = favorites.map { it.id }.toSet()
        val slugs = LinkedHashSet<String>()
        if (!onlyFavorites || favorites.isEmpty()) slugs += followed
        slugs += favorites.map { it.slug }.filter { it.isNotBlank() }
        if (favorites.any { !it.national }) slugs += CONTINENTAL
        if (favorites.any { it.national }) slugs += ALL_COMPETITIONS.filter { it.national }.map { it.slug }
        val leagues = slugs.map { leagueOf(it) }
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }.timeInMillis
        val end = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        // ESPN groups by US date, so look one day either side and filter locally.
        val dates = listOf(-1, 0, 1).map { d -> fmt.format(Calendar.getInstance().apply { timeInMillis = start + 12 * 3600_000L; add(Calendar.DAY_OF_YEAR, d) }.time) }
        val all = leagues.flatMap { lg -> dates.map { d -> lg to d } }
            .map { (lg, d) -> async { runCatching { fetchLeague(lg, d, force) }.getOrDefault(emptyList()) } }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
            .filter { it.startMs in start until end }
            .map { m -> if (m.home.id in favIds || m.away.id in favIds) m.copy(favorite = true) else m }
            .filter { m ->
                when {
                    onlyFavorites && favorites.isNotEmpty() -> m.favorite
                    else -> m.favorite || m.leagueSlug in followed
                }
            }
        all.sortedWith(
            compareBy<Match>(
                { when (it.state) { MatchState.LIVE, MatchState.HALFTIME -> 0; MatchState.PRE -> 1; MatchState.POST -> 2; MatchState.POSTPONED -> 3 } },
                { if (it.favorite) 0 else 1 },
                { it.priority },
                { it.startMs },
            )
        )
    }

    private suspend fun fetchLeague(lg: League, date: String, force: Boolean): List<Match> {
        val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/${lg.slug}/scoreboard?dates=$date"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[url]?.let { (t, v) -> if (!force && now - t < if (v.any { it.isLive }) 45_000 else 600_000) return v }
        }
        val root = JsonParser.parseString(fetcher.string(url, USER_AGENT)).asJsonObject
        val leagueObj = root.arr("leagues").firstOrNull()?.obj()
        val leagueName = leagueObj?.str("name")?.ifBlank { null } ?: lg.name
        val leagueLogo = leagueObj?.arr("logos")?.firstOrNull()?.obj()?.str("href").orEmpty()
        val list = root.arr("events").mapNotNull { it.obj()?.let { e -> parseEvent(e, lg, leagueName, leagueLogo) } }
        synchronized(cache) { cache[url] = now to list }
        return list
    }

    private fun parseEvent(e: JsonObject, lg: League, leagueName: String, leagueLogo: String): Match? {
        val comp = e.arr("competitions").firstOrNull()?.obj() ?: return null
        val teams = comp.arr("competitors").mapNotNull { it.obj() }
        val home = teams.firstOrNull { it.str("homeAway") == "home" } ?: teams.getOrNull(0) ?: return null
        val away = teams.firstOrNull { it.str("homeAway") == "away" } ?: teams.getOrNull(1) ?: return null
        val status = (comp.get("status") ?: e.get("status"))?.obj()
        val type = status?.get("type")?.obj()
        val stateRaw = type?.str("state").orEmpty()
        val typeName = type?.str("name").orEmpty()
        val state = when {
            typeName.contains("POSTPONED") || typeName.contains("CANCELED") -> MatchState.POSTPONED
            typeName.contains("HALFTIME") -> MatchState.HALFTIME
            stateRaw == "in" -> MatchState.LIVE
            stateRaw == "post" -> MatchState.POST
            else -> MatchState.PRE
        }
        val clock = status?.str("displayClock").orEmpty().ifBlank { type?.str("shortDetail").orEmpty() }
        val names = LinkedHashSet<String>()
        comp.arr("broadcasts").forEach { b -> b.obj()?.arr("names")?.forEach { n -> n.asStringOrNull()?.let { names += it } } }
        comp.arr("geoBroadcasts").forEach { g -> g.obj()?.get("media")?.obj()?.str("shortName")?.takeIf { it.isNotBlank() }?.let { names += it } }
        return Match(
            id = e.str("id").ifBlank { e.str("uid") },
            league = leagueName,
            leagueSlug = lg.slug,
            leagueLogo = leagueLogo,
            priority = lg.priority,
            home = team(home, state),
            away = team(away, state),
            startMs = parseDate(e.str("date").ifBlank { comp.str("date") }),
            state = state,
            clock = clock,
            broadcasters = names.toList().take(3),
        )
    }

    private fun team(o: JsonObject, state: MatchState): MatchTeam {
        val t = o.get("team")?.obj()
        val logo = t?.str("logo")?.ifBlank { null } ?: t?.arr("logos")?.firstOrNull()?.obj()?.str("href").orEmpty()
        return MatchTeam(
            id = t?.str("id").orEmpty(),
            name = t?.str("displayName").orEmpty(),
            short = t?.str("shortDisplayName")?.ifBlank { null } ?: t?.str("abbreviation").orEmpty(),
            logo = logo,
            score = if (state == MatchState.PRE || state == MatchState.POSTPONED) "" else o.str("score"),
            color = hex(t?.str("color").orEmpty()),
            altColor = hex(t?.str("alternateColor").orEmpty()),
        )
    }

    private val teamsCache = HashMap<String, List<FavTeam>>()

    /** Teams of one competition (for the "my teams" picker). */
    suspend fun teams(slug: String): List<FavTeam> {
        synchronized(teamsCache) { teamsCache[slug]?.let { return it } }
        val national = ALL_COMPETITIONS.firstOrNull { it.slug == slug }?.national == true
        val root = JsonParser.parseString(
            fetcher.string("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/teams", USER_AGENT)
        ).asJsonObject
        val list = root.arr("sports").firstOrNull()?.obj()?.arr("leagues")?.firstOrNull()?.obj()?.arr("teams")
            ?.mapNotNull { it.obj()?.get("team")?.obj() }
            ?.map { t ->
                FavTeam(
                    id = t.str("id"),
                    name = t.str("displayName"),
                    logo = t.arr("logos").firstOrNull()?.obj()?.str("href").orEmpty(),
                    national = national,
                    slug = slug,
                )
            }
            ?.filter { it.id.isNotBlank() }
            ?.sortedBy { it.name }
            .orEmpty()
        if (list.isNotEmpty()) synchronized(teamsCache) { teamsCache[slug] = list }
        return list
    }

    private fun hex(s: String): Long {
        val h = s.trim().removePrefix("#")
        if (h.length != 6) return 0
        return h.toLongOrNull(16)?.let { 0xFF000000L or it } ?: 0
    }

    private fun parseDate(s: String): Long {
        if (s.isBlank()) return 0
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            runCatching {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(s)!!.time
            }
        }
        return 0
    }

    companion object {
        private const val USER_AGENT = "NOVA-IPTV/1.0 (Android)"
    }
}

val Match.isLive: Boolean get() = state == MatchState.LIVE || state == MatchState.HALFTIME

private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
private fun JsonElement.asStringOrNull(): String? = if (isJsonPrimitive) asString else null
private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
private fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
