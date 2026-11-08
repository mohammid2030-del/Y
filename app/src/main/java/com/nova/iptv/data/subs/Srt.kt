package com.nova.iptv.data.subs

data class SrtCue(val time: String, val text: String)

/** Minimal, tolerant SubRip reader/writer (pure Kotlin, unit-tested). */
object Srt {
    private val TIME = Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}""")
    private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(raw: String): List<SrtCue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val out = ArrayList<SrtCue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.trim('\n').split('\n')
            val ti = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (ti < 0) continue
            val body = lines.drop(ti + 1).joinToString("\n").trim()
            if (body.isEmpty()) continue
            out += SrtCue(TIME.find(lines[ti])!!.value.replace('.', ','), body)
        }
        return out
    }

    fun write(cues: List<SrtCue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(c.time).append('\n').append(c.text.trim()).append("\n\n")
        }
    }

    /** Removes styling tags and joins lines so translation sees whole sentences. */
    fun plain(text: String): String = text.replace(TAGS, "").replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
