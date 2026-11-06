package dev.padermodern.data

/** Geometry/fare enrichment and live updates have independent lifetimes. */
class TripResultSet {
    private val plans = linkedMapOf<String, Route>()
    private val realtime = mutableMapOf<String, Route>()
    // On-device answers shown until the provider has answered; they never outrank a provider route.
    private val local = linkedMapOf<String, Route>()

    val serverRoutes: List<Route> get() = plans.values.toList()

    private fun Route.signature() = rides.joinToString("/") { "${it.shortName}@${parseTime(it.departureDate)?.toInstant()}@${parseTime(it.arrivalDate)?.toInstant()}" }

    val routes: List<Route> get() = run {
        val known = plans.values.mapTo(HashSet()) { it.signature() }
        plans + local.filter { (key, r) -> key !in plans && r.signature() !in known }
    }.map { (key, plan) ->
        realtime[key]?.let { live -> overlay(plan, live) } ?: plan
    }

    val hasLocal: Boolean get() = local.isNotEmpty()

    fun clear() { plans.clear(); realtime.clear(); local.clear() }

    fun addLocal(batch: List<Route>) { batch.forEach { local[it.key] = it } }

    /** The provider search finished: its answer replaces the preview, including routes it does not confirm. */
    fun dropLocal() { local.keys.forEach { if (it !in plans) realtime.remove(it) }; local.clear() }

    fun add(batch: List<Route>) {
        batch.forEach { incoming ->
            val old = plans[incoming.key]
            if (old == null || quality(incoming) >= quality(old)) plans[incoming.key] = incoming
        }
    }

    fun update(result: RouteRealtimeResult) { realtime[result.route.key] = result.route }

    private fun quality(route: Route) = route.partialRoutes.count { it.encodedPath != null } + route.fares.size

    private fun overlay(plan: Route, live: Route): Route = plan.copy(
        impossible = live.impossible,
        expectedDepartureDate = live.expectedDepartureDate,
        expectedArrivalDate = live.expectedArrivalDate,
        partialRoutes = plan.partialRoutes.mapIndexed { index, part ->
            val current = live.partialRoutes.getOrNull(index)?.takeIf {
                it.shortName == part.shortName && it.stations.firstOrNull()?.id == part.stations.firstOrNull()?.id &&
                    it.stations.lastOrNull()?.id == part.stations.lastOrNull()?.id &&
                    sameTime(it.departureDate ?: it.stations.firstOrNull()?.date, part.departureDate ?: part.stations.firstOrNull()?.date)
            } ?: return@mapIndexed part
            part.copy(
                expectedDepartureDate = current.expectedDepartureDate,
                expectedArrivalDate = current.expectedArrivalDate,
                stations = part.stations.map { station ->
                    val updated = current.stations.firstOrNull { it.id == station.id && sameTime(it.date, station.date) }
                    if (updated == null) station else station.copy(
                        expectedDate = updated.expectedDate, platform = updated.platform, canceled = updated.canceled,
                    )
                },
            )
        },
    )

    private fun sameTime(a: String?, b: String?): Boolean =
        if (a == b) true else parseTime(a)?.let { first -> parseTime(b)?.let(first::isEqual) } == true
}

/** Preference changes rerank results; only a changed journey invalidates a submitted search. */
data class TripInputs(val from: Station?, val to: Station?, val time: java.time.ZonedDateTime?, val arrival: Boolean, val maxChanges: Int) {
    fun sameJourney(other: TripInputs): Boolean = copy(maxChanges = other.maxChanges) == other
}

fun routePasses(cap: Int): List<Int> = listOf(minOf(1, cap)) + if (cap >= 2) listOf(2) else emptyList()

fun additionalRoutePasses(previousCap: Int, cap: Int): List<Int> = routePasses(cap).filter { it > previousCap }

/** Agreement between provider and on-device results: the first three provider connections must appear locally. */
object ShadowComparison {
    data class Result(val matched: Int, val total: Int)

    private fun Route.sig() = rides.joinToString("/") { "${it.shortName}@${parseTime(it.departureDate)?.toInstant()}@${parseTime(it.arrivalDate)?.toInstant()}" } +
        "=>" + parseTime(realArrival)?.toInstant()

    fun compare(server: List<Route>, local: List<Route>, n: Int = 3): Result {
        val have = local.mapTo(HashSet()) { it.sig() }
        val firstThree = server.sortedWith(compareBy({ parseTime(it.realDeparture) }, { parseTime(it.realArrival) })).take(n)
        return Result(firstThree.count { it.sig() in have }, firstThree.size)
    }
}
