package com.nova.iptv.ui.components

/**
 * Providers often decorate names with language / quality tags ("AR: Title - 4K-AR-DO").
 * These helpers strip only well-known tags so real titles ("CSI: Miami") stay intact.
 */
private val PrefixCodes = setOf(
    "AR", "EN", "TR", "FR", "DE", "ES", "IT", "IN", "US", "UK", "KSA", "SA", "EG", "AE", "KW", "QA",
    "NF", "OSN", "AS", "KR", "CN", "JP", "HI", "4K", "8K", "FHD", "UHD", "HD", "VIP", "MULTI",
)
private val TagTokens = setOf(
    "4K", "8K", "UHD", "FHD", "HD", "SD", "HEVC", "H265", "H264", "HDR", "AR", "EN", "TR", "DO", "DB",
    "SUB", "SUBS", "DUB", "DUBBED", "MULTI", "ARSUB", "VIP", "NF", "ULTRA", "ULTRAHD", "RAW",
)
private val prefixRe = Regex("""^\s*[|\[(]?\s*([A-Za-z0-9]{2,5})\s*[|\])]?\s*[:|]\s*""")
private val dashPrefixRe = Regex("""^\s*([A-Za-z0-9][A-Za-z0-9_\-]{0,12})\s+[-|–—]\s+""")
private val bracketTagRe = Regex("""\s*[\[(]([^\])]{1,20})[\])]\s*$""")
private val dashTailRe = Regex("""\s+[-|–—]\s+([A-Za-z0-9][A-Za-z0-9_\-]{0,24})\s*$""")
private val seRe = Regex("""[Ss]\s*0*(\d{1,2})\s*[ .xX-]?\s*[Ee]\s*0*(\d{1,3})""")

private fun isTagGroup(s: String): Boolean {
    val parts = s.split('-', ' ', '_').filter { it.isNotBlank() }
    return parts.isNotEmpty() && parts.all { it.uppercase() in TagTokens }
}

fun String.cleanTitle(): String {
    var t = trim()
    prefixRe.find(t)?.let { m ->
        if (m.groupValues[1].uppercase() in PrefixCodes && t.length > m.value.length) t = t.substring(m.value.length)
    }
    dashPrefixRe.find(t)?.let { m ->
        val parts = m.groupValues[1].split('-', '_').filter { it.isNotBlank() }
        val isTag = parts.isNotEmpty() && parts.all { it.uppercase() in PrefixCodes || it.uppercase() in TagTokens }
        if (isTag && t.length > m.value.length) t = t.substring(m.value.length)
    }
    var changed = true
    while (changed) {
        changed = false
        bracketTagRe.find(t)?.let { m ->
            if (isTagGroup(m.groupValues[1]) && m.range.first > 0) { t = t.substring(0, m.range.first).trimEnd(); changed = true }
        }
        dashTailRe.find(t)?.let { m ->
            if (isTagGroup(m.groupValues[1]) && m.range.first > 0) { t = t.substring(0, m.range.first).trimEnd(); changed = true }
        }
    }
    return t.ifBlank { trim() }
}

/** Season / episode numbers found in an episode label such as "S01E02 - …" or "S1 E2". */
fun seasonEpisodeOf(text: String): Pair<Int, Int>? =
    seRe.find(text)?.let { m -> m.groupValues[1].toIntOrNull()?.let { s -> m.groupValues[2].toIntOrNull()?.let { e -> s to e } } }

private val AllTags = TagTokens + setOf(
    "TOP", "NEW", "AMZ", "ATV", "DSNP", "HMAX", "MAX", "HULU", "PRIME", "US", "UK", "GB", "CA", "AU", "IN", "KR", "JP", "FR", "DE",
    "ES", "IT", "MX", "BR", "SUIBS", "DV", "DOLBY", "ATMOS", "REMUX", "WEB", "WEBDL", "BLURAY", "1080P", "720P", "2160P",
)
private fun isAnyTagGroup(s: String): Boolean {
    val parts = s.split('-', ' ', '_').filter { it.isNotBlank() }.map { it.uppercase() }
    return parts.isNotEmpty() && parts.all { it in AllTags || it in PrefixCodes }
}
/** "4K-TOP", "TOP", "NF-AR", "VIP+" — an all-caps provider tag, never a real title word. */
private fun isProviderTag(g: String): Boolean {
    if (isAnyTagGroup(g)) return true
    val letters = g.filter { it.isLetter() }
    return g.length <= 12 && letters.isNotEmpty() && letters.all { it in 'A'..'Z' } &&
        (g.length <= 4 || g.any { it.isDigit() || it in "-_+." })
}
private val fBracketStart = Regex("""^\s*[\[(|▎]\s*([^\])|▎]{1,12})\s*[\])|▎]\s*[:\-–—]?\s*""")
private val fCodePrefix = Regex("""^\s*([A-Za-z0-9]{2,5})\s*[:|]\s*""")
private val fTagPrefix = Regex("""^\s*([A-Za-z0-9][A-Za-z0-9_\-+.]{0,14})\s*[-|–—:▎]\s+""")
private val fYearBracket = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
private val fAnyBracket = Regex("""[(\[][^)\]]*[)\]]""")
private val fSeason = Regex("""\s[-–]?\s*(S\d{1,2}(\s*E\d{1,3})?|Season\s*\d+|الموسم\s*\S+)\b.*$""", RegexOption.IGNORE_CASE)
private val fTailYear = Regex("""\s[-–]?\s*((?:19|20)\d{2})\s*$""")

/** Same cleaning as the Apple and Samsung apps: "4K-AMZ - Drive (2025) (US) HEVC" → ("Drive", "2025"). */
fun cleanFull(raw: String): Pair<String, String> {
    var t = raw.trim()
    repeat(2) {
        fBracketStart.find(t)?.let { m -> if (isProviderTag(m.groupValues[1]) && t.length - m.value.length > 1) t = t.substring(m.value.length) }
    }
    fCodePrefix.find(t)?.let { m -> if (m.groupValues[1].uppercase() in PrefixCodes && t.length > m.value.length) t = t.substring(m.value.length) }
    repeat(2) {
        fTagPrefix.find(t)?.let { m -> if (isProviderTag(m.groupValues[1]) && t.length - m.value.length > 1) t = t.substring(m.value.length) }
    }
    var year = ""
    fYearBracket.find(t)?.let { m ->
        year = m.groupValues[1]
        if (m.range.first > 0) t = t.substring(0, m.range.first)
    }
    t = t.replace(fAnyBracket, " ")
    t = t.replace(fSeason, "")
    if (year.isEmpty()) fTailYear.find(t)?.let { m -> if (m.range.first > 0) { year = m.groupValues[1]; t = t.substring(0, m.range.first) } }
    val words = t.split(' ').filter { it.isNotBlank() }.toMutableList()
    while (words.size > 1 && (words.last().trim('-', '|').uppercase() in AllTags || words.last() in setOf("-", "|", "–"))) words.removeAt(words.lastIndex)
    val out = words.joinToString(" ").trim(' ', '-', '|', '–', ':')
    return out.ifEmpty { raw.trim() } to year
}

/** Clean title + year for TMDB lookups and display. */
fun searchTitle(raw: String): Pair<String, String> = cleanFull(raw)
