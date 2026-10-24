package dev.padermodern.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.ceil

data class Endpoint(val stop: Station, val walkMin: Int)

data class SearchWindow(
    val from: ZonedDateTime,
    val to: ZonedDateTime,
    val byArrival: Boolean,
    val anchor: ZonedDateTime,
    val limit: Int = 1,
    val nearLimit: Int = 1,
)

object TripSearch {
    private const val WALK_M_PER_MIN = 80.0
    private const val WALK_DETOUR = 1.25
    private const val WALK_WEIGHT = 1.5
    private const val MAX_VARIANTS = 4
    private const val VARIANT_SLACK_MIN = 45.0

    private val nearbyCache = HashMap<String, List<Station>>()

    fun walkMinutes(meters: Int): Int = ceil(meters * WALK_DETOUR / WALK_M_PER_MIN).toInt().coerceAtLeast(1)

    suspend fun endpoints(place: Station, limit: Int): List<Endpoint> {
        if (place.isRealStop) return listOf(Endpoint(place, 0))
        val near = synchronized(nearbyCache) { nearbyCache["${place.latitude}:${place.longitude}"] }
            ?: SrsApi.nearbyStations(place.latitude, place.longitude, 1200)
                .filter { it.isRealStop }
                .distinctBy { it.name }
                .sortedBy { it.distance }
                .also { synchronized(nearbyCache) { nearbyCache["${place.latitude}:${place.longitude}"] = it } }
        // no stop within walking range, let the server resolve the coordinates
        if (near.isEmpty()) return listOf(Endpoint(place, 0))
        return near.take(limit).map { Endpoint(it, walkMinutes(it.distance)) }
    }

    suspend fun fetch(
        from: Station,
        to: Station,
        at: ZonedDateTime,
        arrival: Boolean,
        maxChanges: Int,
        onBatch: (List<Route>) -> Unit = {},
    ): List<Route> =
        coroutineScope {
            val (os, ds) = listOf(
                async { endpoints(from, if (to.isRealStop) 7 else 3) },
                async { endpoints(to, if (from.isRealStop) 7 else 3) },
            ).awaitAll()
            val pairs = os.flatMap { o -> ds.map { d -> o to d } }
                .filter { (o, d) -> o.stop.id == null || o.stop.id != d.stop.id }
                .sortedBy { (o, d) -> o.walkMin + d.walkMin }
                .take(8)
            val results = pairs.map { (o, d) ->
                async {
                    runCatching {
                        val t = if (arrival) at.minusMinutes(d.walkMin.toLong()) else at.plusMinutes(o.walkMin.toLong())
                        SrsApi.routesBetween(o.stop, d.stop, t, arrival, maxChanges).map { withWalks(it, o, d, from, to).slim() }
                            // show each stop pair as soon as it returns
                            .also(onBatch)
                    }
                }
            }.awaitAll()
            results.firstOrNull { it.isFailure && results.all { r -> r.isFailure } }?.let { throw it.exceptionOrNull()!! }
            results.flatMap { it.getOrDefault(emptyList()) }
        }

    private fun walkLeg(from: Station, to: Station, start: ZonedDateTime, minutes: Int): PartialRoute {
        val end = start.plusMinutes(minutes.toLong())
        return PartialRoute(
            clazz = "walkway",
            type = 100,
            transferTime = minutes,
            stations = listOf(from.copy(date = start.iso()), to.copy(date = end.iso())),
            departureDate = start.iso(),
            arrivalDate = end.iso(),
        )
    }

    private fun withWalks(r: Route, o: Endpoint, d: Endpoint, from: Station, to: Station): Route {
        if (o.walkMin == 0 && d.walkMin == 0) return r
        var parts = r.partialRoutes
        var dep = r.departureDate
        var arr = r.arrivalDate
        var expDep = r.expectedDepartureDate
        var expArr = r.expectedArrivalDate
        if (o.walkMin > 0) {
            parseTime(r.departureDate)?.let { t ->
                val start = t.minusMinutes(o.walkMin.toLong())
                parts = listOf(walkLeg(from, o.stop, start, o.walkMin)) + parts
                dep = start.iso()
                expDep = expDep?.let { parseTime(it)?.minusMinutes(o.walkMin.toLong())?.iso() }
            }
        }
        if (d.walkMin > 0) {
            parseTime(r.arrivalDate)?.let { t ->
                parts = parts + walkLeg(d.stop, to, t, d.walkMin)
                arr = t.plusMinutes(d.walkMin.toLong()).iso()
                expArr = expArr?.let { parseTime(it)?.plusMinutes(d.walkMin.toLong())?.iso() }
            }
        }
        return r.copy(
            partialRoutes = parts, departureDate = dep, arrivalDate = arr,
            expectedDepartureDate = expDep, expectedArrivalDate = expArr,
        )
    }

    private class Scored(val route: Route, val dep: ZonedDateTime, val arr: ZonedDateTime, prefs: TripPrefs) {
        val changes = route.changes
        val buffer: Long? = route.rides.zipWithNext { a, b ->
            val end = parseTime(a.expectedArrivalDate ?: a.arrivalDate)
            val start = parseTime(b.expectedDepartureDate ?: b.departureDate)
            if (end != null && start != null) Duration.between(end, start).toMinutes() else null
        }.filterNotNull().minOrNull()
        val walk = route.partialRoutes.sumOf { it.walkMinutes }
        val firstLine = route.rides.firstOrNull()?.shortName
        val trainKey = route.trainKey
        val cost: Double = Duration.between(dep, arr).toMinutes() + walk * (WALK_WEIGHT - 1) +
            changes * when (prefs.style) { RouteStyle.Fastest -> 0; RouteStyle.Balanced -> 5; RouteStyle.Relaxed -> 15 }
    }

    private val Route.trainKey: String
        get() = rides.lastOrNull().let { "${it?.shortName}@${it?.departureDate}" }

    fun fastest(routes: List<Route>): Set<String> =
        routes.groupBy { it.trainKey }.values.mapNotNull { g ->
            g.minByOrNull { r ->
                val d = parseTime(r.realDeparture); val a = parseTime(r.realArrival)
                if (d != null && a != null) Duration.between(d, a).toMinutes() else Long.MAX_VALUE
            }?.key
        }.toSet()

    fun rank(routes: List<Route>, window: SearchWindow, prefs: TripPrefs = TripPrefs()): List<Route> {
        val scored = routes
            .filterNot { it.impossible }
            .distinctBy { it.key }
            .mapNotNull { r ->
                // fix: rank by realtime, delayed trains were dropped from the window
                val d = parseTime(r.realDeparture) ?: return@mapNotNull null
                val a = parseTime(r.realArrival) ?: return@mapNotNull null
                Scored(r, d, a, prefs)
            }
            .filter { s -> s.changes <= prefs.maxChanges && (s.buffer ?: Long.MAX_VALUE) >= prefs.minTransferMin }
            .filter { s ->
                val t = if (window.byArrival) s.arr else s.dep
                t >= window.from && t <= window.to
            }
        val groups = scored.groupBy { it.trainKey }.values.map { all ->
            val quickest = all.minOf { it.cost }
            all.groupBy { it.firstLine }.values.map { line -> line.minBy { it.cost } }
                .filter { it.cost <= quickest + VARIANT_SLACK_MIN }
                .sortedBy { it.cost }
                .take(MAX_VARIANTS)
        }
        fun at(g: List<Scored>) = if (window.byArrival) g.minOf { it.arr } else g.minOf { it.dep }
        val (far, near) = groups.partition { if (window.byArrival) at(it) <= window.anchor else at(it) >= window.anchor }
        val kept = (if (window.byArrival) far.sortedByDescending { at(it) } else far.sortedBy { at(it) }).take(window.limit) +
            (if (window.byArrival) near.sortedBy { at(it) } else near.sortedByDescending { at(it) }).take(window.nearLimit)
        return kept.flatten().sortedWith(compareBy({ it.dep }, { it.arr })).map { it.route }
    }

    fun window(at: ZonedDateTime, arrival: Boolean, spanMinutes: Long = 180): SearchWindow =
        if (arrival) SearchWindow(at.minusMinutes(spanMinutes), at.plusMinutes(45), true, at)
        else SearchWindow(at.minusMinutes(1), at.plusMinutes(spanMinutes), false, at)

    fun minutesBetween(a: ZonedDateTime, b: ZonedDateTime) = Duration.between(a, b).toMinutes()
}
