package com.nova.iptv.data.parser

import java.util.Calendar
import java.util.TimeZone

object XmltvTime {
    /** Parses "20241231203000 +0300" (offset optional) into epoch millis (UTC). */
    fun parse(value: String?): Long? {
        val t = value?.trim() ?: return null
        if (t.length < 12) return null
        val y = t.substring(0, 4).toIntOrNull() ?: return null
        val mo = t.substring(4, 6).toIntOrNull() ?: return null
        val d = t.substring(6, 8).toIntOrNull() ?: return null
        val h = t.substring(8, 10).toIntOrNull() ?: return null
        val mi = t.substring(10, 12).toIntOrNull() ?: return null
        val s = if (t.length >= 14) t.substring(12, 14).toIntOrNull() ?: 0 else 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y, mo - 1, d, h, mi, s)
        var ms = cal.timeInMillis
        val rest = if (t.length > 14) t.substring(14).trim() else ""
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val sign = if (rest[0] == '-') -1 else 1
            val oh = rest.substring(1, 3).toIntOrNull() ?: 0
            val om = rest.substring(3, 5).toIntOrNull() ?: 0
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}
