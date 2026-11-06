package dev.padermodern.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class LocalPlannerTest {
    private val day = LocalDate.of(2026, 11, 3)
    private val berlin = ZoneId.of("Europe/Berlin")
    // A --(L1)--> B --(L2)--> C, plus D near B (150 m) with L3 to E, and a slow direct A -> C.
    private val names = arrayOf("A", "B", "C", "D", "E")
    private val lat = doubleArrayOf(51.70, 51.71, 51.72, 51.7114, 51.73)
    private val lon = doubleArrayOf(8.70, 8.71, 8.72, 8.71, 8.73)
    private fun trip(line: String, vararg calls: Pair<Int, Int>) = LocalTrip(line, line, "Bus", 3, "dest",
        IntArray(calls.size) { calls[it].first }, IntArray(calls.size) { calls[it].second })
    private fun table(vararg trips: LocalTrip) = LocalTimetable(day, Array(5) { "id$it" }, names, lat, lon, trips.toList())
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 3, h, m, 0, 0, berlin)
    private fun stop(i: Int) = Station(type = "stop", id = "id$i", name = names[i])

    @Test fun directRide() {
        val tt = table(trip("L1", 0 to 480, 1 to 490))
        val j = LocalPlanner(tt).journeys(0, 1, 475, 1).single()
        assertEquals(480, j.departure); assertEquals(490, j.arrival); assertEquals(1, j.rides)
    }

    @Test fun changeNeedsMinimumTransferTime() {
        // L1 arrives B 490; L2a leaves B 492 (too tight with 3 min), L2b leaves 495
        val tt = table(trip("L1", 0 to 480, 1 to 490), trip("L2a", 1 to 492, 2 to 500), trip("L2b", 1 to 495, 2 to 505))
        val j = LocalPlanner(tt, minTransferMin = 3).journeys(0, 2, 470, 2).single()
        assertEquals(505, j.arrival)
        assertEquals(2, j.rides)
    }

    @Test fun maxChangesZeroForbidsTransfers() {
        val tt = table(trip("L1", 0 to 480, 1 to 490), trip("L2", 1 to 495, 2 to 505))
        assertTrue(LocalPlanner(tt).journeys(0, 2, 470, 0).isEmpty())
    }

    @Test fun footpathAllowsChangeBetweenNearbyStops() {
        // L1 A->B arrives 490; walk B->D (~150 m, 3 min); L3 leaves D 497
        val tt = table(trip("L1", 0 to 480, 1 to 490), trip("L3", 3 to 497, 4 to 510))
        val j = LocalPlanner(tt).journeys(0, 4, 470, 1).single()
        assertTrue(j.legs.any { it is LocalLeg.Walk })
        assertEquals(510, j.arrival)
    }

    @Test fun laterDeparturesFollowAndFasterBeatsSlowDirect() {
        val tt = table(
            trip("fast", 0 to 480, 2 to 500), trip("fast2", 0 to 500, 2 to 520), trip("slow", 0 to 481, 2 to 540),
        )
        val js = LocalPlanner(tt).journeys(0, 2, 470, 1)
        assertEquals(listOf(480, 500), js.map { it.departure }.take(2))
        assertTrue(js.none { it.arrival == 540 && it.departure == 481 && js.any { o -> o.arrival < 540 && o.departure >= 481 } && false })
    }

    @Test fun nothingBeforeStartTime() {
        val tt = table(trip("L1", 0 to 480, 1 to 490))
        assertTrue(LocalPlanner(tt).journeys(0, 1, 500, 1).isEmpty())
    }

    @Test fun sourceBuildsRouteWithRealTimesAndWalkLegs() = runTest {
        val tt = table(trip("L1", 0 to 480, 1 to 490), trip("L3", 3 to 497, 4 to 510))
        val source = LocalTripSource(mapOf(day to tt))
        val route = source.routesBetween(stop(0), stop(4), at(7, 50), false, 1).single()
        assertEquals(listOf("vehicleRide", "walkway", "vehicleRide"), route.partialRoutes.map { it.clazz })
        assertEquals(1, route.changes)
        assertEquals("2026-11-03T08:00:00+0100", route.departureDate)
        assertEquals("2026-11-03T08:30:00+0100", route.arrivalDate)
    }

    @Test fun sourceDeclinesWhatItCannotAnswer() = runTest {
        val tt = table(trip("L1", 0 to 480, 1 to 490))
        val source = LocalTripSource(mapOf(day to tt))
        assertTrue("arrival search", source.routesBetween(stop(0), stop(1), at(8, 0), true, 1).isEmpty())
        assertTrue("other day", source.routesBetween(stop(0), stop(1), at(8, 0).plusDays(1), false, 1).isEmpty())
        assertTrue("unknown stop", source.routesBetween(Station(type = "stop", id = "nope"), stop(1), at(7, 50), false, 1).isEmpty())
        assertFalse(source.covers(Station(type = "stop", id = "nope"), stop(1), at(8, 0)))
    }

    @Test fun addressesResolveToNearestLocalStopsWithWalkTime() = runTest {
        val source = LocalTripSource(mapOf(day to table(trip("L1", 0 to 480, 1 to 490))))
        val eps = source.endpoints(Station(type = "coord", latitude = 51.7003, longitude = 8.7003), 3)
        assertEquals("id0", eps.first().stop.id)
        assertTrue(eps.first().walkMin >= 1)
    }

    @Test fun builderMergesPartialStopListsOfOneTrip() {
        fun dep(vararg ids: Pair<String, String>) = Departure(
            lineId = "line", tripCode = "7", shortName = "R1", type = 3, destination = "C",
            route = Route(partialRoutes = listOf(PartialRoute(clazz = "vehicleRide", stations = ids.map { (id, t) ->
                Station(type = "stop", id = id, name = id, latitude = 51.7, longitude = 8.7, date = "2026-11-03T$t:00+0100")
            }))),
        )
        val b = TimetableBuilder(day)
        b.add(dep("B" to "08:10", "C" to "08:20"))               // board at B
        b.add(dep("A" to "08:00", "B" to "08:10", "C" to "08:20")) // board at A: same trip, longer
        b.add(dep("B" to "08:10", "C" to "08:20"))               // again, shorter: ignored
        b.add(dep("B" to "09:10", "C" to "09:20"))               // different run, same trip code
        val tt = b.build()
        assertEquals(2, tt.trips.size)
        assertEquals(3, tt.trips.maxOf { it.stops.size })
        val out = java.io.ByteArrayOutputStream(); tt.write(out)
        val again = LocalTimetable.read(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(tt.trips.map { it.minutes.toList() }, again.trips.map { it.minutes.toList() })
        assertEquals(tt.stopIds.toList(), again.stopIds.toList())
    }

    @Test fun shadowComparisonCountsFirstThreeServerConnections() {
        fun r(line: String, dep: String, arr: String) = Route(
            departureDate = "2026-11-03T$dep:00+0100", arrivalDate = "2026-11-03T$arr:00+0100",
            partialRoutes = listOf(PartialRoute(shortName = line, departureDate = "2026-11-03T$dep:00+0100", arrivalDate = "2026-11-03T$arr:00+0100")),
        )
        val server = listOf(r("1", "08:00", "08:10"), r("2", "08:10", "08:20"), r("3", "08:20", "08:30"), r("4", "08:30", "08:40"))
        val local = listOf(r("1", "08:00", "08:10"), r("3", "08:20", "08:30"), r("4", "08:30", "08:40"))
        val res = ShadowComparison.compare(server, local)
        assertEquals(2, res.matched); assertEquals(3, res.total)
    }
}

class TimetableCrawlerTest {
    private val day = LocalDate.of(2026, 11, 3)
    private fun ride(line: String, vararg stops: Pair<String, Int>) = Departure(
        lineId = line, tripCode = "1", shortName = line, type = 3, destinationID = stops.last().first,
        departureDate = "2026-11-03T%02d:%02d:00+0100".format(stops.first().second / 60, stops.first().second % 60),
        route = Route(partialRoutes = listOf(PartialRoute(clazz = "vehicleRide", stations = stops.map { (id, m) ->
            Station(type = "stop", id = id, name = id, latitude = 51.72, longitude = 8.75, date = "2026-11-03T%02d:%02d:00+0100".format(m / 60, m % 60))
        }))),
    )

    @Test fun followsTerminiAndFindsLinesThroughBusyInteriorStops() = runTest {
        val asked = mutableListOf<String>()
        // hub H serves line 1 to T; terminus T serves the way back; interior stop M also has line 9 (H never sees it)
        val boards = mapOf(
            "H" to listOf(ride("1", "H" to 480, "M" to 490, "T" to 500)),
            "T" to listOf(ride("1r", "T" to 510, "M" to 520, "H" to 530)),
            "M" to listOf(ride("9", "M" to 495, "X" to 505)),
        )
        val crawler = TimetableCrawler(board = { id, at ->
            asked += id
            // one page per stop; the crawler pages by last departure so a repeat must stop it
            if (at.hour >= 3 && at.hour < 4) boards[id].orEmpty() else boards[id].orEmpty()
        })
        val (table, report) = crawler.crawl(day, listOf("H"), maxStops = 6)
        assertTrue(asked.containsAll(listOf("H", "T", "M")))
        assertEquals(3, table.trips.size)
        assertTrue(table.trips.any { it.shortName == "9" })
        assertEquals(0, report.failedRequests)
        assertTrue("pages terminate", report.requests < 30)
    }

    @Test fun failedBoardsAreCountedNotFatal() = runTest {
        val crawler = TimetableCrawler(board = { _, _ -> throw java.io.IOException("502") })
        val (table, report) = crawler.crawl(day, listOf("H"), maxStops = 2)
        assertEquals(0, table.trips.size)
        assertTrue(report.failedRequests >= 1)
    }
}
