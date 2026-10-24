package dev.padermodern.data

import java.util.Locale

data class BusRecord(
    val company: Int,
    val lineId: Int,
    val destinationId: Int,
    val nextStopId: Int,
    val hardwareId: Int,
    val course: Int,
    val simulator: Boolean = false,
)

object BusAdvertisement {
    private const val SERVICE_UUID = 0x1f1f

    fun parse(raw: ByteArray): BusRecord? {
        var offset = 0
        var hasService = false
        var manufacturer: ByteArray? = null
        while (offset < raw.size) {
            val length = raw[offset].toInt() and 0xff
            if (length == 0) {
                if ((offset + 1 until raw.size).any { raw[it].toInt() != 0 }) return null
                break
            }
            if (offset + length + 1 > raw.size || length < 1) return null
            val type = raw[offset + 1].toInt() and 0xff
            val start = offset + 2
            val end = offset + length + 1
            if (type in 0x02..0x05) {
                val width = if (type == 0x02 || type == 0x03) 2 else 4
                if ((end - start) % width != 0) return null
                for (i in start until end step width) {
                    val uuid = (0 until width).fold(0L) { acc, j -> acc or ((raw[i + j].toLong() and 0xff) shl (8 * j)) }
                    if (uuid == SERVICE_UUID.toLong()) hasService = true
                }
            } else if (type == 0xff) {
                manufacturer = raw.copyOfRange(start, end)
            }
            offset = end
        }
        if (!hasService) return null
        val data = manufacturer ?: return null
        if (data.size < 18) return null
        val simulator = data[0] == 0x79.toByte() && data[1] == 0x7d.toByte()
        if (!simulator && !(data[0] == 0x78.toByte() && data[1] == 0x7d.toByte())) return null
        if (((data[2].toInt() and 0xff) shl 8 or (data[3].toInt() and 0xff)) != 16 || (data[5].toInt() and 0xff) != 4) return null
        return BusRecord(4, word(data, 6), word(data, 8), word(data, 10), word(data, 12), word(data, 14), simulator)
    }

    private fun word(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}

data class BusNames(
    val lines: Map<Int, String>,
    val destinations: Map<Int, String>,
    val stops: Map<Int, String>,
)

fun BusNames.match(record: BusRecord, leg: PartialRoute): Int? {
    val line = normalize(lines[record.lineId]) ?: return null
    if (record.company != 4 || record.simulator || line != normalize(leg.shortName)) return null
    val direction = normalize(leg.lineDirection)?.removePrefix("nach ")?.trim().orEmpty()
    if (direction.isBlank() || normalize(destinations[record.destinationId])?.removePrefix("nach ")?.trim() != direction) return null
    val stopName = normalize(stops[record.nextStopId]) ?: return null
    val matches = leg.stations.filter { normalize(it.displayName) == stopName }
    return matches.singleOrNull()?.let { leg.stations.indexOf(it) }
}

private fun normalize(value: String?): String? = value?.lowercase(Locale.ROOT)
    ?.replace("str.", "straße")
    ?.replace("straße", "strasse")
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.removePrefix("paderborn, ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

class BusMatchTracker {
    private data class Candidate(val index: Int, val firstAt: Long, var lastAt: Long, var count: Int)
    private val candidates = mutableMapOf<String, Candidate>()
    private var boundKey: String? = null
    private var lastIndex: Int? = null
    var matched: Boolean = false
        private set
    var ambiguous: Boolean = false
        private set
    val nextIndex: Int? get() = lastIndex.takeIf { matched }

    fun observe(key: String, nextIndex: Int, nowMillis: Long, gpsNextIndex: Int?): Int? {
        expire(nowMillis)
        if (nextIndex <= 0) return null
        if (key != boundKey && gpsNextIndex != nextIndex && nextIndex != lastIndex) return null
        if (boundKey == null && gpsNextIndex != nextIndex) return null
        if (key == boundKey && nextIndex < (lastIndex ?: nextIndex)) return null
        val candidate = candidates[key]
        if (candidate == null && candidates.size >= 32) return null
        if (candidate != null && nowMillis <= candidate.lastAt) return null
        if (candidate == null || candidate.index != nextIndex) {
            candidates[key] = Candidate(nextIndex, nowMillis, nowMillis, 1)
        } else {
            candidate.lastAt = nowMillis
            candidate.count++
        }
        expire(nowMillis)
        if (ambiguous) return null
        val current = candidates[key] ?: return null
        if (current.count < 2 || nowMillis - current.firstAt < 2_000) return null
        if (boundKey == null) {
            boundKey = key
            lastIndex = nextIndex
            matched = true
            return null
        }
        if (boundKey != key) return null
        matched = true
        if (nextIndex == lastIndex) return null
        lastIndex = nextIndex
        return nextIndex
    }

    fun expire(nowMillis: Long): Boolean {
        candidates.entries.removeIf { nowMillis - it.value.lastAt > 10_000 }
        ambiguous = candidates.size > 1
        val current = candidates[boundKey]
        matched = current != null && current.index == lastIndex && current.count >= 2 &&
            current.lastAt - current.firstAt >= 2_000 && !ambiguous
        return matched
    }
}
