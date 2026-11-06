package dev.padermodern.data

import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/**
 * Collects the stop lists that departure boards return. A board only lists a trip from its own stop
 * onward, so the same trip arrives as several partial lists; they share (line, tripCode, stop, time) and the
 * longest one wins.
 */
class TimetableBuilder(private val day: LocalDate) {
    private class Raw(val line: String, val short: String, val name: String, val type: Int, val destination: String, val calls: List<Call>)
    private class Call(val id: String, val name: String, val lat: Double, val lon: Double, val minute: Int)

    private val groups = ArrayList<Raw?>()
    private val index = HashMap<String, Int>()
    val tripCount get() = groups.count { it != null }

    /** Adds one departure's attached ride. Returns the last stop it reaches, or null when unusable. */
    fun add(d: Departure): Station? {
        val leg = d.route?.partialRoutes?.singleOrNull()?.takeIf { !it.isWalk } ?: return null
        val calls = leg.stations.mapNotNull { s ->
            val id = s.id ?: return@mapNotNull null
            Call(id, s.name.orEmpty(), s.latitude, s.longitude, minuteOf(s.date) ?: return@mapNotNull null)
        }
        if (calls.size < 2 || calls.size != leg.stations.size) return null
        val line = d.lineId ?: return null
        val keys = calls.map { "$line|${d.tripCode}|${it.id}|${it.minute}" }
        val existing = keys.firstNotNullOfOrNull { index[it] }
        val slot = existing ?: groups.size.also { groups += null }
        val old = groups[slot]
        if (old == null || calls.size > old.calls.size) {
            groups[slot] = Raw(line, d.shortName.orEmpty(), d.lineName.orEmpty(), d.type, d.destination.orEmpty(), calls)
            keys.forEach { index[it] = slot }
        }
        return leg.stations.last()
    }

    private fun minuteOf(date: String?): Int? = parseTime(date)?.withZoneSameInstant(BERLIN)?.toLocalDateTime()?.let {
        java.time.Duration.between(day.atStartOfDay(), it).toMinutes().toInt()
    }

    /** Stops ranked by how many collected trips call there; ids in [skip] and stops outside [accept] are left out. */
    fun busiest(skip: Set<String>, accept: (lat: Double, lon: Double) -> Boolean, limit: Int): List<String> {
        val counts = HashMap<String, Int>(); val where = HashMap<String, Call>()
        groups.forEach { g -> g?.calls?.forEach { c -> counts.merge(c.id, 1, Int::plus); where[c.id] = c } }
        return counts.entries.asSequence().filter { it.key !in skip && where.getValue(it.key).let { c -> accept(c.lat, c.lon) } }
            .sortedByDescending { it.value }.take(limit).map { it.key }.toList()
    }

    fun build(): LocalTimetable {
        val raws = groups.filterNotNull()
        val stopIndex = LinkedHashMap<String, Int>()
        val ids = ArrayList<String>(); val names = ArrayList<String>(); val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        raws.forEach { r -> r.calls.forEach { c -> stopIndex.getOrPut(c.id) { ids += c.id; names += c.name; la += c.lat; lo += c.lon; ids.size - 1 } } }
        val trips = raws.map { r -> LocalTrip(r.line, r.short, r.name, r.type, r.destination,
            IntArray(r.calls.size) { stopIndex.getValue(r.calls[it].id) }, IntArray(r.calls.size) { r.calls[it].minute }) }
        return LocalTimetable(day, ids.toTypedArray(), names.toTypedArray(), la.toDoubleArray(), lo.toDoubleArray(), trips)
    }

    private companion object { val BERLIN: ZoneId = ZoneId.of("Europe/Berlin") }
}

fun LocalTimetable.write(out: OutputStream) {
    GZIPOutputStream(out).bufferedWriter().use { w ->
        w.write("PMTT1 $day\n")
        for (i in 0 until stopCount) w.write("S|${stopIds[i]}|${stopNames[i].replace('|', ' ')}|${"%.5f".format(java.util.Locale.ROOT, lat[i])}|${"%.5f".format(java.util.Locale.ROOT, lon[i])}\n")
        for (t in trips) {
            w.write("T|${t.line}|${t.shortName}|${t.lineName.replace('|', ' ')}|${t.type}|${t.destination.replace('|', ' ')}|")
            w.write(t.stops.indices.joinToString(",") { "${t.stops[it]}:${t.minutes[it]}" })
            w.write("\n")
        }
    }
}
