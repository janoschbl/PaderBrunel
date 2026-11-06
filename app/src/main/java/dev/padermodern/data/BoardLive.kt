package dev.padermodern.data

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Applies pushed delay changes to a departure board that was loaded from another source. */
object BoardLive {
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    fun lineKey(s: String?) = s.orEmpty().filterNot { it.isWhitespace() }.lowercase()

    /**
     * A snapshot only sets delays. A change also clears them: a pushed entry that disappears means the bus is back
     * on time. Returns null when nothing on the board changed.
     */
    fun apply(current: List<Departure>, d: BDelta): List<Departure>? {
        val set = d.set.associate { (it[0].jsonPrimitive.long to lineKey(it[1].jsonPrimitive.content)) to it[2].jsonPrimitive.long }
        val clear = if (d.snap) emptySet() else d.clear.map { it[0].jsonPrimitive.long to lineKey(it[1].jsonPrimitive.content) }.toSet()
        var changed = false
        val next = current.map { dep ->
            val minute = parseTime(dep.departureDate)?.toEpochSecond()?.div(60) ?: return@map dep
            val key = minute to lineKey(dep.shortName ?: dep.lineName)
            val expected = when {
                key in set -> ZonedDateTime.ofInstant(Instant.ofEpochSecond(set.getValue(key)), berlin).iso()
                key in clear -> null
                else -> return@map dep
            }
            if (expected == dep.expectedDepartureDate) dep else { changed = true; dep.copy(expectedDepartureDate = expected) }
        }
        return if (changed) next else null
    }
}
