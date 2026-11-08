package com.nova.iptv

import com.nova.iptv.data.network.XtreamParser
import com.nova.iptv.data.network.XtreamUrls
import com.nova.iptv.data.parser.M3uEntry
import com.nova.iptv.data.parser.M3uParser
import com.nova.iptv.data.parser.XmltvTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.util.TimeZone

class M3uParserTest {

    private fun parse(text: String): Pair<List<M3uEntry>, String> {
        val out = ArrayList<M3uEntry>()
        val header = M3uParser.parse(BufferedReader(StringReader(text))) { out += it }
        return out to header.epgUrl
    }

    @Test
    fun parsesAttributesGroupsAndHeaderEpg() {
        val (entries, epg) = parse(
            """
            #EXTM3U x-tvg-url="http://epg.example/guide.xml.gz"
            #EXTINF:-1 tvg-id="news.one" tvg-logo="http://img/1.png" group-title="News, World",News One HD
            http://host/live/1.ts
            #EXTINF:-1 tvg-name="Sports" group-title="Sport",
            #EXTVLCOPT:http-user-agent=MyAgent
            http://host/live/2.m3u8
            #EXTINF:0,Film Night
            #EXTGRP:Cinema
            http://host/movie/user/pass/55.mkv
            """.trimIndent()
        )
        assertEquals("http://epg.example/guide.xml.gz", epg)
        assertEquals(3, entries.size)
        with(entries[0]) {
            assertEquals("News One HD", name)
            assertEquals("News, World", group)
            assertEquals("news.one", tvgId)
            assertEquals("http://img/1.png", logo)
            assertFalse(isVod)
        }
        assertEquals("Sports", entries[1].name)
        assertEquals("MyAgent", entries[1].userAgent)
        assertEquals("Cinema", entries[2].group)
        assertTrue(entries[2].isVod)
    }

    @Test
    fun handlesBomAndCrLf() {
        val (entries, _) = parse("﻿#EXTM3U\r\n#EXTINF:-1,Chan\r\nhttp://a/b.ts\r\n")
        assertEquals(1, entries.size)
        assertEquals("Chan", entries[0].name)
    }

    @Test
    fun rejectsNonPlaylist() {
        try {
            parse("<html>\n<body>\nnot found\n</body>\n</html>\n<a>\n<b>\n")
            fail("expected exception")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun titleSeparatorIgnoresQuotedCommas() {
        val line = "#EXTINF:-1 group-title=\"A, B\",Name, with comma"
        val idx = M3uParser.titleSeparator(line)
        assertEquals("Name, with comma", line.substring(idx + 1))
    }
}

class XmltvTimeTest {
    @Test
    fun parsesWithOffset() {
        // 2024-12-31 20:30:00 +0300 == 17:30:00 UTC
        assertEquals(1735666200000L, XmltvTime.parse("20241231203000 +0300"))
    }

    @Test
    fun parsesNegativeOffsetAndNoOffset() {
        assertEquals(1735666200000L, XmltvTime.parse("20241231123000 -0500"))
        assertEquals(1735666200000L, XmltvTime.parse("20241231173000"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(XmltvTime.parse("abc"))
        assertNull(XmltvTime.parse(null))
    }
}

class XtreamTest {
    @Test
    fun normalizesServerUrls() {
        assertEquals("http://host:8080", XtreamUrls.normalizeServer("host:8080"))
        assertEquals("http://host:8080", XtreamUrls.normalizeServer(" http://host:8080/ "))
        assertEquals("https://h.tv", XtreamUrls.normalizeServer("https://h.tv/player_api.php?username=a&password=b"))
        assertEquals("http://h.tv:80", XtreamUrls.normalizeServer("http://h.tv:80/get.php?username=a&password=b&type=m3u"))
    }

    @Test
    fun buildsEncodedStreamUrls() {
        assertEquals("http://h/live/u%40x/p%20w/12.ts", XtreamUrls.live("http://h", "u@x", "p w", "12", "ts"))
        assertEquals("http://h/live/u/p/12.m3u8", XtreamUrls.live("http://h", "u", "p", "12", "m3u8"))
        assertEquals("http://h/movie/u/p/7.mkv", XtreamUrls.movie("http://h", "u", "p", "7", "mkv"))
        assertEquals("http://h/series/u/p/9.mp4", XtreamUrls.episode("http://h", "u", "p", "9", ""))
        val ts = XtreamUrls.timeshift("http://h", "u", "p", "5", 1735666200000L, 60, TimeZone.getTimeZone("UTC"))
        assertEquals("http://h/timeshift/u/p/60/2024-12-31:17-30/5.ts", ts)
    }

    @Test
    fun parsesAuthWithMixedTypes() {
        val auth = XtreamParser.parseAuth(
            StringReader("""{"user_info":{"auth":1,"status":"Active","exp_date":"1735689600","max_connections":"2"},"server_info":{}}""")
        )
        assertTrue(auth.authenticated)
        assertEquals("Active", auth.status)
        assertEquals(1735689600000L, auth.expiresAtMs)
        assertEquals(2, auth.maxConnections)

        val bad = XtreamParser.parseAuth(StringReader("""{"user_info":{"auth":0}}"""))
        assertFalse(bad.authenticated)
    }

    @Test
    fun streamsListsTolerantly() {
        val items = ArrayList<Map<String, String>>()
        val n = XtreamParser.readObjects(
            StringReader(
                """[{"num":1,"name":"A","stream_id":101,"category_id":"3","tv_archive":1,"epg_channel_id":null,"category_ids":[3]},
                   {"num":"2","name":"B","stream_id":"102","category_id":4,"rating":4.5,"extra":{"x":1}}]"""
            )
        ) { items += it }
        assertEquals(2, n)
        assertEquals("101", items[0]["stream_id"])
        assertEquals("1", items[0]["tv_archive"])
        assertNull(items[0]["epg_channel_id"])
        assertEquals("4", items[1]["category_id"])
        assertEquals("4.5", items[1]["rating"])
    }

    @Test
    fun categoriesFromObjectOrEmpty() {
        val cats = XtreamParser.readCategories(StringReader("""[{"category_id":"1","category_name":"News"}]"""))
        assertEquals("News", cats.single().name)
        assertTrue(XtreamParser.readCategories(StringReader("[]")).isEmpty())
        assertTrue(XtreamParser.readCategories(StringReader("null")).isEmpty())
    }

    @Test
    fun parsesSeriesInfoBothEpisodeShapes() {
        val objShape = XtreamParser.parseSeriesInfo(
            StringReader(
                """{"info":{"name":"Show","plot":"P"},"episodes":{"1":[{"id":"11","episode_num":2,"title":"E2","container_extension":"mkv","info":{"duration_secs":1500}},
                   {"id":"10","episode_num":1,"title":"E1","container_extension":"mp4"}],"2":[{"id":"20","episode_num":1,"season":2}]}}"""
            )
        )
        assertEquals(listOf(1, 2), objShape.seasons.keys.toList())
        assertEquals(listOf("10", "11"), objShape.seasons[1]!!.map { it.id })
        assertEquals(1500, objShape.seasons[1]!![1].durationSecs)

        val arrShape = XtreamParser.parseSeriesInfo(
            StringReader("""{"info":{},"episodes":[[{"id":"1","episode_num":1,"season":1}],[{"id":"2","episode_num":1,"season":3}]]}""")
        )
        assertEquals(listOf(1, 3), arrShape.seasons.keys.toList())
    }

    @Test
    fun parsesVodInfoBackdropArray() {
        val info = XtreamParser.parseVodInfo(
            StringReader("""{"info":{"plot":"Story","backdrop_path":["http://b/1.jpg"],"duration":"01:40:00"},"movie_data":{}}""")
        )
        assertEquals("Story", info.plot)
        assertEquals("http://b/1.jpg", info.backdrop)
        assertEquals("01:40:00", info.duration)
    }
}

class SegmentsParserTest {
    @Test
    fun parsesIntroRecapCredits() {
        val s = com.nova.iptv.data.network.SegmentsParser.parse(
            StringReader("""{"type":"tv","season":1,"episode":2,"intro":[{"start_ms":31000,"end_ms":92500}],"recap":[{"start_ms":0,"end_ms":28000}],"credits":[{"start_ms":2580000,"end_ms":null}],"preview":[]}""")
        )
        assertEquals(31000L, s.intro!!.startMs)
        assertEquals(92500L, s.intro!!.endMs)
        assertEquals(28000L, s.recap!!.endMs)
        assertEquals(2580000L, s.credits!!.startMs)
        assertEquals(-1L, s.credits!!.endMs)
    }

    @Test
    fun handlesMissingOrInvalid() {
        val s = com.nova.iptv.data.network.SegmentsParser.parse(StringReader("""{"intro":[{"start_ms":null,"end_ms":null}],"credits":[]}"""))
        assertNull(s.intro)
        assertNull(s.credits)
        assertNull(com.nova.iptv.data.network.SegmentsParser.parse(StringReader("[]")).intro)
    }

    @Test
    fun seriesTmdbId() {
        val info = XtreamParser.parseSeriesInfo(StringReader("""{"info":{"name":"X","tmdb":"1396"},"episodes":{}}"""))
        assertEquals("1396", info.tmdbId)
    }
}

class SrtTest {
    @Test
    fun parsesAndWritesSrt() {
        val raw = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Merhaba</i>\r\ndünya\r\n\r\n2\r\n00:00:04.000 --> 00:00:06,000\r\nNasılsın?\r\n\r\n"
        val cues = com.nova.iptv.data.subs.Srt.parse(raw)
        assertEquals(2, cues.size)
        assertEquals("00:00:04,000 --> 00:00:06,000", cues[1].time)
        assertEquals("Merhaba dünya", com.nova.iptv.data.subs.Srt.plain(cues[0].text))
        val out = com.nova.iptv.data.subs.Srt.write(cues)
        assertTrue(out.startsWith("1\n00:00:01,000 --> 00:00:03,500\n"))
        assertEquals(2, com.nova.iptv.data.subs.Srt.parse(out).size)
    }
}
