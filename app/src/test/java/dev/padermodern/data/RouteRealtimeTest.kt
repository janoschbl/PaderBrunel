package dev.padermodern.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouteRealtimeTest {
    private fun station(id: String, time: String, expected: String? = null, canceled: Boolean = false) =
        Station(id = id, name = id, date = time, expectedDate = expected, canceled = canceled, platform = "scheduled")
    private fun leg(name: String = "4", origin: String = "A", destination: String = "B") = PartialRoute(
        shortName = name, departureDate = "2026-10-27T10:00:00+0100", arrivalDate = "2026-10-27T10:30:00+0100",
        stations = listOf(station(origin, "2026-10-27T10:00:00+0100"), station(destination, "2026-10-27T10:30:00+0100")),
    )
    private fun departure(route: Route? = null, expected: String? = null, canceled: Boolean = false) = Departure(
        shortName = "4", departureDate = "2026-10-27T10:00:00+0100", expectedDepartureDate = expected,
        canceled = canceled, route = route,
    )
    private fun providerLeg(stops: List<Station>, name: String = "4") = PartialRoute(
        shortName = name, departureDate = "2026-10-27T10:00:00+0100", arrivalDate = "2026-10-27T10:30:00+0100", stations = stops,
    )

    @Test fun overlaysExactRealtimeAndRecomputesTopLevel() = runTest {
        val original = Route(partialRoutes = listOf(leg()), departureDate = leg().departureDate, arrivalDate = leg().arrivalDate,
            expectedDepartureDate = "2026-10-27T09:58:00+0100", expectedArrivalDate = "2026-10-27T10:31:00+0100")
        val rt = providerLeg(listOf(station("A", "2026-10-27T10:00:00+0100", "2026-10-27T10:05:00+0100"), station("B", "2026-10-27T10:30:00+0100", "2026-10-27T10:36:00+0100")))
        val out = RouteRealtime.refresh(original) { _, _ -> listOf(departure(Route(partialRoutes = listOf(rt)))) }
        assertTrue(out.verified)
        assertEquals("2026-10-27T10:05:00+0100", out.route.expectedDepartureDate)
        assertEquals("2026-10-27T10:36:00+0100", out.route.expectedArrivalDate)
        assertEquals("2026-10-27T10:00:00+0100", out.route.partialRoutes.single().departureDate)
        assertNull(original.partialRoutes.single().stations.first().expectedDate)
    }

    @Test fun unknownIntermediateArrivalDoesNotFabricateAndClearsOldForecast() = runTest {
        val base = leg().copy(stations = listOf(station("A", "2026-10-27T10:00:00+0100", "2026-10-27T10:02:00+0100"), station("X", "2026-10-27T10:15:00+0100", "2026-10-27T10:20:00+0100"), station("B", "2026-10-27T10:30:00+0100", "2026-10-27T10:32:00+0100")))
        val rt = providerLeg(listOf(station("A", "2026-10-27T10:00:00+0100", "2026-10-27T10:05:00+0100"), station("B", "2026-10-27T10:30:00+0100")))
        val out = RouteRealtime.refresh(Route(partialRoutes = listOf(base))) { _, _ -> listOf(departure(Route(partialRoutes = listOf(rt)))) }
        assertFalse(out.verified)
        assertNull(out.route.partialRoutes.single().stations[1].expectedDate)
        assertNull(out.route.partialRoutes.single().stations.last().expectedDate)
        assertNull(out.route.partialRoutes.single().expectedArrivalDate)
    }

    @Test fun emptyAndNetworkErrorAreUnverifiedAndKeepBaseVisible() = runTest {
        val base = Route(partialRoutes = listOf(leg()))
        val empty = RouteRealtime.refresh(base) { _, _ -> emptyList() }
        assertFalse(empty.verified); assertEquals(base.key, empty.route.key)
        assertNull(empty.route.partialRoutes.single().expectedArrivalDate)
        val failed = RouteRealtime.refresh(base) { _, _ -> error("offline") }
        assertFalse(failed.verified); assertEquals("offline", failed.error); assertEquals(base.key, failed.route.key)
    }

    @Test fun canceledAndNegativeBoardsAndUnmatchedRemainUnverified() = runTest {
        val base = Route(partialRoutes = listOf(leg()))
        val canceled = RouteRealtime.refresh(base) { _, _ -> listOf(departure(canceled = true)) }
        assertTrue(canceled.verified); assertTrue(canceled.route.impossible)
        val wrong = RouteRealtime.refresh(base) { _, _ -> listOf(departure().copy(shortName = "9")) }
        assertFalse(wrong.verified)
        val unknown = RouteRealtime.refresh(base) { _, _ -> listOf(departure(route = null)) }
        assertFalse(unknown.verified)
    }

    @Test fun cancellationPropagatesAndMultipleLegsUseInjectedLoader() = runTest {
        val route = Route(partialRoutes = listOf(leg(), leg("5", "B", "C")))
        var calls = 0
        RouteRealtime.refresh(route) { _, _ -> calls++; listOf(departure()) }
        assertEquals(2, calls)
        try {
            RouteRealtime.refresh(route) { _, _ -> throw CancellationException("stop") }
            fail("cancellation should propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun walkingPrefixAndSuffixShiftExpectedTopTimes() = runTest {
        val walk = PartialRoute(clazz = "walkway", transferTime = 5, departureDate = "2026-10-27T09:55:00+0100", arrivalDate = "2026-10-27T10:00:00+0100")
        val tail = walk.copy(departureDate = "2026-10-27T10:30:00+0100", arrivalDate = "2026-10-27T10:35:00+0100")
        val base = Route(partialRoutes = listOf(walk, leg(), tail))
        val rt = providerLeg(listOf(station("A", "2026-10-27T10:00:00+0100", "2026-10-27T10:04:00+0100"), station("B", "2026-10-27T10:30:00+0100", "2026-10-27T10:34:00+0100")))
        val out = RouteRealtime.refresh(base) { _, _ -> listOf(departure(Route(partialRoutes = listOf(rt)))) }
        assertEquals("2026-10-27T09:59:00+0100", out.route.expectedDepartureDate)
        assertEquals("2026-10-27T10:39:00+0100", out.route.expectedArrivalDate)
        assertEquals(walk.departureDate, out.route.partialRoutes.first().departureDate)
        assertEquals(walk.arrivalDate, out.route.partialRoutes.first().arrivalDate)
        assertEquals("2026-10-27T09:59:00+0100", out.route.partialRoutes.first().expectedDepartureDate)
        assertEquals("2026-10-27T10:39:00+0100", out.route.partialRoutes.last().expectedArrivalDate)
    }

    @Test fun wholeProviderRunIsSlicedAtOrderedBoardingAndAlightingStops() = runTest {
        val route = leg().copy(stations = listOf(station("A", "2026-10-27T10:00:00+0100"), station("B", "2026-10-27T10:30:00+0100")))
        val full = providerLeg(listOf(
            station("PRE", "2026-10-27T09:40:00+0100"),
            station("A", "2026-10-27T10:00:00+0100", "2026-10-27T10:03:00+0100"),
            station("MID", "2026-10-27T10:15:00+0100", "2026-10-27T10:18:00+0100"),
            station("B", "2026-10-27T10:30:00+0100", "2026-10-27T10:35:00+0100"),
            station("POST", "2026-10-27T10:50:00+0100"),
        ))
        val out = RouteRealtime.refresh(Route(partialRoutes = listOf(route))) { _, _ -> listOf(departure(Route(partialRoutes = listOf(full)))) }
        assertTrue(out.verified)
        assertEquals("2026-10-27T10:03:00+0100", out.route.partialRoutes.single().stations.first().expectedDate)
        assertEquals("2026-10-27T10:35:00+0100", out.route.partialRoutes.single().stations[1].expectedDate)
        assertEquals("2026-10-27T10:35:00+0100", out.route.partialRoutes.single().expectedArrivalDate)
    }

    @Test fun wrongBoardStopKeepsIncomingForecastAndMatchedRunReplacesIt() = runTest {
        val base = leg().copy(expectedDepartureDate = "old", expectedArrivalDate = "old", stations = listOf(
            station("A", "2026-10-27T10:00:00+0100", "old", true),
            station("B", "2026-10-27T10:30:00+0100", "old", true),
        ))
        val wrongStop = RouteRealtime.refresh(Route(partialRoutes = listOf(base))) { _, _ -> listOf(departure().copy(stopId = "OTHER")) }
        assertFalse(wrongStop.verified)
        assertEquals("old", wrongStop.route.partialRoutes.single().stations.first().expectedDate)
        assertTrue(wrongStop.route.partialRoutes.single().stations.first().canceled)
        val fresh = providerLeg(listOf(station("A", "2026-10-27T10:00:00+0100"), station("B", "2026-10-27T10:30:00+0100")))
        val refreshed = RouteRealtime.refresh(Route(partialRoutes = listOf(base))) { _, _ -> listOf(departure(Route(partialRoutes = listOf(fresh)))) }
        assertTrue(refreshed.verified)
        assertFalse(refreshed.route.partialRoutes.single().stations.any { it.canceled })
        assertNull(refreshed.route.partialRoutes.single().stations.last().expectedDate)
    }

    @Test fun delayedDepartureWithUnknownArrivalClearsTopForecastAndIsUnverified() = runTest {
        val original = Route(partialRoutes = listOf(leg()), expectedArrivalDate = "2026-10-27T10:45:00+0100")
        val rt = providerLeg(listOf(station("A", "2026-10-27T10:00:00+0100", "2026-10-27T10:05:00+0100"), station("B", "2026-10-27T10:30:00+0100")))
        val out = RouteRealtime.refresh(original) { _, _ -> listOf(departure(Route(partialRoutes = listOf(rt)))) }
        assertFalse(out.verified)
        assertNull(out.route.expectedArrivalDate)
    }

    /** RB89 Geseke 2026-11-07: the backend said +7, the provider board knows no stop "de:05974:32585". */
    @Test fun keepsBackendForecastWhenBoardKnowsNothing() = runTest {
        val late = leg("RB89", "de:05974:32585", "de:05774:7000").let { l ->
            l.copy(expectedDepartureDate = "2026-10-27T10:07:00+0100", expectedArrivalDate = "2026-10-27T10:38:00+0100",
                stations = listOf(station("de:05974:32585", "2026-10-27T10:00:00+0100", "2026-10-27T10:07:00+0100"), station("de:05774:7000", "2026-10-27T10:30:00+0100", "2026-10-27T10:38:00+0100")))
        }
        val walk = PartialRoute(clazz = "walkway", transferTime = 8, departureDate = "2026-10-27T09:52:00+0100", arrivalDate = "2026-10-27T10:00:00+0100")
        val out = RouteRealtime.refresh(Route(partialRoutes = listOf(walk, late))) { _, _ -> emptyList() }
        assertFalse(out.verified)
        assertEquals("2026-10-27T10:07:00+0100", out.route.partialRoutes[1].expectedDepartureDate)
        assertEquals("2026-10-27T10:38:00+0100", out.route.expectedArrivalDate)
        assertEquals("2026-10-27T09:59:00+0100", out.route.expectedDepartureDate)
    }
}
