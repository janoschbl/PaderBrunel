package dev.padermodern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class TripPreparationTest {
    private val at = ZonedDateTime.parse("2026-10-27T12:34:56+01:00[Europe/Berlin]")
    private val a = Station(type = "stop", id = "a", name = "A")
    private val b = Station(type = "stop", id = "b", name = "B")
    private val c = Station(type = "stop", id = "c", name = "C")
    private fun draft(from: Station? = a, to: Station? = null, time: ZonedDateTime = at, changes: Int = 1) = TripDraft(from, to, time, false, changes)

    @Test fun originAloneLoadsBoardButNoRoutes() = runTest {
        var boards = 0; var routes = 0
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> boards++ }, loadRoutes = { _, _, _, _, _ -> routes++ }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft()); runCurrent()
        assertEquals(1, boards); assertEquals(0, routes); assertFalse(prep.state.value.routesReady)
        assertFalse(prep.state.value.loading)
    }

    @Test fun confirmedDestinationUsesEndpointsNotBoardAndRetainsOrigin() = runTest {
        var boards = 0; val destinationIds = mutableListOf<String>(); val routes = mutableListOf<Int>()
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> boards++ }, loadDestination = { destinationIds += it.id!! }, loadRoutes = { _, _, _, _, n -> routes += n }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft()); runCurrent(); prep.prepare(draft(to = b, changes = 2)); runCurrent()
        assertEquals(1, boards); assertEquals(listOf("b"), destinationIds); assertEquals(setOf(1, 2), routes.toSet())
    }

    @Test fun previewDestinationNeverStartsRoutesEvenWithConfirmedOrigin() = runTest {
        var endpoints = 0; var routes = 0
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> }, loadDestination = { endpoints++ }, loadRoutes = { _, _, _, _, _ -> routes++ }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft()); runCurrent(); prep.preview(b, origin = false); runCurrent()
        assertEquals(1, endpoints); assertEquals(0, routes)
    }

    @Test fun readyRouteExpiresAutomaticallyAtScheduleTtl() = runTest {
        var routes = 0
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> }, loadDestination = {}, loadRoutes = { _, _, _, _, _ -> routes++ }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft(to = b)); runCurrent()
        assertTrue(prep.state.value.routesReady)
        advanceTimeBy(SearchPolicy.SCHEDULE_TTL_MILLIS - 1); assertTrue(prep.state.value.routesReady)
        advanceTimeBy(1); runCurrent()
        assertFalse(prep.state.value.routesReady); assertEquals(1, routes)
        prep.prepare(draft(to = b)); runCurrent(); assertEquals(2, routes)
    }

    @Test fun routeVariantsStartConcurrently() = runTest {
        val entered = mutableSetOf<Int>(); val gate = CompletableDeferred<Unit>()
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> }, loadDestination = {}, loadRoutes = { _, _, _, _, n -> entered += n; gate.await() }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft(to = b, changes = 3)); runCurrent()
        assertEquals(setOf(1, 2), entered); gate.complete(Unit); runCurrent()
    }

    @Test fun failedRouteRetriesAndObsoleteCancellationDoesNotPublishReady() = runTest {
        var attempt = 0; val started = CompletableDeferred<Unit>()
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> }, loadDestination = {}, loadRoutes = { _, _, _, _, _ -> if (++attempt == 1) error("offline") else { started.complete(Unit); CompletableDeferred<Unit>().await() } }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft(to = b)); runCurrent(); assertFalse(prep.state.value.routesReady)
        prep.prepare(draft(to = b)); started.await(); prep.prepare(draft(from = null)); runCurrent()
        assertEquals(PreparationState(), prep.state.value); assertEquals(2, attempt)
    }

    @Test fun routeCacheIsReusableWhileFreshThenReloadedAfterExpiry() = runTest {
        var calls = 0
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> }, loadDestination = {}, loadRoutes = { _, _, _, _, _ -> calls++ }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft(to = b)); runCurrent(); prep.prepare(draft(to = b)); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(SearchPolicy.SCHEDULE_TTL_MILLIS); runCurrent()
        prep.prepare(draft(to = b)); runCurrent(); assertEquals(2, calls)
    }

    @Test fun changingDestinationKeepsInFlightOriginAndPreviewCancellationKeepsConfirmedWork() = runTest {
        var origins = 0
        val release = CompletableDeferred<Unit>()
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> origins++; release.await() },
            loadDestination = {}, loadRoutes = { _, _, _, _, _ -> }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft()); runCurrent()
        prep.prepare(draft(to = b)); runCurrent()
        prep.preview(c, origin = false); runCurrent(); prep.preview(null, origin = false)
        prep.prepare(draft(to = c)); runCurrent()
        assertEquals(1, origins)
        assertTrue(prep.state.value.loading)
        release.complete(Unit); runCurrent()
        assertFalse(prep.state.value.loading)
        assertTrue(prep.state.value.routesReady)
    }

    @Test fun scheduleSoftRefreshRetriesAtThreeMinutesWithoutWaitingForHardExpiry() = runTest {
        var calls = 0
        val prep = TripPreparation(backgroundScope, loadPlace = { _, _, _ -> }, loadDestination = {},
            loadRoutes = { _, _, _, _, _ -> calls++ }, nowMillis = { testScheduler.currentTime })
        prep.prepare(draft(to = b)); runCurrent()
        advanceTimeBy(SearchPolicy.SCHEDULE_REFRESH_MILLIS - 1)
        prep.prepare(draft(to = b)); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1)
        prep.prepare(draft(to = b)); runCurrent(); assertEquals(2, calls)
        assertFalse(prep.state.value.loading)
    }
}
