package dev.padermodern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutePlansTest {
    private val a = Station(type = "stop", id = "A", name = "Alpha")
    private val b = Station(type = "stop", id = "B", name = "Beta")
    private fun query(at: ZonedDateTime = ZonedDateTime.parse("2026-10-27T10:00:40+01:00[Europe/Berlin]"), arrival: Boolean = false, cap: Int = 2, from: Station = a) =
        RouteQuery(from, b, at, arrival, cap)
    private fun route(canceled: Boolean = false, impossible: Boolean = false) = Route(
        departureDate = "2026-10-27T10:00:00+01:00[Europe/Berlin]",
        expectedDepartureDate = "2026-10-27T10:04:00+01:00[Europe/Berlin]",
        partialRoutes = listOf(PartialRoute(
            shortName = "R1", departureDate = "10:00", expectedDepartureDate = "10:04",
            stations = listOf(a.copy(date = "10:00", expectedDate = "10:04", platform = "9", canceled = canceled), b.copy(date = "10:30", expectedDate = "10:34")),
        )), impossible = impossible,
    )

    @Test fun warmSameMinuteIsImmediateAndMakesNoProviderCall() = runTest {
        var calls = 0
        var clock = 0L
        val plans = RoutePlans(load = { _, _ -> calls++; listOf(route()) }, nowMillis = { clock })
        val q = query()
        val first = plans.fetch(q)
        clock = 10
        var observed: List<Route>? = null
        val second = plans.fetch(query(at = q.at.plusSeconds(15))) { observed = it }
        assertEquals(1, calls)
        assertEquals(first.map { it.key }, second.map { it.key })
        assertEquals(second, observed)
        assertNull(second.single().expectedDepartureDate)
    }

    @Test fun staleSnapshotEmitsBeforeSlowRevalidationAndFailureRetainsIt() = runTest {
        var clock = 0L
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<Route>>()
        val plans = RoutePlans(load = { _, _ ->
            calls++
            if (calls == 1) listOf(route()) else { started.complete(Unit); release.await() }
        }, nowMillis = { clock })
        val q = query()
        plans.fetch(q)
        clock = SearchPolicy.SCHEDULE_REFRESH_MILLIS
        val delivered = CompletableDeferred<List<Route>>()
        val work = async { runCatching { plans.fetch(q) { if (!delivered.isCompleted) delivered.complete(it) } } }
        assertNotNull(delivered.await())
        started.await()
        assertNotNull(plans.cached(q))
        release.completeExceptionally(IllegalStateException("offline"))
        assertEquals("offline", work.await().exceptionOrNull()?.message)
        assertNotNull(plans.cached(q))
    }

    @Test fun providerBatchesAreForwardedButCachedSnapshotIsScheduleOnlyAndCanceledRoutesExcluded() = runTest {
        val predicted = route()
        var batches = 0
        val plans = RoutePlans(load = { _, batch ->
            batch(listOf(predicted)); batches++
            listOf(predicted, route(canceled = true), route(impossible = true))
        })
        val returned = plans.fetch(query())
        assertEquals(3, returned.size)
        assertEquals(1, batches)
        val cached = plans.cached(query())!!.single()
        assertNull(cached.expectedDepartureDate)
        assertNull(cached.partialRoutes.single().expectedDepartureDate)
        assertNull(cached.partialRoutes.single().stations.first().expectedDate)
        assertEquals(predicted.partialRoutes.single().stations.first().date, cached.partialRoutes.single().stations.first().date)
        assertNull(cached.partialRoutes.single().stations.first().platform)
        assertFalse(cached.partialRoutes.single().stations.first().canceled)
    }

    @Test fun ttlAndQueryDimensionsDoNotCrossContaminate() = runTest {
        var now = 0L
        var calls = 0
        val plans = RoutePlans(load = { _, _ -> calls++; listOf(route()) }, nowMillis = { now })
        val q = query()
        plans.fetch(q)
        assertNull(plans.cached(query(arrival = true)))
        assertNull(plans.cached(query(cap = 1)))
        assertNull(plans.cached(query(from = a.copy(name = "Renamed"))))
        assertNull(plans.cached(query(at = q.at.withZoneSameInstant(ZoneId.of("UTC")))))
        now = SearchPolicy.SCHEDULE_TTL_MILLIS
        assertNull(plans.cached(q))
        assertEquals(1, calls)
    }

    @Test fun scheduleRefreshBoundaryAndLruCapacity() = runTest {
        var now = 0L
        var calls = 0
        val plans = RoutePlans(load = { _, _ -> calls++; listOf(route()) }, nowMillis = { now })
        plans.fetch(query())
        now = SearchPolicy.SCHEDULE_REFRESH_MILLIS - 1
        plans.fetch(query())
        assertEquals(1, calls)
        now++
        plans.fetch(query())
        assertEquals(2, calls)
        for (i in 0..48) plans.fetch(query(from = a.copy(id = "S$i")))
        assertTrue(plans.cached(query()) == null) // oldest entry evicted
    }

    @Test fun simulatedProviderBenchmarkWarmHitAvoidsOneSecondProviderAndGraphRequest() = runTest {
        var clock = 0L
        var graphRequests = 0
        val plans = RoutePlans(load = { _, _ ->
            graphRequests++
            clock += 1_000
            listOf(route())
        }, nowMillis = { clock })
        val query = query()
        val start = clock
        plans.fetch(query)
        val coldMs = clock - start
        val warmStart = clock
        plans.fetch(query)
        val warmMs = clock - warmStart
        println("BENCHMARK route-plans provider=simulated uncached=${coldMs}ms warm=${warmMs}ms graphRequests=$graphRequests")
        assertEquals(1, graphRequests)
        assertEquals(1_000L, coldMs)
        assertEquals(0L, warmMs)
    }
}
