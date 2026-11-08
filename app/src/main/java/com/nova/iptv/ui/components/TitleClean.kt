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
