package com.nova.iptv.data.library

import com.nova.iptv.data.db.CategoryEntity
import com.nova.iptv.data.db.ContentDao
import com.nova.iptv.data.db.ContentType
import com.nova.iptv.data.db.LibItem
import com.nova.iptv.data.db.SubscriptionDao
import com.nova.iptv.data.tmdb.TmdbRepository
import com.nova.iptv.ui.components.cleanFull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A Netflix-style group of provider categories: a platform (Netflix, Shahid…), a region (Turkish…) or a type (Kids…). */
data class Shelf(val id: String, val title: String, val categories: List<CategoryEntity>, val count: Int)

/** One copy of a title (4K / HD / dubbed / subtitled…) for the version chooser. */
data class Version(val item: LibItem, val language: String, val rank: Int, val quality: Int, val category: String)

/** Genre filters backed by TMDB genre ids (movie and TV lists differ) plus words in category names. Same as Apple / Samsung. */
enum class Genre(private val movie: Set<Int>, private val tv: Set<Int>, pattern: String) {
    ACTION(setOf(28), setOf(10759), "action|اكشن|أكشن"),
    DRAMA(setOf(18), setOf(18), "drama|دراما"),
    COMEDY(setOf(35), setOf(35), "comedy|كوميدي|كوميديا"),
    ROMANCE(setOf(10749), setOf(10749), "romance|romantic|رومانس|رومنس|رومانسي"),
    THRILLER(setOf(53), setOf(9648, 80), "thriller|اثارة|إثارة|تشويق"),
    HORROR(setOf(27), setOf(27), "horror|رعب"),
    CRIME(setOf(80), setOf(80), "crime|جريمة|جرائم"),
    SCIFI(setOf(878, 14), setOf(10765), "sci-?fi|science|خيال علمي"),
    ADVENTURE(setOf(12), setOf(10759), "adventure|مغامرة|مغامرات"),
    ANIMATION(setOf(16), setOf(16), "animation|cartoon|anime|انمي|أنمي|كرتون|رسوم"),
    FAMILY(setOf(10751), setOf(10751, 10762), "family|عائلي|عائلية"),
    MYSTERY(setOf(9648), setOf(9648), "mystery|غموض"),
    WAR(setOf(10752), setOf(10768), "war|حرب|حروب"),
    DOCUMENTARY(setOf(99), setOf(99), "docu|وثائقي");

    private val re = Regex(pattern, RegexOption.IGNORE_CASE)
    fun ids(series: Boolean) = if (series) tv else movie
    fun matchesCategory(name: String) = re.containsMatchIn(name)

    companion object {
        fun available(series: Boolean): List<Genre> =
            if (series) listOf(DRAMA, COMEDY, ACTION, CRIME, THRILLER, SCIFI, ANIMATION, FAMILY, MYSTERY, WAR, DOCUMENTARY)
            else listOf(ACTION, DRAMA, COMEDY, ROMANCE, THRILLER, HORROR, CRIME, SCIFI, ADVENTURE, ANIMATION, FAMILY, WAR, DOCUMENTARY)
    }
}

/**
 * Movies / series library: shelves by platform and region, one card per title (the best copy), every copy for the
 * version chooser. Built once per sync, off the main thread. Rules match the Apple and Samsung apps.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val contentDao: ContentDao,
    private val subDao: SubscriptionDao,
    private val tmdb: TmdbRepository,
) {
    class Index(
        val type: String,
        val items: List<LibItem>,
        val byId: Map<Long, LibItem>,
        val categories: Map<String, CategoryEntity>,
        val shelves: List<Shelf>,
        val shelfOfCategory: Map<String, String>,
        val key: Map<Long, String>,
        val variants: Map<String, List<LibItem>>,
        val quality: Map<Long, Int>,
        val clean: Map<Long, Pair<String, String>>,
        val titleTmdb: Map<String, String>,
        val tmdbCount: Map<String, Int>,
    ) {
        @Volatile var groupsVersion = -1
        @Volatile var keyOf: Map<Long, String> = emptyMap()
        @Volatile var groups: Map<String, List<LibItem>> = emptyMap()
    }

    private val lock = Mutex()
    private val cache = HashMap<String, Pair<String, Index>>()

    suspend fun index(subId: Long, type: String): Index = lock.withLock {
        val stamp = "${subDao.get(subId)?.lastSync ?: 0L}|${contentDao.hiddenCategoryIds(subId, type).sorted().hashCode()}"
        cache["$subId|$type"]?.takeIf { it.first == stamp }?.let { return it.second }
        val idx = withContext(Dispatchers.Default) {
            build(type, contentDao.libraryItems(subId, type), contentDao.visibleCategories(subId, type))
        }
        cache["$subId|$type"] = stamp to idx
        idx
    }

    fun invalidate() { cache.clear() }

    /** Rebuilt after every sync of the subscription. */
    fun indexFlow(subId: Long, type: String): kotlinx.coroutines.flow.Flow<Index> = subDao.observeAll()
        .map { l -> l.firstOrNull { it.id == subId }?.lastSync ?: 0L }
        .distinctUntilChanged()
        .map { index(subId, type) }

    private fun series(idx: Index) = idx.type == ContentType.SERIES

    /** Group key of every title, worked out once (and again only when TMDB identified more titles). */
    private fun ensureGroups(idx: Index) {
        val v = tmdb.idsVersion
        if (idx.groupsVersion == v) return
        synchronized(idx) {
            if (idx.groupsVersion == v) return
            val keys = HashMap<Long, String>(idx.items.size * 2)
            val groups = HashMap<String, MutableList<LibItem>>()
            for (m in idx.items) {
                val k = rawGroupKey(idx, m)
                keys[m.id] = k
                groups.getOrPut(k) { mutableListOf() } += m
            }
            idx.keyOf = keys; idx.groups = groups; idx.groupsVersion = v
        }
    }

    fun groupKey(idx: Index, m: LibItem): String {
        if (idx.groupsVersion == tmdb.idsVersion) idx.keyOf[m.id]?.let { return it }
        return rawGroupKey(idx, m)
    }

    /** Same title everywhere: provider TMDB id, a sibling copy's id, or an id TMDB gave us; otherwise the cleaned title + year. */
    private fun rawGroupKey(idx: Index, m: LibItem): String {
        if (m.tmdb.isNotEmpty()) return "tmdb:${m.tmdb}"
        val tk = idx.key[m.id] ?: m.id.toString()
        idx.titleTmdb[tk]?.let { return "tmdb:$it" }
        val c = idx.clean[m.id]
        if (c != null) tmdb.knownId(series(idx), m.name, m.year)?.let { return "tmdb:$it" }
        return tk
    }

    /** Titles of a shelf (or one of its categories), one card per title — best copy (4K > FHD > HD), newest first. */
    fun browse(idx: Index, shelf: String, category: String?): List<LibItem> {
        val source = if (category != null) idx.items.filter { it.categoryId == category }
        else idx.items.filter { idx.shelfOfCategory[it.categoryId] == shelf }
        return collapse(idx, source)
    }

    fun collapse(idx: Index, source: List<LibItem>): List<LibItem> {
        ensureGroups(idx)
        val best = LinkedHashMap<String, LibItem>()
        for (m in source) {
            val k = groupKey(idx, m)
            val cur = best[k]
            if (cur == null) best[k] = m
            else {
                val a = idx.quality[m.id] ?: 0; val b = idx.quality[cur.id] ?: 0
                if (a > b || (a == b && m.added > cur.added)) best[k] = m
            }
        }
        return best.values.sortedByDescending { it.added }
    }

    /** Every copy of the same title, Arabic first (dubbed, subtitled, Arabic), then best quality. */
    fun versions(idx: Index, item: LibItem): List<Version> {
        ensureGroups(idx)
        val g = groupKey(idx, item)
        val list = idx.groups[g] ?: idx.variants[g] ?: listOf(item)
        return list.ifEmpty { listOf(item) }.map { m ->
            val cat = idx.categories[m.categoryId]?.name.orEmpty()
            val (label, rank) = language(m.name, cat)
            Version(m, label, rank, idx.quality[m.id] ?: 0, cat)
        }.sortedWith(compareBy<Version> { it.rank }.thenByDescending { it.quality }.thenByDescending { it.item.added })
    }

    suspend fun versionsOf(subId: Long, type: String, contentId: Long): List<Version> {
        val idx = index(subId, type)
        val item = idx.byId[contentId] ?: return emptyList()
        return withContext(Dispatchers.Default) { versions(idx, item) }
    }

    /** Asks TMDB about titles it hasn't seen yet (cached), so copies merge and genre filters work. */
    suspend fun identify(idx: Index, list: List<LibItem>, max: Int = 240) {
        if (!tmdb.enabled.value) return
        val tv = series(idx)
        list.asSequence().filter { it.tmdb.isEmpty() && tmdb.cachedPoster(tv, it.name, it.year) == null }.take(max).toList()
            .chunked(12).forEach { chunk ->
                coroutineScope {
                    chunk.map { m -> async { runCatching { tmdb.poster(tv, m.name, m.year) } } }.forEach { it.await() }
                }
            }
    }

    fun matchesGenre(idx: Index, m: LibItem, g: Genre): Boolean {
        if (g.matchesCategory(idx.categories[m.categoryId]?.name.orEmpty())) return true
        val ids = tmdb.cachedPoster(series(idx), m.name, m.year)?.genres ?: return false
        return ids.any { it in g.ids(series(idx)) }
    }

    fun tmdbVersion() = tmdb.idsVersion

    /** A title a person is known for, if this subscription has it: the content row id. */
    suspend fun find(subId: Long, work: com.nova.iptv.data.tmdb.KnownWork): Long? = withContext(Dispatchers.Default) { findIn(subId, work) }

    private suspend fun findIn(subId: Long, work: com.nova.iptv.data.tmdb.KnownWork): Long? {
        val idx = index(subId, if (work.isTv) ContentType.SERIES else ContentType.MOVIE)
        val tid = work.id.toString()
        idx.items.firstOrNull { it.tmdb == tid }?.let { return best(idx, it).id }
        val names = listOf(work.title, work.original).filter { it.isNotBlank() }.map { normalize(cleanFull(it).first) }.toSet()
        val y = work.year.toIntOrNull()
        val hits = idx.items.filter { m ->
            val c = idx.clean[m.id] ?: return@filter false
            normalize(c.first) in names && (y == null || c.second.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } != false)
        }
        if (hits.isNotEmpty()) return best(idx, hits.first()).id
        // titles TMDB already identified with this id
        ensureGroups(idx)
        return idx.groups["tmdb:${work.id}"]?.firstOrNull()?.let { best(idx, it).id }
    }

    private fun best(idx: Index, m: LibItem): LibItem = versions(idx, m).firstOrNull()?.item ?: m

    /** Copies count for the card badge without scanning the whole library (title-key copies + TMDB-id copies seen). */
    fun versionCountFast(idx: Index, item: LibItem): Int {
        if (idx.groupsVersion == tmdb.idsVersion) idx.groups[groupKey(idx, item)]?.let { return it.size }
        val g = groupKey(idx, item)
        if (!g.startsWith("tmdb:")) return idx.variants[g]?.size ?: 1
        val tk = idx.key[item.id]
        return maxOf(idx.variants[tk]?.size ?: 1, idx.tmdbCount[g.removePrefix("tmdb:")] ?: 1)
    }

    fun categoryName(idx: Index, id: String) = idx.categories[id]?.name.orEmpty()

    companion object {
        private class Rule(val id: String, val ar: String, val en: String, pattern: String) { val re = Regex(pattern) }

        /** Order matters: platforms first ("Netflix Arabic" is Netflix), then regions, then types. */
        private val rules = listOf(
            Rule("netflix", "نتفليكس", "Netflix", "netflix|نتفليكس|نتفلكس|(^|[^a-z])nf([^a-z]|$)"),
            Rule("shahid", "شاهد", "Shahid", "shahid|شاهد"),
            Rule("prime", "أمازون برايم", "Prime Video", "amazon|prime|برايم|امازون|أمازون|(^|[^a-z])amz([^a-z]|$)"),
            Rule("disney", "ديزني+", "Disney+", "disney|ديزني"),
            Rule("hbo", "HBO و Max", "HBO & Max", "hbo|اتش بي او|(^|[^a-z])max([^a-z]|$)"),
            Rule("apple", "Apple TV+", "Apple TV+", "apple|ابل تي|آبل|(^|[^a-z])atv([^a-z]|$)"),
            Rule("starz", "ستارز بلاي", "STARZPLAY", "starz|ستارز"),
            Rule("osn", "OSN+", "OSN+", "(^|[^a-z])osn"),
            Rule("platforms", "منصات أخرى", "More platforms",
                "paramount|hulu|peacock|crunchyroll|(^|[^a-z])tod([^a-z]|$)|yango|watch ?it|wavo|viu|tvplus|tv\\+|jawwy|weyyak|وياك|منصة"),
            Rule("ramadan", "رمضان", "Ramadan", "رمضان|ramadan"),
            Rule("kids", "أطفال وأنمي", "Kids & anime", "kid|child|اطفال|أطفال|cartoon|كرتون|انمي|أنمي|anime|animation|spacetoon|سبيستون|رسوم"),
            Rule("docs", "وثائقي", "Documentaries", "docu|وثائقي"),
            Rule("turkish", "تركي", "Turkish", "turk|ترك"),
            Rule("asian", "كوري وآسيوي", "Korean & Asian", "korea|كوري|asia|اسيو|آسيو|china|chinese|صين|japan|يابان|thai|تايلند"),
            Rule("indian", "هندي", "Indian", "india|هند|bollywood|بوليوود"),
            Rule("arabic", "عربي", "Arabic", "arab|عرب|مصر|خليج|سوري|لبنان|سعودي|مغرب|عراق|كويت|اردن|تونس|جزائر|egypt|khaleej|syri"),
            Rule("foreign", "أجنبي وعالمي", "International",
                "english|اجنبي|أجنبي|foreign|hollywood|هوليوود|(^|[^a-z])(en|us|uk)([^a-z]|$)|spanish|اسباني|french|فرنس|europe|اوروب|latino"),
        )
        private val arabicChar = Regex("[\\u0600-\\u06FF]")

        private fun arabicUi() = (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
            .ifBlank { Locale.getDefault().language }) == "ar"

        fun shelfId(name: String): String {
            val n = name.lowercase()
            rules.firstOrNull { it.re.containsMatchIn(n) }?.let { return it.id }
            return if (arabicChar.containsMatchIn(n)) "other" else "foreign"
        }

        fun shelfTitle(id: String): String {
            val ar = arabicUi()
            if (id == "other") return if (ar) "أخرى" else "More"
            val r = rules.first { it.id == id }
            return if (ar) r.ar else r.en
        }

        /** "Zmora: PL" / "ZMORA" / "Zmóra" → "zmora". */
        fun normalize(title: String): String {
            val words = title.split(' ').filter { it.isNotEmpty() }.toMutableList()
            while (words.size > 1 && words.last().let { w -> w.length <= 3 && w.all { it in 'A'..'Z' } }) words.removeAt(words.lastIndex)
            val folded = Normalizer.normalize(words.joinToString(" "), Normalizer.Form.NFKD).replace(Regex("\\p{Mn}+"), "").lowercase()
            val out = folded.filter { it.isLetterOrDigit() }
            return out.ifEmpty { title.lowercase() }
        }

        private val reDub = Regex("مدبلج|dubbed ?ar|ar ?dub|arabic dub")
        private val reSub = Regex("مترجم|arsub|ar-sub|ar sub|sub ?ar|arabic sub|subbed")
        private val reAr = Regex("عرب|arab|(^|[^a-z])ar([^a-z]|$)")
        private val reEn = Regex("english|(^|[^a-z])(en|eng|us|uk)([^a-z]|$)")
        private val reEs = Regex("españa|espana|spain|spanish|latino|(^|[^a-z])(es|esp)([^a-z]|$)")
        private val reNordic = Regex("nordic|sweden|swedish|norway|danish|denmark|finland|(^|[^a-z])(se|no|dk|fi)([^a-z]|$)")
        private val reTr = Regex("turk|ترك|(^|[^a-z])tr([^a-z]|$)")
        private val reFr = Regex("french|france|(^|[^a-z])fr([^a-z]|$)")
        private val reDe = Regex("german|deutsch|(^|[^a-z])de([^a-z]|$)")
        private val rePl = Regex("poland|polish|(^|[^a-z])pl([^a-z]|$)")
        private val reHi = Regex("india|hindi|هند")

        /** Language of a copy from its name and category: what the version chooser shows and sorts by (Arabic first). */
        fun language(name: String, category: String): Pair<String, Int> {
            val n = "$name $category".lowercase()
            val ar = arabicUi()
            return when {
                reDub.containsMatchIn(n) -> (if (ar) "مدبلج عربي" else "Arabic dubbed") to 0
                reSub.containsMatchIn(n) -> (if (ar) "مترجم عربي" else "Arabic subtitles") to 1
                reAr.containsMatchIn(n) -> (if (ar) "عربي" else "Arabic") to 2
                reEn.containsMatchIn(n) -> (if (ar) "إنجليزي" else "English") to 3
                reEs.containsMatchIn(n) -> (if (ar) "إسباني" else "Spanish") to 5
                reNordic.containsMatchIn(n) -> (if (ar) "إسكندنافي" else "Nordic") to 5
                reTr.containsMatchIn(n) -> (if (ar) "تركي" else "Turkish") to 4
                reFr.containsMatchIn(n) -> (if (ar) "فرنسي" else "French") to 5
                reDe.containsMatchIn(n) -> (if (ar) "ألماني" else "German") to 5
                rePl.containsMatchIn(n) -> (if (ar) "بولندي" else "Polish") to 5
                reHi.containsMatchIn(n) -> (if (ar) "هندي" else "Hindi") to 5
                else -> "" to 4
            }
        }

        private val q4k = Regex("(^|[^A-Z0-9])(4K|UHD|2160P?)([^A-Z0-9]|$)")
        private val qFhd = Regex("(^|[^A-Z0-9])(FHD|1080P?)([^A-Z0-9]|$)")
        private val qHd = Regex("(^|[^A-Z0-9])(HD|720P?)([^A-Z0-9]|$)")
        fun qualityScore(name: String): Int {
            val n = name.uppercase()
            return when { q4k.containsMatchIn(n) -> 3; qFhd.containsMatchIn(n) -> 2; qHd.containsMatchIn(n) -> 1; else -> 0 }
        }
        fun qualityLabel(q: Int) = when (q) { 3 -> "4K"; 2 -> "FHD"; 1 -> "HD"; else -> "" }

        fun build(type: String, items: List<LibItem>, cats: List<CategoryEntity>): Index {
            val counts = HashMap<String, Int>()
            items.forEach { counts[it.categoryId] = (counts[it.categoryId] ?: 0) + 1 }
            val catMap = cats.associateBy { it.remoteId }
            val groups = LinkedHashMap<String, MutableList<CategoryEntity>>()
            val shelfOf = HashMap<String, String>()
            for (c in cats) {
                if ((counts[c.remoteId] ?: 0) == 0) continue
                val sid = shelfId(c.name)
                groups.getOrPut(sid) { mutableListOf() } += c
                shelfOf[c.remoteId] = sid
            }
            val shelves = (rules.map { it.id } + "other").mapNotNull { sid ->
                val cs = groups[sid]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val sorted = cs.sortedByDescending { counts[it.remoteId] ?: 0 }
                Shelf(sid, shelfTitle(sid), sorted, sorted.sumOf { counts[it.remoteId] ?: 0 })
            }
            val quality = HashMap<Long, Int>(items.size * 2)
            val clean = HashMap<Long, Pair<String, String>>(items.size * 2)
            val byTitle = HashMap<String, MutableList<Pair<LibItem, String>>>()
            for (m in items) {
                val c = cleanFull(m.name)
                val y = m.year.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) } ?: c.second
                clean[m.id] = c.first to y
                quality[m.id] = qualityScore(m.name)
                byTitle.getOrPut(normalize(c.first)) { mutableListOf() } += m to y
            }
            val key = HashMap<Long, String>(items.size * 2)
            val variants = HashMap<String, List<LibItem>>()
            val titleTmdb = HashMap<String, String>()
            for ((t, list) in byTitle) {
                val years = HashMap<String, Int>()
                list.forEach { (_, y) -> if (y.isNotEmpty()) years[y] = (years[y] ?: 0) + 1 }
                val main = years.maxByOrNull { it.value }?.key.orEmpty()
                val gs = HashMap<String, MutableList<LibItem>>()
                for ((m, y) in list) {
                    // a year within one of a known year is the same release (providers disagree by a year)
                    val near = years.keys.firstOrNull { k -> val a = k.toIntOrNull(); val b = y.toIntOrNull(); a != null && b != null && kotlin.math.abs(a - b) <= 1 }
                    val g = if (y.isEmpty()) main else (near ?: y)
                    gs.getOrPut(g) { mutableListOf() } += m
                }
                for ((y, ms) in gs) {
                    val k = "$t|$y"
                    ms.forEach { key[it.id] = k }
                    ms.firstOrNull { it.tmdb.isNotEmpty() }?.let { titleTmdb[k] = it.tmdb }
                    variants[k] = ms.sortedWith(compareByDescending<LibItem> { quality[it.id] ?: 0 }.thenByDescending { it.added })
                }
            }
            val tmdbCount = HashMap<String, Int>()
            items.forEach { if (it.tmdb.isNotEmpty()) tmdbCount[it.tmdb] = (tmdbCount[it.tmdb] ?: 0) + 1 }
            return Index(type, items, items.associateBy { it.id }, catMap, shelves, shelfOf, key, variants, quality, clean, titleTmdb, tmdbCount)
        }
    }
}
