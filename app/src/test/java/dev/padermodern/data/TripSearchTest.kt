package dev.padermodern.data

import org.junit.Assert.assertEquals
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
        // two trains, the first with its four buses and the second with its three, and no third train
        assertEquals(4, ranked.count { it.departureDate == at(11, 0) })
        assertEquals(3, ranked.count { it.departureDate == at(12, 0) })
        assertEquals(0, ranked.count { it.departureDate == at(13, 0) })
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
}
