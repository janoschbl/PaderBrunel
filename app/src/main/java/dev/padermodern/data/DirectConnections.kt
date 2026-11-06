package dev.padermodern.data

import java.time.Duration

/** Builds direct, no-transfer journeys from the stop sequence attached to departures. */
object DirectConnections {
    fun between(departures: List<Departure>, from: Station, to: Station): List<Route> {
        if (from.id.isNullOrBlank() || to.id.isNullOrBlank() || from.id == to.id) return emptyList()

        return departures.mapNotNull { departure ->
            if (departure.canceled) return@mapNotNull null
            val sourceRoute = departure.route ?: return@mapNotNull null
            if (sourceRoute.impossible || sourceRoute.partialRoutes.size != 1) return@mapNotNull null
            val leg = sourceRoute.partialRoutes.single()
            if (leg.isWalk || (departure.shortName != null && departure.shortName != leg.shortName)) return@mapNotNull null

            val stations = leg.stations
            val start = stations.indexOfFirst { it.id == from.id }
            if (start < 0) return@mapNotNull null
            val end = (start + 1 until stations.size).firstOrNull { stations[it].id == to.id }
                ?: return@mapNotNull null
            val board = stations[start]
            val alight = stations[end]
            if (board.canceled || alight.canceled) return@mapNotNull null

            val plannedDeparture = board.date ?: return@mapNotNull null
            val plannedArrival = alight.date ?: return@mapNotNull null
            val plannedStartTime = parseTime(plannedDeparture) ?: return@mapNotNull null
            val plannedEndTime = parseTime(plannedArrival) ?: return@mapNotNull null
            if (Duration.between(plannedStartTime, plannedEndTime).isNegative) return@mapNotNull null

            val expectedDeparture = board.expectedDate
                ?: if (start == 0) departure.expectedDepartureDate else null
            val expectedArrival = alight.expectedDate
            // A delayed departure without destination realtime data has no trustworthy
            // forecast arrival; do not turn its planned arrival into an invented realtime.
            if (expectedDeparture != null && expectedArrival == null &&
                parseTime(expectedDeparture)?.isAfter(plannedStartTime) == true) return@mapNotNull null
            val realStart = parseTime(expectedDeparture ?: plannedDeparture) ?: return@mapNotNull null
            val realEnd = parseTime(expectedArrival ?: plannedArrival) ?: return@mapNotNull null
            if (Duration.between(realStart, realEnd).isNegative) return@mapNotNull null

            val slicedLeg = leg.copy(
                stations = stations.subList(start, end + 1),
                encodedPath = null,
                departureDate = plannedDeparture,
                arrivalDate = plannedArrival,
                expectedDepartureDate = expectedDeparture,
                expectedArrivalDate = expectedArrival,
            )
            Route(
                partialRoutes = listOf(slicedLeg),
                changes = 0,
                departureDate = plannedDeparture,
                arrivalDate = plannedArrival,
                expectedDepartureDate = expectedDeparture,
                expectedArrivalDate = expectedArrival,
            )
        }
    }
}
