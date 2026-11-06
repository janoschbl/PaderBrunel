package dev.padermodern.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.ZonedDateTime

data class RouteRealtimeResult(val route: Route, val verified: Boolean, val error: String? = null)

object RouteRealtime {
    private data class LegResult(val leg: PartialRoute, val verified: Boolean, val canceled: Boolean = false, val error: String? = null)

    suspend fun refresh(
        route: Route,
        loadDepartures: suspend (String, ZonedDateTime) -> List<Departure> = { id, at -> SrsApi.departures(id, at, fresh = true) },
    ): RouteRealtimeResult = coroutineScope {
        val results = route.partialRoutes.map { leg -> async {
            if (leg.isWalk) LegResult(clearForecast(leg), true) else refreshLeg(leg, loadDepartures)
        } }.awaitAll()
        val refreshed = refreshWalkTimes(route.copy(
            partialRoutes = results.map { it.leg },
            expectedDepartureDate = null, expectedArrivalDate = null,
            impossible = route.impossible || results.any { it.canceled },
        ))
        RouteRealtimeResult(refreshed, results.all { it.verified }, results.firstNotNullOfOrNull { it.error })
    }

    private suspend fun refreshLeg(leg: PartialRoute, load: suspend (String, ZonedDateTime) -> List<Departure>): LegResult {
        val clean = clearForecast(leg)
        val id = leg.stations.firstOrNull()?.id ?: return LegResult(clean, false)
        val planned = parseTime(leg.departureDate ?: leg.stations.firstOrNull()?.date) ?: return LegResult(clean, false)
        if (leg.shortName == null) return LegResult(clean, false)
        return try {
            val match = load(id, planned.minusMinutes(2)).firstOrNull {
                it.shortName == leg.shortName && parseTime(it.departureDate)?.isEqual(planned) == true && (it.stopId == null || it.stopId == id)
            } ?: return LegResult(clean, false)
            if (match.canceled) return LegResult(clean.copy(stations = clean.stations.mapIndexed { i, s -> s.copy(canceled = i == 0) }), true, true)
            val destination = leg.stations.lastOrNull()?.id
            var slice: List<Station>? = null
            var matchedLeg: PartialRoute? = null
            for (provider in match.route?.partialRoutes.orEmpty().filterNot { it.isWalk }) {
                if (provider.shortName != leg.shortName) continue
                val start = provider.stations.indexOfFirst { it.id == id && parseTime(it.date ?: provider.departureDate)?.isEqual(planned) == true }
                if (start < 0) continue
                val end = (start + 1 until provider.stations.size).firstOrNull { provider.stations[it].id == destination } ?: continue
                slice = provider.stations.subList(start, end + 1)
                matchedLeg = provider
                break
            }
            if (slice == null) return LegResult(clean.copy(expectedDepartureDate = match.expectedDepartureDate), false)
            val stops = slice.associateBy { it.id }
            val updated = clean.stations.map { s -> stops[s.id]?.let { fresh ->
                s.copy(expectedDate = fresh.expectedDate, platform = fresh.platform, canceled = fresh.canceled)
            } ?: s }
            val dep = updated.firstOrNull()?.expectedDate ?: match.expectedDepartureDate
            val alighting = updated.lastOrNull()
            val arr = alighting?.expectedDate ?: matchedLeg?.takeIf {
                it.stations.lastOrNull()?.id == destination && parseTime(it.arrivalDate)?.isEqual(parseTime(leg.arrivalDate) ?: planned) == true
            }?.expectedArrivalDate
            val canceled = updated.firstOrNull()?.canceled == true || alighting?.canceled == true
            val arrivalUnknown = parseTime(dep)?.isAfter(planned) == true && arr == null
            LegResult(clean.copy(stations = updated, expectedDepartureDate = dep, expectedArrivalDate = arr),
                canceled || (!arrivalUnknown && clean.stations.all { it.id in stops }), canceled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LegResult(clean, false, error = e.message ?: "Live-Abfrage fehlgeschlagen")
        }
    }

    private fun clearForecast(leg: PartialRoute) = leg.copy(
        expectedDepartureDate = null, expectedArrivalDate = null,
        stations = leg.stations.map { it.copy(expectedDate = null, platform = null, canceled = false) },
    )

    private fun walkDuration(leg: PartialRoute): Long {
        val start = parseTime(leg.departureDate)
        val end = parseTime(leg.arrivalDate)
        return if (start != null && end != null) Duration.between(start, end).toSeconds().coerceAtLeast(0) else leg.walkMinutes * 60L
    }

    private fun refreshWalkTimes(route: Route): Route {
        val parts = route.partialRoutes.toMutableList()
        fun setWalk(index: Int, start: ZonedDateTime?, end: ZonedDateTime?) {
            val leg = parts[index]
            val plannedStart = parseTime(leg.departureDate)
            val shift = if (plannedStart != null && start != null) Duration.between(plannedStart, start) else null
            parts[index] = leg.copy(expectedDepartureDate = start?.iso(), expectedArrivalDate = end?.iso(),
                stations = leg.stations.map { s -> s.copy(expectedDate = shift?.let { parseTime(s.date)?.plus(it)?.iso() }) })
        }
        // Prefix walks are anchored backwards from the first ride, all other walks forwards.
        val firstRide = parts.indexOfFirst { !it.isWalk }
        if (firstRide < 0) return route
        var next = parseTime(parts[firstRide].expectedDepartureDate)
        for (i in firstRide - 1 downTo 0) {
            val start = next?.minusSeconds(walkDuration(parts[i]))
            setWalk(i, start, next); next = start
        }
        for (i in firstRide + 1 until parts.size) if (parts[i].isWalk) {
            val start = parseTime(parts[i - 1].expectedArrivalDate)
            setWalk(i, start, start?.plusSeconds(walkDuration(parts[i])))
        }
        return route.copy(partialRoutes = parts,
            expectedDepartureDate = parts.first().expectedDepartureDate,
            expectedArrivalDate = parts.last().expectedArrivalDate)
    }
}
