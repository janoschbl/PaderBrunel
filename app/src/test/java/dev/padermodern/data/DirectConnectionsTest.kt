package dev.padermodern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DirectConnectionsTest {
    private val base = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))
    private fun at(minutes: Long) = base.plusMinutes(minutes).iso()
    private fun stop(id: String, minutes: Long? = null, expected: Long? = null, canceled: Boolean = false) = Station(
        id = id, date = minutes?.let(::at), expectedDate = expected?.let(::at), canceled = canceled,
    )
    private fun departure(stations: List<Station>, shortName: String? = "RB89", route: Route? = Route(
        partialRoutes = listOf(PartialRoute(shortName = shortName, stations = stations, encodedPath = "full-line")),
    ), canceled: Boolean = false) = Departure(shortName = shortName, route = route, canceled = canceled)
    private fun between(d: Departure, from: String = "A", to: String = "C") =
        DirectConnections.between(listOf(d), Station(id = from), Station(id = to))

    @Test fun slicesInteriorAndUsesStationRealtimeTimes() {
        val source = departure(listOf(stop("A", 0), stop("B", 10, 12), stop("C", 20, 25), stop("D", 30))).copy(
            route = Route(
                partialRoutes = listOf(PartialRoute(shortName = "RB89", stations = listOf(stop("A", 0), stop("B", 10, 12), stop("C", 20, 25), stop("D", 30)), encodedPath = "full-line")),
                fares = listOf(Fare(name = "full route")), ticketPriceAvailable = true, price = 9.5,
            ),
        )
        val result = between(source, "B", "C")
        assertEquals(1, result.size)
        val route = result.single()
        assertEquals(listOf("B", "C"), route.partialRoutes.single().stations.map { it.id })
        assertEquals(at(10), route.departureDate)
        assertEquals(at(12), route.expectedDepartureDate)
        assertEquals(at(20), route.arrivalDate)
        assertEquals(at(25), route.expectedArrivalDate)
        assertNull(route.duration)
        assertEquals(emptyList<Fare>(), route.fares)
        assertNull(route.price)
        assertEquals(false, route.ticketPriceAvailable)
        assertNull(route.partialRoutes.single().encodedPath)
    }

    @Test fun allowsDepartureRealtimeFallbackOnlyAtFirstStation() {
        val d = departure(listOf(stop("A", 0), stop("B", 10, 12), stop("C", 20))).copy(expectedDepartureDate = at(2))
        assertEquals(at(2), between(d, "A", "B").single().expectedDepartureDate)
        val withoutOriginForecast = departure(listOf(stop("A", 0), stop("B", 10), stop("C", 20))).copy(expectedDepartureDate = at(2))
        assertNull(between(withoutOriginForecast, "B", "C").single().expectedDepartureDate)
    }

    @Test fun filtersInvalidOrUnsupportedConnections() {
        val stops = listOf(stop("A", 20), stop("C", 10))
        assertTrue(between(departure(stops)).isEmpty()) // negative duration
        assertTrue(between(departure(listOf(stop("A"), stop("C", 10)))).isEmpty())
        assertTrue(between(departure(listOf(stop("A", 0), stop("C", 10, canceled = true)))).isEmpty())
        assertTrue(between(departure(listOf(stop("A", 0, canceled = true), stop("C", 10)))).isEmpty())
        assertTrue(between(departure(listOf(stop("A", 0), stop("C", 10)), route = Route(impossible = true))).isEmpty())
        assertTrue(between(departure(emptyList(), route = null)).isEmpty())
        assertTrue(between(departure(listOf(stop("A", 0), stop("C", 10)), canceled = true)).isEmpty())
        assertTrue(DirectConnections.between(listOf(departure(listOf(stop("A", 0), stop("C", 10)))), Station(), Station(id = "C")).isEmpty())
        assertTrue(DirectConnections.between(listOf(departure(listOf(stop("A", 0), stop("C", 10)))), Station(id = "A"), Station(id = "A")).isEmpty())
    }

    @Test fun repeatedStopsUseFirstOriginThenForwardDestination() {
        val result = between(departure(listOf(stop("A", 0), stop("C", 5), stop("A", 10), stop("C", 20))))
        assertEquals(listOf("A", "C"), result.single().partialRoutes.single().stations.map { it.id })
        assertEquals(at(5), result.single().arrivalDate)
    }

    @Test fun requiresValidNonnegativeRealtimeDurationAndMatchingLine() {
        assertTrue(between(departure(listOf(stop("A", 0, 30), stop("C", 20, 25)))).isEmpty())
        assertTrue(between(departure(listOf(stop("A", 0), stop("C", 20))).copy(shortName = "ICE")).isEmpty())
        assertTrue(DirectConnections.between(listOf(departure(listOf(stop("A", 0), stop("C", 20)))), Station(id = "C"), Station(id = "A")).isEmpty())
    }

    @Test fun delayedDepartureWithoutDestinationForecastIsNotInvented() {
        val d = departure(listOf(stop("A", 0), stop("C", 40))).copy(expectedDepartureDate = at(60))
        assertTrue(between(d).isEmpty())
    }
}
