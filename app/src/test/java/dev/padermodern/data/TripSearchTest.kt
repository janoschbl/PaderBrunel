package dev.padermodern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TripSearchTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 10, 24, 10, 33, 0, 0, zone)

    private fun at(h: Int, m: Int) = now.withHour(h).withMinute(m).iso()

    private fun route(dep: Pair<Int, Int>, arr: Pair<Int, Int>, expDep: Pair<Int, Int>? = null, expArr: Pair<Int, Int>? = null) = Route(
        partialRoutes = listOf(PartialRoute(shortName = "RB89", departureDate = at(dep.first, dep.second), arrivalDate = at(arr.first, arr.second))),
        departureDate = at(dep.first, dep.second),
        arrivalDate = at(arr.first, arr.second),
        expectedDepartureDate = expDep?.let { at(it.first, it.second) },
        expectedArrivalDate = expArr?.let { at(it.first, it.second) },
    )

    @Test
    fun trainPlannedBeforeNowButDelayedPastNowStaysInTheList() {
        val delayed = route(10 to 25, 10 to 40, expDep = 11 to 1, expArr = 11 to 16)
        val gone = route(10 to 4, 10 to 46, expDep = 10 to 5, expArr = 10 to 48)
        val ranked = TripSearch.rank(listOf(delayed, gone), TripSearch.window(now, arrival = false))
        assertEquals(listOf(delayed.key), ranked.map { it.key })
    }

    @Test
    fun punctualTrainBeforeNowIsStillCut() {
        val early = route(10 to 25, 10 to 40)
        assertEquals(emptyList<Route>(), TripSearch.rank(listOf(early), TripSearch.window(now, arrival = false)))
    }

    private fun ride(line: String, type: Int, dep: Pair<Int, Int>, arr: Pair<Int, Int>) =
        PartialRoute(shortName = line, type = type, departureDate = at(dep.first, dep.second), arrivalDate = at(arr.first, arr.second))

    /** A train followed by one onward bus. */
    private fun trainThenBus(trainDep: Pair<Int, Int>, bus: String, busDep: Pair<Int, Int>, arr: Pair<Int, Int>) = Route(
        partialRoutes = listOf(ride("RB89", 1, trainDep, trainDep.first to trainDep.second + 30), ride(bus, 3, busDep, arr)),
        changes = 1, departureDate = at(trainDep.first, trainDep.second), arrivalDate = at(arr.first, arr.second),
    )

    @Test
    fun eachTrainKeepsSeveralOnwardBusVariants() {
        val routes = listOf("100", "480", "2", "S60").mapIndexed { i, bus -> trainThenBus(11 to 0, bus, 11 to 35 + i, 11 to 50 + i) } +
            listOf("100", "8", "R50").mapIndexed { i, bus -> trainThenBus(12 to 0, bus, 12 to 35 + i, 12 to 52 + i) } +
            listOf("1").map { bus -> trainThenBus(13 to 0, bus, 13 to 35, 13 to 50) }
        val ranked = TripSearch.rank(routes, TripSearch.window(now, arrival = false))
        // the first train with its four buses, the second with its three, and a third train to reach the eight rows
        assertEquals(4, ranked.count { it.departureDate == at(11, 0) })
        assertEquals(3, ranked.count { it.departureDate == at(12, 0) })
        assertEquals(1, ranked.count { it.departureDate == at(13, 0) })
    }

    @Test
    fun listOpensWithAtLeastEightConnections() {
        val routes = (11..13).flatMap { h -> listOf(0, 10, 20).map { m -> trainThenBus(h to m, "100", h to m + 35, h to m + 38) } }
        val ranked = TripSearch.rank(routes, TripSearch.window(now, arrival = false))
        assertEquals(TripSearch.MIN_ROWS, ranked.size)
    }

    @Test
    fun sameLineChainIsOneChoice() {
        val a = trainThenBus(11 to 0, "100", 11 to 35, 11 to 50)
        val b = trainThenBus(11 to 0, "100", 11 to 36, 11 to 55)
        assertEquals(1, TripSearch.rank(listOf(a, b), TripSearch.window(now, arrival = false)).size)
    }

    @Test
    fun directBusesShowMoreThanOneChoice() {
        val buses = (0 until 6).map { i ->
            val d = 11 to (i * 6)
            Route(partialRoutes = listOf(ride("L$i", 3, d, 11 to (i * 6 + 12))), departureDate = at(d.first, d.second), arrivalDate = at(11, i * 6 + 12))
        }
        assertEquals(4, TripSearch.rank(buses, TripSearch.window(now, arrival = false)).size)
    }

    /** Screenshot 2026-11-07: "Mein Standort", 8 min walk, 8 min walk, Geseke. The route starts at a bus stop and
     * the provider walks on to the station; the traveller walks there directly, in one leg. */
    @Test
    fun walkToABusStopAndTheProvidersWalkOnBecomeOneWalk() {
        val me = Station(type = "address", name = "Mein Standort", latitude = 51.6400, longitude = 8.5150)
        val busStop = Station(type = "stop", id = "24032568", name = "Schulzentrum Mitte", latitude = 51.64167, longitude = 8.51375)
        val station = Station(type = "stop", id = "24032585", name = "Geseke", latitude = 51.6440, longitude = 8.5150)
        val dest = Station(type = "stop", id = "23207000", name = "Paderborn Hbf")
        val providerWalk = PartialRoute(clazz = "walkway", type = 100, transferTime = 8, departureDate = at(10, 47), arrivalDate = at(10, 55),
            stations = listOf(busStop.copy(date = at(10, 47)), station.copy(date = at(10, 55))))
        val train = PartialRoute(shortName = "RB89", departureDate = at(10, 55), arrivalDate = at(11, 10), expectedDepartureDate = at(10, 56),
            stations = listOf(station.copy(date = at(10, 55)), dest.copy(date = at(11, 10))))
        val r = Route(partialRoutes = listOf(providerWalk, train), departureDate = at(10, 47), arrivalDate = at(11, 10))
        val out = TripSearch.withWalks(r, Endpoint(busStop, 8), Endpoint(dest, 0), me, dest)
        assertEquals(2, out.partialRoutes.size)
        val walk = out.partialRoutes.first()
        // 445 m straight to the station: 7 min, not 8 + 8
        assertEquals(7, walk.transferTime)
        assertEquals("Geseke", walk.stations.last().name)
        assertEquals(at(10, 48), out.departureDate)
        assertEquals(at(10, 49), out.expectedDepartureDate)
    }

    @Test
    fun plainRouteStillGetsItsWalk() {
        val me = Station(type = "address", name = "Mein Standort", latitude = 51.64, longitude = 8.515)
        val stop = Station(type = "stop", id = "1", name = "A", latitude = 51.641, longitude = 8.515)
        val r = route(10 to 25, 10 to 40)
        val out = TripSearch.withWalks(r, Endpoint(stop, 4), Endpoint(stop.copy(id = "2"), 0), me, stop)
        assertEquals(at(10, 21), out.departureDate)
        assertTrue(out.partialRoutes.first().isWalk && out.partialRoutes.first().transferTime == 4)
    }
}
