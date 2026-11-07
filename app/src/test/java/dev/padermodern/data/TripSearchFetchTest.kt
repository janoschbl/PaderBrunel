package dev.padermodern.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TripSearchFetchTest {
    private val time = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))
    private fun station(id: String) = Station(type = "stop", id = id, name = "$id Bahnhof")
    private fun route(id: String = "x") = Route(partialRoutes = listOf(PartialRoute(shortName = id)), departureDate = time.toString(), arrivalDate = time.plusMinutes(10).toString())

    private open class Source(private val stops: List<Endpoint>) : TripSearchSource {
        override suspend fun endpoints(place: Station, limit: Int) = stops.filter { it.stop.id == place.id }.ifEmpty { stops }
        override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean) = emptyList<Departure>()
        override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int) = emptyList<Route>()
    }

    @Test fun arrivalSearchSkipsBoardsButStillQueriesProvider() = runTest {
        var boards = 0
        var pairs = 0
        val src = object : Source(listOf(Endpoint(station("A"), 0), Endpoint(station("B"), 0))) {
            override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean): List<Departure> { boards++; return emptyList() }
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> { pairs++; return emptyList() }
        }
        TripSearch.fetch(station("A"), station("B"), time, true, 1, source = src)
        assertEquals(0, boards)
        assertEquals(1, pairs)
    }

    @Test fun wholeTripSearchReplacesStopPairs() = runTest {
        var pairs = 0
        val address = Station(type = "address", name = "Marsenweg", latitude = 51.72777, longitude = 8.76695)
        val src = object : Source(listOf(Endpoint(station("A"), 5), Endpoint(station("C"), 7), Endpoint(station("B"), 0))) {
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> { pairs++; return emptyList() }
            override suspend fun wholeTrip(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int) = listOf(route("2"))
        }
        val result = TripSearch.fetch(address, station("B"), time, false, 1, source = src)
        assertEquals(0, pairs)
        assertEquals("2", result.single().partialRoutes.single().shortName)
    }

    @Test fun stopPairsAreTheFallbackWhenWholeTripFails() = runTest {
        var pairs = 0
        val src = object : Source(listOf(Endpoint(station("A"), 0), Endpoint(station("B"), 0))) {
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> { pairs++; return listOf(route()) }
            override suspend fun wholeTrip(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> = error("backend down")
        }
        TripSearch.fetch(station("A"), station("B"), time, false, 1, includeDirect = false, source = src)
        assertEquals(1, pairs)
    }

    @Test fun directDiscoveryCanReturnCandidate() = runTest {
        val src = object : Source(listOf(Endpoint(station("A"), 0), Endpoint(station("B"), 0))) {
            override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean): List<Departure> = listOf(
                Departure(shortName = "RB", route = Route(partialRoutes = listOf(PartialRoute(
                    shortName = "RB", stations = listOf(
                        station("A").copy(date = time.toString()),
                        station("B").copy(date = time.plusMinutes(10).toString()),
                    ),
                )))),
            )
        }
        val result = TripSearch.fetch(station("A"), station("B"), time, false, 1, includeDirect = true, source = src,
            computationDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(1, result.size)
        assertEquals("RB", result.single().rides.single().shortName)
    }

    @Test fun localFallbackStillReportsProviderOutageAndProviderEnrichmentWinsOnSuccess() = runTest {
        val a = station("A"); val b = station("B")
        val ride = PartialRoute(shortName = "RB", departureDate = time.iso(), arrivalDate = time.plusMinutes(10).iso(), encodedPath = "full-path",
            stations = listOf(a.copy(date = time.iso()), b.copy(date = time.plusMinutes(10).iso())))
        val provider = Route(partialRoutes = listOf(ride), departureDate = ride.departureDate, arrivalDate = ride.arrivalDate,
            fares = listOf(Fare(name = "Provider fare")))
        var failed = true
        val source = object : Source(emptyList()) {
            override suspend fun endpoints(place: Station, limit: Int) = listOf(Endpoint(place, 0))
            override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean) = listOf(Departure(shortName = "RB", route = provider))
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> {
                if (failed) error("offline")
                return listOf(provider)
            }
        }
        val partial = mutableListOf<Route>()
        val error = runCatching { TripSearch.fetch(a, b, time, false, 1, onBatch = { partial += it }, source = source,
            computationDispatcher = StandardTestDispatcher(testScheduler)) }.exceptionOrNull()
        assertTrue(error is PartialTripSearchException)
        assertEquals(1, partial.size)
        assertNull(partial.single().rides.single().encodedPath)
        failed = false
        val routes = TripSearch.fetch(a, b, time, false, 1, source = source, computationDispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(1, routes.size)
        assertEquals("full-path", routes.single().rides.single().encodedPath)
        assertEquals("Provider fare", routes.single().fares.single().name)
    }

    @Test fun pairFanoutIsLimitedToFourAndConcurrent() = runTest {
        val active = AtomicInteger(); val peak = AtomicInteger()
        val stops = (0..4).map { Endpoint(station("$it"), it) }
        val src = object : Source(stops) {
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> {
                val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }
                kotlinx.coroutines.yield(); active.decrementAndGet(); return emptyList()
            }
        }
        TripSearch.fetch(station("origin"), station("dest"), time, true, 2, includeDirect = false, source = src)
        assertEquals(SearchPolicy.MAX_STOP_PAIRS, peak.get())
    }

    @Test fun anyProviderFailureThrowsAfterPartialBatch() = runTest {
        var batches = 0
        val src = object : Source(listOf(Endpoint(station("A"), 0), Endpoint(station("B"), 0), Endpoint(station("C"), 0))) {
            override suspend fun endpoints(place: Station, limit: Int) =
                if (place.id == "A") listOf(Endpoint(station("A"), 0))
                else listOf(Endpoint(station("B"), 0), Endpoint(station("C"), 0))
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> {
                if (to.id == "B") return listOf(route("ok"))
                error("provider offline")
            }
        }
        try {
            TripSearch.fetch(station("A"), station("C"), time, false, 2, onBatch = { batches += it.size }, includeDirect = false, source = src)
            fail("expected partial-search error")
        } catch (e: PartialTripSearchException) {
            assertTrue(e.message!!.contains("unvollständig"))
        }
        assertEquals(1, batches)
    }

    @Test fun validEmptyProviderResponsesDoNotThrow() = runTest {
        val src = Source(listOf(Endpoint(station("A"), 0), Endpoint(station("B"), 0)))
        assertTrue(TripSearch.fetch(station("A"), station("B"), time, true, 2, includeDirect = false, source = src).isEmpty())
    }

    @Test fun cancellationPropagates() = runTest {
        val gate = CompletableDeferred<Unit>()
        val src = object : Source(listOf(Endpoint(station("A"), 0), Endpoint(station("B"), 0))) {
            override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> { gate.await(); return emptyList() }
        }
        val job = async { TripSearch.fetch(station("A"), station("B"), time, true, 2, includeDirect = false, source = src) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun simulatedFirstResultComparesProviderOnlyColdBoardAndPreloadedBoard() = runTest {
        data class Measurement(val firstResultMs: Long, val graphLoads: Int, val boardLoadsDuringSearch: Int)
        suspend fun measure(direct: Boolean, preload: Boolean): Measurement {
            var graphLoads = 0
            var boardLoads = 0
            val a = station("A"); val b = station("B")
            val ride = PartialRoute(shortName = "RB", departureDate = time.iso(), arrivalDate = time.plusMinutes(10).iso(),
                stations = listOf(a.copy(date = time.iso()), b.copy(date = time.plusMinutes(10).iso())))
            val provider = Route(partialRoutes = listOf(ride), departureDate = ride.departureDate, arrivalDate = ride.arrivalDate)
            val boards = DepartureRequests(backgroundScope, { _, _ ->
                boardLoads++; kotlinx.coroutines.delay(100)
                listOf(Departure(shortName = "RB", departureDate = time.iso(), route = provider))
            }, nowMillis = { testScheduler.currentTime })
            if (preload) boards.get("A", time, fresh = false)
            val previousBoards = boardLoads
            val start = testScheduler.currentTime
            var first: Long? = null
            val source = object : TripSearchSource {
                override suspend fun endpoints(place: Station, limit: Int) = listOf(Endpoint(place, 0))
                override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean) = boards.get(stationId, at, fresh)
                override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> {
                    graphLoads++; kotlinx.coroutines.delay(1_000); return listOf(provider)
                }
            }
            TripSearch.fetch(a, b, time, false, 1, onBatch = {
                if (it.isNotEmpty() && first == null) first = testScheduler.currentTime - start
            }, includeDirect = direct, source = source, computationDispatcher = StandardTestDispatcher(testScheduler))
            return Measurement(first!!, graphLoads, boardLoads - previousBoards)
        }
        val providerOnly = measure(direct = false, preload = false)
        val coldDirect = measure(direct = true, preload = false)
        val warmDirect = measure(direct = true, preload = true)
        assertEquals(Measurement(1_000, 1, 0), providerOnly)
        assertEquals(Measurement(100, 1, 1), coldDirect)
        assertEquals(Measurement(0, 1, 0), warmDirect)
        println("BENCHMARK simulated first-result providerOnly=$providerOnly directCold=$coldDirect directPreloaded=$warmDirect; preload costs one board request before search; final provider completion remains 1000ms (not device benchmark)")
    }
}
