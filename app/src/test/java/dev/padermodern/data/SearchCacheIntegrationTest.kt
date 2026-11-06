package dev.padermodern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchCacheIntegrationTest {
    private val at = ZonedDateTime.parse("2026-10-27T10:00:42+01:00[Europe/Berlin]")
    private val a = Station(type = "stop", id = "A", name = "Alpha")
    private val b = Station(type = "stop", id = "B", name = "Beta")
    private val dep = "2026-10-27T10:00:00+01:00[Europe/Berlin]"
    private val arr = "2026-10-27T10:30:00+01:00[Europe/Berlin]"

    private fun planned(path: String? = "path") = Route(
        departureDate = dep, arrivalDate = arr,
        partialRoutes = listOf(PartialRoute(
            shortName = "R1", departureDate = dep, arrivalDate = arr, encodedPath = path,
            stations = listOf(a.copy(date = dep), b.copy(date = arr)),
        )),
        fares = listOf(Fare(name = "Adult", net = "AB")),
    )

    private fun query(changes: Int = 1) = RouteQuery(a, b, at, arrival = false, maxChanges = changes)

    @Test fun preparationWarmsPlanCacheWithoutPublishingAndButtonFetchThenRealtimeOverlay() = runTest {
        var providerLoads = 0
        val plan = planned()
        val plans = RoutePlans(load = { _, batch ->
            providerLoads++
            batch(listOf(plan))
            listOf(plan)
        }, nowMillis = { testScheduler.currentTime })
        val results = TripResultSet()
        val preparation = TripPreparation(
            scope = backgroundScope,
            loadPlace = { _, _, _ -> }, loadDestination = {},
            loadRoutes = { from, to, time, arrival, changes ->
                val q = RouteQuery(from, to, time, arrival, changes)
                plans.fetch(q) // warm route schedules only; intentionally no results.add callback
            },
            nowMillis = { testScheduler.currentTime },
        )

        preparation.prepare(TripDraft(a, b, at, arrival = false, maxChanges = 1))
        runCurrent()
        assertTrue(preparation.state.value.routesReady)
        assertTrue("prefetch must not publish visible routes", results.routes.isEmpty())
        assertNotNull(plans.cached(query()))
        assertEquals(1, providerLoads)

        val shownPlan = plans.fetch(query()) { results.add(it) }.single()
        assertNull(shownPlan.expectedDepartureDate)
        assertNull(shownPlan.partialRoutes.single().stations.first().expectedDate)
        assertEquals(plan.partialRoutes.single().encodedPath, results.routes.single().partialRoutes.single().encodedPath)

        var freshDepartureLoads = 0
        val realtime = RouteRealtime.refresh(results.routes.single()) { stationId, requestedAt ->
            freshDepartureLoads++
            assertEquals("A", stationId)
            assertEquals(ZonedDateTime.parse(dep).minusMinutes(2), requestedAt)
            val providerRun = PartialRoute(
                shortName = "R1", departureDate = dep, arrivalDate = arr,
                stations = listOf(
                    a.copy(date = dep, expectedDate = "2026-10-27T10:04:00+01:00[Europe/Berlin]", platform = "7"),
                    b.copy(date = arr, expectedDate = "2026-10-27T10:36:00+01:00[Europe/Berlin]"),
                ),
            )
            listOf(Departure(shortName = "R1", stopId = "A", departureDate = dep,
                expectedDepartureDate = "2026-10-27T10:04:00+01:00[Europe/Berlin]",
                route = Route(partialRoutes = listOf(providerRun))))
        }
        assertTrue(realtime.verified)
        results.update(realtime)
        assertEquals("2026-10-27T10:04:00+01:00[Europe/Berlin]", results.routes.single().expectedDepartureDate)
        assertEquals("2026-10-27T10:36:00+01:00[Europe/Berlin]", results.routes.single().expectedArrivalDate)
        assertEquals(1, freshDepartureLoads)
    }

    @Test fun repeatedSameQueryWithinRefreshWindowCachesGraphButRefreshesDepartures() = runTest {
        var graphProviderRequests = 0
        var departureRequests = 0
        val provider: suspend (RouteQuery, (List<Route>) -> Unit) -> List<Route> = { _, batch ->
            graphProviderRequests++
            kotlinx.coroutines.delay(1_000)
            val routes = listOf(planned())
            batch(routes)
            routes
        }
        val plans = RoutePlans(load = provider, nowMillis = { testScheduler.currentTime })
        val results = TripResultSet()
        val q = query()
        val cachedStart = testScheduler.currentTime
        repeat(2) {
            plans.fetch(q) { results.add(it) }
            val fresh = RouteRealtime.refresh(results.routes.single()) { _, _ ->
                departureRequests++
                val updatedRun = planned().partialRoutes.single().copy(stations = listOf(
                    a.copy(date = dep, expectedDate = "2026-10-27T10:02:00+01:00[Europe/Berlin]"),
                    b.copy(date = arr, expectedDate = "2026-10-27T10:32:00+01:00[Europe/Berlin]"),
                ))
                listOf(Departure(shortName = "R1", stopId = "A", departureDate = dep, route = Route(partialRoutes = listOf(updatedRun))))
            }
            results.update(fresh)
        }
        val cachedGraphRequests = graphProviderRequests
        val cachedMs = testScheduler.currentTime - cachedStart
        assertEquals(1, graphProviderRequests)
        assertEquals(2, departureRequests)
        val baselineStart = testScheduler.currentTime
        graphProviderRequests = 0
        repeat(2) { provider(q) {} }
        val baselineGraphRequests = graphProviderRequests
        val baselineMs = testScheduler.currentTime - baselineStart
        assertEquals(2, baselineGraphRequests)
        assertEquals(2_000L, baselineMs)
        assertEquals(1_000L, cachedMs)
        println("BENCHMARK simulated same-query-runs=2 measuredBaselineGraphRequests=$baselineGraphRequests cachedGraphRequests=$cachedGraphRequests freshDepartureRequests=$departureRequests baselineMs=$baselineMs cachedMs=$cachedMs (not device benchmark)")
    }

    @Test fun routePlanTtlExpiresAndStaleSoftFailureKeepsPlanAndExposesWarningError() = runTest {
        var now = 0L
        var calls = 0
        val plans = RoutePlans(load = { _, _ ->
            calls++
            if (calls == 1) listOf(planned()) else error("provider temporarily unavailable")
        }, nowMillis = { now })
        val q = query()
        val results = TripResultSet()
        results.add(plans.fetch(q))
        now = SearchPolicy.SCHEDULE_REFRESH_MILLIS
        var staleBatchDelivered = false
        val warning = runCatching { plans.fetch(q) { results.add(it); staleBatchDelivered = true } }.exceptionOrNull()
        assertTrue(staleBatchDelivered)
        assertEquals("provider temporarily unavailable", warning?.message)
        assertNotNull("soft failure leaves reusable plan cached", plans.cached(q))
        assertEquals("planned schedule remains visible", dep, results.routes.single().departureDate)

        now = SearchPolicy.SCHEDULE_TTL_MILLIS
        assertNull(plans.cached(q))
        assertEquals(2, calls)
    }

    @Test fun departureRequestsCoalesceSameBoardAcrossRouteLegConsumers() = runTest {
        var loads = 0
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<Departure>>()
        val requests = DepartureRequests(backgroundScope, { stationId, _ ->
            assertEquals("A", stationId)
            loads++
            entered.complete(Unit)
            release.await()
        }, nowMillis = { testScheduler.currentTime })
        val firstLeg = async { requests.get("A", at, fresh = true) }
        entered.await()
        val secondRoute = async { requests.get("A", at.plusSeconds(10), fresh = true) }
        runCurrent()
        release.complete(listOf(Departure(shortName = "R1")))
        assertEquals(listOf(Departure(shortName = "R1")), firstLeg.await())
        assertEquals(firstLeg.await(), secondRoute.await())
        assertEquals(1, loads)
    }

    @Test fun planRefreshBoundaryAndFreshDepartureTtlAreIndependent() = runTest {
        var now = 0L
        var graphLoads = 0
        var departureLoads = 0
        val plans = RoutePlans(load = { _, _ -> graphLoads++; listOf(planned()) }, nowMillis = { now })
        val requests = DepartureRequests(backgroundScope, { _, _ -> departureLoads++; emptyList() }, nowMillis = { now })
        val q = query()
        plans.fetch(q)
        requests.get("A", at, fresh = true)
        now = SearchPolicy.SCHEDULE_REFRESH_MILLIS - 1
        plans.fetch(q)
        requests.get("A", at, fresh = false)
        assertEquals(1, graphLoads)
        assertEquals(2, departureLoads) // The 30-second board snapshot expired before the 3-minute plan refresh.
        now++
        plans.fetch(q)
        requests.get("A", at, fresh = true)
        assertEquals(2, graphLoads)
        assertEquals(3, departureLoads) // Explicit refresh never reuses a completed snapshot.
        now += SearchPolicy.SCHEDULE_TTL_MILLIS
        assertNull(plans.cached(q))
    }
}
