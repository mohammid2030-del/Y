package com.nova.iptv.data.parser

import java.io.BufferedReader

data class M3uEntry(
    val name: String,
    val url: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val catchup: String,
    val catchupDays: Int,
    val userAgent: String,
) {
    /** VOD detection for M3U sources: Xtream-style paths or file extensions. */
    val isVod: Boolean
        get() {
            val u = url.substringBefore('?').lowercase()
            return u.contains("/movie/") || u.contains("/series/") ||
                VOD_EXT.any { u.endsWith(it) }
        }

    companion object {
        private val VOD_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".m4v", ".webm")
    }
}

data class M3uHeader(val epgUrl: String)

/** Streaming M3U/M3U8 playlist parser. Handles tens of thousands of entries with O(1) memory. */
object M3uParser {
    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parseAttributes(line: String): Map<String, String> =
        ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Returns the index of the first comma that is not inside double quotes. */
    fun titleSeparator(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    /**
     * @return header info (EPG url advertised in #EXTM3U, if any).
     * @throws IllegalStateException if the content is not an M3U playlist.
     */
    fun parse(reader: BufferedReader, onEntry: (M3uEntry) -> Unit): M3uHeader {
        var epgUrl = ""
        var sawHeader = false
        var pendingAttrs: Map<String, String>? = null
        var pendingName = ""
        var pendingGroup = ""
        var pendingUa = ""
        var lineNo = 0

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("﻿")
            lineNo++
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    sawHeader = true
                    val attrs = parseAttributes(line)
                    epgUrl = attrs["x-tvg-url"] ?: attrs["url-tvg"] ?: attrs["tvg-url"] ?: ""
                    epgUrl = epgUrl.split(',').firstOrNull()?.trim().orEmpty()
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    sawHeader = true
                    val sep = titleSeparator(line)
                    val attrPart = if (sep >= 0) line.substring(0, sep) else line
                    pendingAttrs = parseAttributes(attrPart)
                    pendingName = if (sep >= 0) line.substring(sep + 1).trim() else ""
                    pendingGroup = ""
                    pendingUa = ""
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11).trim()
                    if (opt.startsWith("http-user-agent=", ignoreCase = true)) pendingUa = opt.substringAfter('=')
                }
                line.startsWith("#") -> Unit
                else -> {
                    val attrs = pendingAttrs
                    if (attrs != null) {
                        val name = pendingName.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { line.substringAfterLast('/') }
                        onEntry(
                            M3uEntry(
                                name = name,
                                url = line,
                                group = attrs["group-title"].orEmpty().ifBlank { pendingGroup },
                                logo = attrs["tvg-logo"].orEmpty(),
                                tvgId = attrs["tvg-id"].orEmpty(),
                                catchup = attrs["catchup"] ?: attrs["catchup-type"].orEmpty(),
                                catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0,
                                userAgent = pendingUa.ifBlank { attrs["user-agent"].orEmpty() },
                            )
                        )
                    } else if (!sawHeader && lineNo > 5) {
                        throw IllegalStateException("not m3u")
                    }
                    pendingAttrs = null
                }
            }
        }
        if (!sawHeader) throw IllegalStateException("not m3u")
        return M3uHeader(epgUrl)
    }
}
