package com.nova.iptv.data.parser

import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
)

/** Streaming XMLTV parser (supports gzip). Only programmes inside [from, to] are emitted. */
class XmltvParser(private val newParser: () -> XmlPullParser) {

    fun parse(input: InputStream, from: Long, to: Long, onProgramme: (XmltvProgramme) -> Unit) {
        val buffered = BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val stream: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered

        val parser = newParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var channel = ""
        var start = 0L
        var stop = 0L
        var title = ""
        var desc = ""
        var inProgramme = false
        var keep = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        inProgramme = true
                        channel = parser.getAttributeValue(null, "channel").orEmpty()
                        start = XmltvTime.parse(parser.getAttributeValue(null, "start")) ?: 0L
                        stop = XmltvTime.parse(parser.getAttributeValue(null, "stop")) ?: (start + 3_600_000L)
                        keep = start > 0 && stop > from && start < to && channel.isNotEmpty()
                        title = ""
                        desc = ""
                    }
                    "title" -> if (inProgramme && keep && title.isEmpty()) title = safeText(parser)
                    "desc" -> if (inProgramme && keep && desc.isEmpty()) desc = safeText(parser)
                }
                XmlPullParser.END_TAG -> if (parser.name == "programme") {
                    if (keep && title.isNotEmpty()) {
                        onProgramme(XmltvProgramme(channel, start, stop, title, desc))
                    }
                    inProgramme = false
                }
            }
            event = parser.next()
        }
    }

    private fun safeText(parser: XmlPullParser): String = try {
        parser.nextText().trim()
    } catch (e: Exception) {
        ""
    }
}
