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
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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
    /**
     * One search from the real start to the real destination, positions included, with the walks to and from the
     * stops planned by the router. Null or empty when the source cannot do that; the search then pairs nearby stops.
     */
    suspend fun wholeTrip(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route>? = null
}

class PartialTripSearchException(message: String, cause: Throwable) : Exception(message, cause)

object TripSearch {
    private const val WALK_M_PER_MIN = 80.0
    private const val WALK_DETOUR = 1.25
    private const val WALK_WEIGHT = 1.5
    private const val MAX_VARIANTS = 4
    private const val DIRECT_EXTRA = 3
    private const val CHANGE_EXTRA = 1
    /** The list opens with at least this many connections, so a trip with few variants still fills the screen. */
    const val MIN_ROWS = 8
    private const val VARIANT_SLACK_MIN = 45.0
    /** Trains shown in the "more trains" mode besides the requested limit, one connection each. */
    private const val TRAINS_EXTRA = 5
    /** Ways to a train leaving this much earlier than its best only add waiting. */
    private const val FEEDER_SPREAD_MIN = 20L
    /** Two ways leaving within this many minutes of each other are one choice, unless they ride other lines. */
    private const val SAME_LEAVE_MIN = 3L

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
        rules: SearchRules = SearchRules(),
        source: TripSearchSource = object : TripSearchSource {
            override suspend fun endpoints(place: Station, limit: Int) = this@TripSearch.endpoints(place, limit)
            override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean) = SrsApi.departures(stationId, at, fresh)
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int) = SrsApi.routesBetween(from, to, at, arrival, maxChanges, rules)
            override suspend fun wholeTrip(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int) =
                BackendApi.routesBetween(from, to, at, arrival, maxChanges, rules)
        },
        computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): List<Route> =
        coroutineScope {
            // Pairing up to four nearby stops and gluing our own walks on missed every stop outside the four and
            // planned each pair without the traveller's minimum change. The router does the walks itself.
            val whole = try {
                source.wholeTrip(from, to, at, arrival, maxChanges)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Pm.note("whole trip failed: ${e.message}")
                null
            }
            if (!whole.isNullOrEmpty()) return@coroutineScope whole.map { it.slim() }.distinctRoutes().also(onBatch)
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

    private fun meters(a: Station, b: Station): Double? {
        if ((a.latitude == 0.0 && a.longitude == 0.0) || (b.latitude == 0.0 && b.longitude == 0.0)) return null
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * Minutes for one walk that replaces the walk to [via] plus the provider's own [provider] walks from there. A
     * provider route that starts at a bus stop often walks on to the station; the traveller goes there directly,
     * so the walk is the direct distance, never longer than the detour.
     */
    private fun mergedWalk(a: Station, b: Station?, ownMin: Int, provider: List<PartialRoute>): Int {
        val detour = ownMin + provider.sumOf { it.walkMinutes }
        val direct = b?.let { meters(a, it) }?.let { walkMinutes(it.roundToInt()) } ?: return detour
        return direct.coerceAtMost(detour)
    }

    internal fun withWalks(r: Route, o: Endpoint, d: Endpoint, from: Station, to: Station): Route {
        if (o.walkMin == 0 && d.walkMin == 0) return r
        var parts = r.partialRoutes
        var dep = r.departureDate
        var arr = r.arrivalDate
        var expDep = r.expectedDepartureDate
        var expArr = r.expectedArrivalDate
        if (o.walkMin > 0) {
            // provider walks before the first ride merge into ours, so the list never shows two walks in a row
            val lead = parts.takeWhile { it.isWalk }
            val ride = parts.getOrNull(lead.size)
            val anchor = ride?.departureDate ?: r.departureDate
            parseTime(anchor)?.let { t ->
                val minutes = if (lead.isEmpty() || ride == null) o.walkMin else mergedWalk(from, ride.stations.firstOrNull(), o.walkMin, lead)
                val target = ride?.stations?.firstOrNull()?.takeIf { lead.isNotEmpty() } ?: o.stop
                val start = t.minusMinutes(minutes.toLong())
                parts = listOf(walkLeg(from, target, start, minutes)) + parts.drop(lead.size)
                dep = start.iso()
                val rideExp = if (lead.isEmpty()) expDep else ride?.expectedDepartureDate
                expDep = rideExp?.let(::parseTime)?.minusMinutes(minutes.toLong())?.iso()
            }
        }
        if (d.walkMin > 0) {
            val tail = parts.takeLastWhile { it.isWalk }
            val ride = parts.getOrNull(parts.size - tail.size - 1)
            val anchor = if (tail.isNotEmpty()) ride?.arrivalDate else r.arrivalDate
            parseTime(anchor)?.let { t ->
                val minutes = if (tail.isEmpty() || ride == null) d.walkMin else mergedWalk(to, ride.stations.lastOrNull(), d.walkMin, tail)
                val source = ride?.stations?.lastOrNull()?.takeIf { tail.isNotEmpty() } ?: d.stop
                parts = parts.dropLast(tail.size) + walkLeg(source, to, t, minutes)
                arr = t.plusMinutes(minutes.toLong()).iso()
                val rideExp = if (tail.isEmpty()) expArr else ride?.expectedArrivalDate
                expArr = rideExp?.let(::parseTime)?.plusMinutes(minutes.toLong())?.iso()
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

        /**
         * True when [other] only adds waiting: this one leaves later or changes less, and arrives no later. Extra
         * walking here counts only beyond the minutes it lets the traveller leave later. Onward buses after the same
         * train keep their place (same departure, later arrival): they are the spare choice when the first is missed.
         */
        fun dominates(other: Scored): Boolean {
            if (dep < other.dep || arr > other.arr || changes > other.changes) return false
            if (walk > other.walk + Duration.between(other.dep, dep).toMinutes()) return false
            return dep > other.dep || changes < other.changes
        }

        /**
         * True when this one leaves at most a few minutes before [other] but arrives clearly earlier, with no more
         * changes and not much more walking: "one minute earlier, there a quarter hour sooner, one change less".
         */
        fun clearlyFaster(other: Scored): Boolean {
            val gain = Duration.between(arr, other.arr).toMinutes()
            if (gain < 3 || changes > other.changes || walk > other.walk + 10) return false
            return Duration.between(dep, other.dep).toMinutes() <= minOf(5L, gain / 3)
        }

        /** A change more without leaving later or arriving earlier than [other]. */
        fun changesForNothing(other: Scored): Boolean =
            changes > other.changes && !other.dep.isBefore(dep.minusMinutes(2)) && !other.arr.isAfter(arr)

        fun beatenBy(other: Scored): Boolean =
            other !== this && (other.dominates(this) || other.clearlyFaster(this) || changesForNothing(other))
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
            .let { all ->
                // a night spent waiting is no connection: at most twice the quickest trip, and an hour more anyway
                val quickest = all.minOfOrNull { Duration.between(it.dep, it.arr).toMinutes() } ?: 0
                all.filter { Duration.between(it.dep, it.arr).toMinutes() <= maxOf(quickest * 2, quickest + 60) }
            }
        // "walk, wait 18 min, one stop by bus, wait 17 min" loses to the bus that leaves later and arrives as early.
        // A train stays only when its best connection is not beaten that way by another train's.
        val front = scored.filterNotTo(HashSet()) { s -> scored.any { o -> s.beatenBy(o) } }
        val groups = scored.groupBy { it.trainKey }.values.mapNotNull { all ->
            val primary = all.filter { it in front }.minByOrNull { it.cost } ?: return@mapNotNull null
            if (!prefs.variety) return@mapNotNull listOf(primary)
            // within a train, earlier feeders and later onward buses are the variety; against other trains they
            // must hold their own
            variants(primary, all.filter { v -> v === primary || scored.none { o -> o.trainKey != v.trainKey && v.beatenBy(o) } })
        }
        fun at(g: List<Scored>) = if (window.byArrival) g.minOf { it.arr } else g.minOf { it.dep }
        // Direct rides have no variants, so a handful of them is one choice list; connections with a change show
        // the nearest trains, each with its variants.
        fun pick(gs: List<List<Scored>>, extra: Int, minRows: Int = 0): List<List<Scored>> {
            val (far, near) = gs.partition { if (window.byArrival) at(it) <= window.anchor else at(it) >= window.anchor }
            val ordered = if (window.byArrival) far.sortedByDescending { at(it) } else far.sortedBy { at(it) }
            val picked = ArrayList<List<Scored>>(); var rows = 0
            for (g in ordered) {
                if (picked.size >= window.limit + extra && rows >= minRows) break
                picked += g; rows += g.size
            }
            return picked + (if (window.byArrival) near.sortedBy { at(it) } else near.sortedByDescending { at(it) }).take(window.nearLimit)
        }
        val (directs, changes) = groups.partition { g -> g.all { it.direct } }
        val directPicked = pick(directs, DIRECT_EXTRA)
        val kept = directPicked + pick(changes, if (prefs.variety) CHANGE_EXTRA else TRAINS_EXTRA, (MIN_ROWS - directPicked.sumOf { it.size }).coerceAtLeast(0))
        return kept.flatten().sortedWith(compareBy({ it.dep }, { it.arr })).map { it.route }
    }

    /**
     * Up to [MAX_VARIANTS] ways to take the train of [primary]: other feeders first, each leaving at its own time so
     * the traveller can pick when to set off, then other onward buses. A way that changes more without leaving later
     * is never offered, nor one that leaves more than [FEEDER_SPREAD_MIN] before the best.
     */
    private fun variants(primary: Scored, all: List<Scored>): List<Scored> {
        fun minutes(a: ZonedDateTime, b: ZonedDateTime) = kotlin.math.abs(Duration.between(a, b).toMinutes())
        val useful = all.filter { v ->
            v === primary || (
                v.cost <= primary.cost + VARIANT_SLACK_MIN &&
                    !v.dep.isBefore(primary.dep.minusMinutes(FEEDER_SPREAD_MIN)) &&
                    all.none { w -> v.changesForNothing(w) }
                )
        }.sortedWith(compareByDescending<Scored> { it.dep }.thenBy { it.cost })
        val picked = arrayListOf(primary)
        // first pass: a new time to leave; second pass: same time, a different onward bus
        for (newLeave in listOf(true, false)) for (v in useful) {
            if (picked.size >= MAX_VARIANTS) break
            if (v in picked) continue
            val clash = picked.any { p ->
                minutes(p.dep, v.dep) < SAME_LEAVE_MIN && (newLeave || p.chain == v.chain)
            }
            if (!clash) picked += v
        }
        return picked
    }

    fun window(at: ZonedDateTime, arrival: Boolean, spanMinutes: Long = 180): SearchWindow =
        if (arrival) SearchWindow(at.minusMinutes(spanMinutes), at.plusMinutes(45), true, at)
        else SearchWindow(at.minusMinutes(1), at.plusMinutes(spanMinutes), false, at)

    fun minutesBetween(a: ZonedDateTime, b: ZonedDateTime) = Duration.between(a, b).toMinutes()
}
