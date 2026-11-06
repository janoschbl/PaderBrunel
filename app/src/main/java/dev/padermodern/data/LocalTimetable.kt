package dev.padermodern.data

import java.io.BufferedReader
import java.io.InputStream
import java.io.Reader
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.hypot

private val berlinZone: ZoneId = ZoneId.of("Europe/Berlin")

/** One run of a vehicle. [minutes] are wall-clock minutes after 00:00 of the timetable day and may pass 1440. */
class LocalTrip(
    val line: String,
    val shortName: String,
    val lineName: String,
    val type: Int,
    val destination: String,
    val stops: IntArray,
    val minutes: IntArray,
)

/**
 * A day of trips with per-stop departure lists and short footpaths, laid out for scanning in
 * [LocalPlanner]. Text format, one record per line (gzip by convention):
 * `PMTT1 yyyy-MM-dd`, `S|id|name|lat|lon`, `T|lineId|short|lineName|type|destination|stopIndex:minute,...`.
 */
class LocalTimetable(
    val day: LocalDate,
    val stopIds: Array<String>,
    val stopNames: Array<String>,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val trips: List<LocalTrip>,
) {
    val stopCount get() = stopIds.size
    private val byId = HashMap<String, Int>().also { m -> stopIds.forEachIndexed { i, id -> m[id] = i } }
    fun indexOf(id: String?): Int = id?.let { byId[it] } ?: -1

    /** Trip index and position of every call (except the last) at a stop, ordered by time. */
    val depTrip: Array<IntArray>
    val depPos: Array<IntArray>

    init {
        val counts = IntArray(stopCount)
        trips.forEach { t -> for (j in 0 until t.stops.size - 1) counts[t.stops[j]]++ }
        // minute, trip and position packed into one long so a plain primitive sort orders by time
        val keys = Array(stopCount) { LongArray(counts[it]) }
        val fill = IntArray(stopCount)
        trips.forEachIndexed { ti, t -> for (j in 0 until t.stops.size - 1) {
            val s = t.stops[j]
            keys[s][fill[s]++] = (t.minutes[j].toLong() shl 40) or (ti.toLong() shl 12) or j.toLong()
        } }
        depTrip = Array(stopCount) { s -> keys[s].sort(); IntArray(keys[s].size) { ((keys[s][it] shr 12) and 0xFFFFFFF).toInt() } }
        depPos = Array(stopCount) { s -> IntArray(keys[s].size) { (keys[s][it] and 0xFFF).toInt() } }
    }

    fun minuteOf(at: ZonedDateTime): Int =
        java.time.Duration.between(day.atStartOfDay(), at.withZoneSameInstant(berlinZone).toLocalDateTime()).toMinutes().toInt()

    fun timeOf(minute: Int): ZonedDateTime = LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plusMinutes(minute.toLong()).atZone(berlinZone)

    fun covers(at: ZonedDateTime): Boolean = at.withZoneSameInstant(berlinZone).toLocalDate() == day

    fun metersBetween(a: Int, b: Int): Double {
        val dy = (lat[a] - lat[b]) * 111_320.0
        val dx = (lon[a] - lon[b]) * 111_320.0 * cos(Math.toRadians((lat[a] + lat[b]) / 2))
        return hypot(dx, dy)
    }

    private val byName: Map<String, List<Int>> by lazy {
        val m = HashMap<String, MutableList<Int>>()
        for (i in 0 until stopCount) m.getOrPut(normalizeName(stopNames[i])) { mutableListOf() }.add(i)
        m
    }

    /** Finds a provider stop by id, or else by name and position (GTFS-derived stops carry no provider id). */
    fun resolve(id: String?, name: String?, latitude: Double, longitude: Double): Int {
        indexOf(id).let { if (it >= 0) return it }
        val n = name?.let(::normalizeName) ?: return -1
        val candidates = byName[n] ?: return -1
        return candidates.minByOrNull { i ->
            val dy = (lat[i] - latitude) * 111_320.0; val dx = (lon[i] - longitude) * 111_320.0 * cos(Math.toRadians(latitude)); hypot(dx, dy)
        }?.takeIf { i ->
            latitude == 0.0 || run {
                val dy = (lat[i] - latitude) * 111_320.0; val dx = (lon[i] - longitude) * 111_320.0 * cos(Math.toRadians(latitude)); hypot(dx, dy) <= 450
            }
        } ?: -1
    }

    /** Stops within [meters] of a coordinate, nearest first. */
    fun near(latitude: Double, longitude: Double, meters: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val dyDeg = meters / 111_320.0
        for (i in 0 until stopCount) {
            if (kotlin.math.abs(lat[i] - latitude) > dyDeg) continue
            val dy = (lat[i] - latitude) * 111_320.0
            val dx = (lon[i] - longitude) * 111_320.0 * cos(Math.toRadians(latitude))
            val d = hypot(dx, dy)
            if (d <= meters) out += i to d.toInt()
        }
        return out.sortedBy { it.second }
    }

    /** Footpaths to stops closer than [FOOTPATH_METERS], as (stop, minutes). Built once, lazily. */
    val footpaths: Array<IntArray> by lazy {
        val cell = 0.004
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(x: Int, y: Int) = x.toLong() * 1_000_003L + y
        for (i in 0 until stopCount) grid.getOrPut(key((lon[i] / cell).toInt(), (lat[i] / cell).toInt())) { mutableListOf() }.add(i)
        Array(stopCount) { i ->
            val cx = (lon[i] / cell).toInt(); val cy = (lat[i] / cell).toInt()
            val out = ArrayList<Int>()
            for (dx in -1..1) for (dy in -1..1) grid[key(cx + dx, cy + dy)]?.forEach { j ->
                if (j != i && metersBetween(i, j) <= FOOTPATH_METERS) { out += j; out += TripSearch.walkMinutes(metersBetween(i, j).toInt()) }
            }
            out.toIntArray()
        }
    }

    companion object {
        /** Same normalisation as research/gtfs_to_pmtt.py: word order, punctuation and station-name spellings do not matter. */
        fun normalizeName(s: String): String =
            s.lowercase().replace("hauptbahnhof", "hbf").replace("bahnhof", "bf").replace("straße", "str").replace("strasse", "str").replace("str.", "str")
                .split(Regex("[^a-zäöüß0-9]+")).filter { it.isNotEmpty() }.sorted().joinToString(" ")

        const val FOOTPATH_METERS = 300

        /** [day] names the service day of files written with `PMTT1 *` (the same trips serve several dates). */
        fun read(input: InputStream, day: LocalDate? = null): LocalTimetable = GZIPInputStream(input.buffered()).reader().use { read(it, day) }

        fun read(reader: Reader, forDay: LocalDate? = null): LocalTimetable {
            val lines = BufferedReader(reader)
            val header = lines.readLine() ?: error("empty timetable")
            require(header.startsWith("PMTT1 ")) { "not a timetable" }
            val day = header.substring(6).trim().let { if (it == "*") requireNotNull(forDay) { "day required" } else LocalDate.parse(it) }
            val ids = ArrayList<String>(); val names = ArrayList<String>(); val la = ArrayList<Double>(); val lo = ArrayList<Double>()
            val trips = ArrayList<LocalTrip>()
            lines.forEachLine { line ->
                val f = line.split('|')
                when (f[0]) {
                    "S" -> { ids += f[1]; names += f[2]; la += f[3].toDouble(); lo += f[4].toDouble() }
                    "T" -> {
                        val calls = f[6].split(',')
                        trips += LocalTrip(f[1], f[2], f[3], f[4].toIntOrNull() ?: 0, f[5],
                            IntArray(calls.size) { calls[it].substringBefore(':').toInt() }, IntArray(calls.size) { calls[it].substringAfter(':').toInt() })
                    }
                }
            }
            return LocalTimetable(day, ids.toTypedArray(), names.toTypedArray(), la.toDoubleArray(), lo.toDoubleArray(), trips)
        }
    }
}
