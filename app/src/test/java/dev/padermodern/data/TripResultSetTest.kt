package dev.padermodern.data

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class TripResultSetTest {
    private val a = Station(type = "stop", id = "A", name = "Alpha")
    private val b = Station(type = "stop", id = "B", name = "Beta")
    private val planned = "2026-10-27T10:00:00+01:00[Europe/Berlin]"
    private val delayed = "2026-10-27T10:05:00+01:00[Europe/Berlin]"

    private fun route(
        geometry: String? = null,
        fare: Fare? = null,
        expected: String? = null,
        canceled: Boolean = false,
        impossible: Boolean = false,
    ) = Route(
        partialRoutes = listOf(PartialRoute(
            shortName = "R1", departureDate = planned, arrivalDate = "2026-10-27T10:30:00+01:00[Europe/Berlin]",
            expectedDepartureDate = expected, encodedPath = geometry,
            stations = listOf(
                a.copy(date = planned, expectedDate = expected, platform = expected?.let { "4" }, canceled = canceled),
                b.copy(date = "2026-10-27T10:30:00+01:00[Europe/Berlin]"),
            ),
        )), fares = listOfNotNull(fare), departureDate = planned,
        arrivalDate = "2026-10-27T10:30:00+01:00[Europe/Berlin]",
        expectedDepartureDate = expected, impossible = impossible,
    )

    @Test fun laterEqualKeyGeometryAndFareBatchPreservesFreshLiveOverlay() {
        val set = TripResultSet()
        set.add(listOf(route()))
        set.update(RouteRealtimeResult(route(expected = delayed), verified = true))

        val richerProviderPlan = route(geometry = "encoded", fare = Fare(name = "Adult", net = "AB"))
        set.add(listOf(richerProviderPlan))

        val shown = set.routes.single()
        assertEquals("encoded", shown.partialRoutes.single().encodedPath)
        assertEquals("Adult", shown.fares.single().name)
        assertEquals(delayed, shown.expectedDepartureDate)
        assertEquals(delayed, shown.partialRoutes.single().stations.first().expectedDate)
        assertEquals("4", shown.partialRoutes.single().stations.first().platform)
    }

    @Test fun equalQualityNewProviderDataReplacesOlderPlan() {
        val set = TripResultSet()
        val old = route(geometry = "old")
        val newer = old.copy(partialRoutes = old.partialRoutes.map { it.copy(encodedPath = "new") })
        set.add(listOf(old))
        set.add(listOf(newer))
        assertEquals("new", set.routes.single().partialRoutes.single().encodedPath)
    }

    @Test fun equivalentTimeFormatsAndMissingLegDateDoNotDropLiveOverlay() {
        val set = TripResultSet()
        val original = route()
        set.add(listOf(original))
        set.update(RouteRealtimeResult(route(expected = delayed), verified = true))
        val enriched = original.copy(partialRoutes = original.partialRoutes.map { part -> part.copy(
            departureDate = null, encodedPath = "enriched",
            stations = part.stations.map { it.copy(date = parseTime(it.date)?.iso()) },
        ) })
        set.add(listOf(enriched))
        assertEquals(delayed, set.routes.single().partialRoutes.single().expectedDepartureDate)
        assertEquals(delayed, set.routes.single().partialRoutes.single().stations.first().expectedDate)
    }

    @Test fun richProviderPlanWinsLocalWhileLiveUpdatesRemainIndependent() {
        val set = TripResultSet()
        set.add(listOf(route()))
        set.update(RouteRealtimeResult(route(expected = delayed), verified = true))
        val rich = route(geometry = "provider", fare = Fare(name = "Provider fare"))
        set.add(listOf(rich))
        val shown = set.routes.single()
        assertEquals("provider", shown.partialRoutes.single().encodedPath)
        assertEquals("Provider fare", shown.fares.single().name)
        assertEquals(delayed, shown.expectedDepartureDate)
    }

    @Test fun canceledLiveUpdateIsNotResurrectedByCachedPlanBatch() {
        val set = TripResultSet()
        set.add(listOf(route()))
        set.update(RouteRealtimeResult(route(canceled = true, impossible = true), verified = true))
        set.add(listOf(route()))
        assertTrue(set.routes.single().impossible)
        assertTrue(set.routes.single().partialRoutes.single().stations.first().canceled)
    }

    @Test fun clearRemovesPlansAndRealtimeOverlays() {
        val set = TripResultSet()
        set.add(listOf(route()))
        set.update(RouteRealtimeResult(route(expected = delayed), verified = true))
        set.clear()
        assertTrue(set.routes.isEmpty())
        set.add(listOf(route()))
        assertNull(set.routes.single().expectedDepartureDate)
    }

    @Test fun sameJourneyAllowsPreferenceChangesButRejectsChangedSearch() {
        val base = TripInputs(a, b, ZonedDateTime.parse(planned), arrival = false, maxChanges = 1)
        assertTrue(base.sameJourney(base.copy(maxChanges = 4)))
        assertFalse(base.sameJourney(base.copy(from = a.copy(id = "C"))))
        assertFalse(base.sameJourney(base.copy(to = b.copy(id = "D"))))
        assertFalse(base.sameJourney(base.copy(time = base.time!!.plusMinutes(1))))
        assertFalse(base.sameJourney(base.copy(arrival = true)))
    }

    @Test fun raisingChangeLimitAddsOnlyMissingPassesAndLoweringDoesNotRefetch() {
        assertEquals(listOf(2), additionalRoutePasses(1, 2))
        assertEquals(listOf(1, 2), additionalRoutePasses(0, 2))
        assertEquals(emptyList<Int>(), additionalRoutePasses(2, 1))
        assertEquals(emptyList<Int>(), additionalRoutePasses(2, 2))
        val set = TripResultSet()
        set.add(listOf(route()))
        additionalRoutePasses(1, 2).forEach { cap -> set.add(listOf(route().copy(changes = cap, departureDate = "another"))) }
        assertEquals(2, set.routes.size)
        assertTrue(set.routes.any { it.changes == 2 })
        assertTrue(set.routes.any { it.key == route().key })
    }
}
