package dev.padermodern.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

interface TripSearchSource {
    suspend fun endpoints(place: Station, limit: Int): List<Endpoint>
    suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean): List<Departure>
    suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route>
}

class PartialTripSearchException(message: String, cause: Throwable) : Exception(message, cause)

object TripSearch {
    private const val WALK_M_PER_MIN = 80.0
    private const val WALK_DETOUR = 1.25
    private const val WALK_WEIGHT = 1.5
    private const val MAX_VARIANTS = 4
    private const val DIRECT_EXTRA = 3
    private const val CHANGE_EXTRA = 1
    private const val VARIANT_SLACK_MIN = 45.0

    fun walkMinutes(meters: Int): Int = ceil(meters * WALK_DETOUR / WALK_M_PER_MIN).toInt().coerceAtLeast(1)

    suspend fun endpoints(place: Station, limit: Int): List<Endpoint> {
        if (place.isRealStop) return listOf(Endpoint(place, 0))
        val near = SrsApi.nearbyStations(place.latitude, place.longitude, 1200)
            .filter { it.isRealStop }
            .distinctBy { it.name }
            .sortedBy { it.distance }
        // no stop within walking range, let the server resolve the coordinates
        if (near.isEmpty()) return listOf(Endpoint(place, 0))
        return near.take(limit).map { Endpoint(it, walkMinutes(it.distance)) }
    }

    suspend fun preparePlace(place: Station, at: ZonedDateTime, arrival: Boolean): Unit = coroutineScope {
        val origins = endpoints(place, 7)
        if (arrival) return@coroutineScope
        origins.filter { it.stop.isRealStop }.take(3).map { endpoint ->
            async {
                try {
                    SrsApi.departures(endpoint.stop.id!!, departureTime(at, false, endpoint), fresh = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // An unavailable board must not prevent preparing the other stops.
                }
            }
        }.awaitAll()
    }

    private fun departureTime(at: ZonedDateTime, arrival: Boolean, origin: Endpoint): ZonedDateTime =
        if (arrival) at.minusMinutes(180) else at.plusMinutes(origin.walkMin.toLong())

    suspend fun fetch(
        from: Station,
        to: Station,
        at: ZonedDateTime,
        arrival: Boolean,
        maxChanges: Int,
        onBatch: (List<Route>) -> Unit = {},
        includeDirect: Boolean = true,
        source: TripSearchSource = object : TripSearchSource {
            override suspend fun endpoints(place: Station, limit: Int) = this@TripSearch.endpoints(place, limit)
            override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean) = SrsApi.departures(stationId, at, fresh)
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int) = SrsApi.routesBetween(from, to, at, arrival, maxChanges)
        },
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): List<Route> =
        coroutineScope {
            val (os, ds) = listOf(
                async { source.endpoints(from, if (to.isRealStop) 7 else 3) },
                async { source.endpoints(to, if (from.isRealStop) 7 else 3) },
            ).awaitAll()
            val pairs = os.flatMap { o -> ds.map { d -> o to d } }
                .filter { (o, d) -> o.stop.id == null || o.stop.id != d.stop.id }
                .sortedBy { (o, d) -> o.walkMin + d.walkMin }
                .take(SearchPolicy.MAX_STOP_PAIRS)
            val direct = if (includeDirect && !arrival && maxChanges <= 1) async {
                os.filter { it.stop.isRealStop }.take(3).map { o ->
                    async directStop@ {
                        try {
                            // Optional local discovery must not hold up an otherwise finished provider search.
                            val departures = withTimeoutOrNull(1_500) {
                                source.departures(o.stop.id!!, departureTime(at, false, o), false)
                            } ?: return@directStop emptyList<Route>()
                            withContext(computationDispatcher) {
                                ds.flatMap { d ->
                                    DirectConnections.between(departures, o.stop, d.stop).map { withWalks(it, o, d, from, to).slim() }
                                }
                            }.also { currentCoroutineContext().ensureActive(); onBatch(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            } else null
            val results = pairs.map { (o, d) ->
                async {
                    runCatching {
                        val t = if (arrival) at.minusMinutes(d.walkMin.toLong()) else at.plusMinutes(o.walkMin.toLong())
                        source.routesBetween(o.stop, d.stop, t, arrival, maxChanges).map { withWalks(it, o, d, from, to).slim() }
                            // show each stop pair as soon as it returns
                            .also { currentCoroutineContext().ensureActive(); onBatch(it) }
                    }.onFailure { if (it is CancellationException) throw it }
                }
            }.awaitAll()
            val local = direct?.await().orEmpty()
            val failure = results.firstNotNullOfOrNull { it.exceptionOrNull() }
            if (failure != null) throw PartialTripSearchException("Einige Verbindungen konnten nicht geladen werden; die angezeigten Ergebnisse sind möglicherweise unvollständig.", failure)
            // Keep the provider's full geometry/fares in the reusable schedule, not the local slice.
            (results.flatMap { it.getOrDefault(emptyList()) } + local).distinctRoutes()
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
        // two connections with the same line sequence are the same choice for the traveller
        val chain = route.rides.joinToString(">") { it.shortName ?: it.lineName.orEmpty() }
        val direct = route.rides.size <= 1
        val trainKey = route.trainKey
        val cost: Double = Duration.between(dep, arr).toMinutes() + walk * (WALK_WEIGHT - 1) +
            changes * when (prefs.style) { RouteStyle.Fastest -> 0; RouteStyle.Balanced -> 5; RouteStyle.Relaxed -> 15 }
    }

    /**
     * The ride that defines the connection: the train when there is one, else the longest ride. Connections that
     * share it are one choice with different ways to reach or leave it (feeder or onward buses).
     */
    private fun trunk(route: Route): PartialRoute? {
        val rides = route.rides
        fun minutes(p: PartialRoute): Long {
            val d = parseTime(p.departureDate); val a = parseTime(p.arrivalDate)
            return if (d != null && a != null) Duration.between(d, a).toMinutes() else 0
        }
        return rides.filter { it.type == 1 }.maxByOrNull(::minutes) ?: rides.maxByOrNull(::minutes)
    }

    private val Route.trainKey: String
        get() = trunk(this).let { "${it?.shortName}@${it?.departureDate}" }

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
            .distinctRoutes()
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
            all.groupBy { it.chain }.values.map { line -> line.minBy { it.cost } }
                .filter { it.cost <= quickest + VARIANT_SLACK_MIN }
                .sortedBy { it.cost }
                .take(MAX_VARIANTS)
        }
        fun at(g: List<Scored>) = if (window.byArrival) g.minOf { it.arr } else g.minOf { it.dep }
        // Direct rides have no variants, so a handful of them is one choice list; connections with a change show
        // the nearest trains, each with its variants.
        fun pick(gs: List<List<Scored>>, extra: Int): List<List<Scored>> {
            val (far, near) = gs.partition { if (window.byArrival) at(it) <= window.anchor else at(it) >= window.anchor }
            return (if (window.byArrival) far.sortedByDescending { at(it) } else far.sortedBy { at(it) }).take(window.limit + extra) +
                (if (window.byArrival) near.sortedBy { at(it) } else near.sortedByDescending { at(it) }).take(window.nearLimit)
        }
        val (directs, changes) = groups.partition { g -> g.all { it.direct } }
        val kept = pick(directs, DIRECT_EXTRA) + pick(changes, CHANGE_EXTRA)
        return kept.flatten().sortedWith(compareBy({ it.dep }, { it.arr })).map { it.route }
    }

    fun window(at: ZonedDateTime, arrival: Boolean, spanMinutes: Long = 180): SearchWindow =
        if (arrival) SearchWindow(at.minusMinutes(spanMinutes), at.plusMinutes(45), true, at)
        else SearchWindow(at.minusMinutes(1), at.plusMinutes(spanMinutes), false, at)

    fun minutesBetween(a: ZonedDateTime, b: ZonedDateTime) = Duration.between(a, b).toMinutes()
}
