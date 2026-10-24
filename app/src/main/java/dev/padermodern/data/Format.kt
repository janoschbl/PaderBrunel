package dev.padermodern.data

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val parser = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

fun parseTime(s: String?): ZonedDateTime? = s?.let { runCatching { ZonedDateTime.parse(it, parser) }.getOrNull() }

fun ZonedDateTime.iso(): String = format(parser)

fun String?.hhmm(): String = parseTime(this)?.format(hhmm) ?: "–"

fun delayMinutes(planned: String?, expected: String?): Long? {
    val p = parseTime(planned) ?: return null
    val e = parseTime(expected) ?: return null
    return Duration.between(p, e).toMinutes()
}

fun minutesUntil(s: String?, now: ZonedDateTime = ZonedDateTime.now()): Long? =
    parseTime(s)?.let { Duration.between(now, it).toMinutes() }

fun durationLabel(start: String?, end: String?): String {
    val a = parseTime(start) ?: return ""
    val b = parseTime(end) ?: return ""
    val m = Duration.between(a, b).toMinutes()
    return when {
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

fun euro(v: Double): String = String.format(java.util.Locale.GERMANY, "%.2f €", v)

fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        result = 0
        shift = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        out += lat / 1e5 to lng / 1e5
    }
    return out
}
