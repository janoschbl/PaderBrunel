package dev.padermodern.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class RoutePlansDiskTest {
    private val a = Station(type = "stop", id = "A", name = "Alpha")
    private val b = Station(type = "stop", id = "B", name = "Beta")
    private val at = ZonedDateTime.parse("2026-11-03T10:00:40+01:00[Europe/Berlin]")
    private fun route(dep: String = "10:05") = Route(
        departureDate = "2026-11-03T$dep:00+0100", arrivalDate = "2026-11-03T10:30:00+0100",
        partialRoutes = listOf(PartialRoute(shortName = "R1", stations = listOf(a.copy(date = "x"), b.copy(date = "y")))),
    )

    private class MemStore : PlanStore {
        val rows = mutableListOf<Triple<String, Long, StoredPlan>>()
        var pruned: Pair<Long, Long>? = null
        override fun load(base: String, atMillis: Long, behindMillis: Long, aheadMillis: Long) =
            rows.filter { it.first == base && it.second in (atMillis - behindMillis)..(atMillis + aheadMillis) }
                .minByOrNull { kotlin.math.abs(it.second - atMillis) }?.third
        override fun save(base: String, queryAt: Long, storedAt: Long, routes: List<Route>) {
            rows.removeAll { it.first == base && it.second == queryAt }
            rows += Triple(base, queryAt, StoredPlan(routes, storedAt, queryAt))
        }
        override fun prune(beforeMillis: Long, storedBeforeMillis: Long) { pruned = beforeMillis to storedBeforeMillis }
    }

    private val wall = at.toInstant().toEpochMilli()

    @Test fun completedSearchIsWrittenAndSurvivesRestart() = runTest {
        val store = MemStore()
        RoutePlans(load = { _, _ -> listOf(route()) }, store = store, wallMillis = { wall }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
            .fetch(RouteQuery(a, b, at, false, 1))
        assertEquals(1, store.rows.size)

        // new process: empty memory, same disk
        var provider = 0
        var firstBatch: List<Route>? = null
        val reborn = RoutePlans(load = { _, _ -> provider++; listOf(route()) }, store = store, wallMillis = { wall + 60_000 }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
        val result = reborn.fetch(RouteQuery(a, b, at, false, 1)) { if (firstBatch == null) firstBatch = it }
        assertEquals(1, firstBatch!!.size)
        assertEquals("fresh disk entry needs no provider call", 0, provider)
        assertEquals(1, result.size)
    }

    @Test fun oldDiskEntryIsShownImmediatelyThenRefreshed() = runTest {
        val store = MemStore()
        RoutePlans(load = { _, _ -> listOf(route("10:05")) }, store = store, wallMillis = { wall }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
            .fetch(RouteQuery(a, b, at, false, 1))
        val batches = mutableListOf<List<Route>>()
        val later = RoutePlans(load = { _, _ -> listOf(route("10:15")) }, store = store, wallMillis = { wall + 3_600_000 }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
        later.fetch(RouteQuery(a, b, at.plusMinutes(5), false, 1)) { batches += it }
        assertEquals("stale cache answer first", "2026-11-03T10:05:00+0100", batches.first().single().departureDate)
        assertEquals("2026-11-03T10:15:00+0100", store.rows.maxBy { it.third.storedAt }.third.routes.single().departureDate)
    }

    @Test fun searchesForOtherRoutesOrFarTimesDoNotHit() = runTest {
        val store = MemStore()
        RoutePlans(load = { _, _ -> listOf(route()) }, store = store, wallMillis = { wall }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
            .fetch(RouteQuery(a, b, at, false, 1))
        var provider = 0
        val plans = RoutePlans(load = { _, _ -> provider++; emptyList() }, store = store, wallMillis = { wall }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
        plans.fetch(RouteQuery(b, a, at, false, 1))
        plans.fetch(RouteQuery(a, b, at.plusHours(2), false, 1))
        assertEquals(2, provider)
    }

    @Test fun ageLimitDropsDayOldEntries() = runTest {
        val store = MemStore()
        RoutePlans(load = { _, _ -> listOf(route()) }, store = store, wallMillis = { wall }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
            .fetch(RouteQuery(a, b, at, false, 1))
        var shown = 0
        RoutePlans(load = { _, _ -> emptyList() }, store = store, wallMillis = { wall + SearchPolicy.DISK_CACHE_MAX_AGE_MILLIS + 1 }, ioContext = kotlin.coroutines.EmptyCoroutineContext)
            .fetch(RouteQuery(a, b, at, false, 1)) { shown += it.size }
        assertEquals(0, shown)
    }

    @Test fun pruneAsksStoreToDropPastQueries() {
        val store = MemStore()
        RoutePlans(store = store, wallMillis = { wall }).prune()
        assertEquals(wall - SearchPolicy.DISK_PAST_GRACE_MILLIS, store.pruned!!.first)
    }
}
