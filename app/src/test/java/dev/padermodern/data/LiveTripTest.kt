package dev.padermodern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class LiveTripTest {
    private fun stop(name: String, time: String, canceled: Boolean = false) = Station(id = name, name = name, date = "2026-11-07T$time:00+0100", canceled = canceled)
    private fun bus8(vararg skipped: String) = PartialRoute(
        shortName = "8", departureDate = "2026-11-07T10:59:00+0100", arrivalDate = "2026-11-07T11:10:00+0100",
        stations = listOf("Westerntor" to "10:59", "Rathausplatz" to "11:03", "Kamp" to "11:05", "Langer Weg" to "11:10")
            .map { (n, t) -> stop(n, t, n in skipped) },
    )
    private val now = ZonedDateTime.parse("2026-11-07T10:58:00+01:00")

    /** 2026-11-07: the 8 skipped Rathausplatz and Kamp on a detour and the app said "8 fällt aus". */
    @Test fun skippedStopsInBetweenDoNotCancelTheRide() {
        val s = LiveTrip.status(Route(partialRoutes = listOf(bus8("Rathausplatz", "Kamp"))), now)
        assertFalse(s.broken)
    }

    @Test fun cancelledBoardingStopCancelsTheRide() {
        val s = LiveTrip.status(Route(partialRoutes = listOf(bus8("Westerntor"))), now)
        assertTrue(s.broken)
        assertEquals("8 fällt aus", s.brokenText)
    }
}
