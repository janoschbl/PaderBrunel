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
}
