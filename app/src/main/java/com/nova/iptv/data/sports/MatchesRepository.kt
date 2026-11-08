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

data class MatchTeam(val name: String, val short: String, val logo: String, val score: String)

data class Match(
    val id: String,
    val league: String,
    val leagueLogo: String,
    val priority: Int,
    val home: MatchTeam,
    val away: MatchTeam,
    val startMs: Long,
    val state: MatchState,
    /** Minute shown while live, e.g. "67'" or "45'+2'". */
    val clock: String,
    val broadcasters: List<String>,
)

/**
 * Today's / tomorrow's matches in major competitions from ESPN's public scoreboard JSON
 * (no key; undocumented but widely used). Only league slugs and dates are sent.
 */
@Singleton
class MatchesRepository @Inject constructor(private val fetcher: HttpFetcher) {

    private data class League(val slug: String, val name: String, val priority: Int)

    private val leagues = listOf(
        League("uefa.champions", "UEFA Champions League", 0),
        League("ksa.1", "Saudi Pro League", 1),
        League("eng.1", "Premier League", 2),
        League("esp.1", "LaLiga", 3),
        League("ita.1", "Serie A", 4),
        League("ger.1", "Bundesliga", 5),
        League("fra.1", "Ligue 1", 6),
        League("uefa.europa", "UEFA Europa League", 7),
        League("afc.champions", "AFC Champions League Elite", 8),
        League("fifa.worldq.afc", "World Cup Qualifiers (AFC)", 9),
    )

    private val cache = HashMap<String, Pair<Long, List<Match>>>()

    /** Matches kicking off on the local day [dayOffset] (0 = today, 1 = tomorrow), most important first. */
    suspend fun matchesFor(dayOffset: Int, force: Boolean = false): List<Match> = coroutineScope {
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
        all.sortedWith(
            compareBy<Match>(
                { when (it.state) { MatchState.LIVE, MatchState.HALFTIME -> 0; MatchState.PRE -> 1; MatchState.POST -> 2; MatchState.POSTPONED -> 3 } },
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
            name = t?.str("displayName").orEmpty(),
            short = t?.str("shortDisplayName")?.ifBlank { null } ?: t?.str("abbreviation").orEmpty(),
            logo = logo,
            score = if (state == MatchState.PRE || state == MatchState.POSTPONED) "" else o.str("score"),
        )
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
