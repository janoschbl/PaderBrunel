package dev.padermodern.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardLiveTest {
    private val json = Json { ignoreUnknownKeys = true }
    // 2026-11-05 09:00 and 09:10 Berlin time
    private val t0 = 1793865600L
    private val deps = listOf(
        Departure(shortName = "R 51", departureDate = "2026-11-05T09:00:00+0100"),
        Departure(shortName = "4", departureDate = "2026-11-05T09:10:00+0100", expectedDepartureDate = "2026-11-05T09:13:00+0100"),
    )
    private fun delta(s: String) = json.decodeFromString<BDelta>(s)

    @Test fun setMovesMatchingDepartureOnly() {
        val out = BoardLive.apply(deps, delta("""{"stop":"x","set":[[${t0 / 60},"r51",${t0 + 480}]],"clear":[]}"""))!!
        assertEquals("2026-11-05T09:08:00+0100", out[0].expectedDepartureDate)
        assertEquals(deps[1], out[1])
    }

    @Test fun changeClearsRecoveredDelay() {
        val out = BoardLive.apply(deps, delta("""{"stop":"x","set":[],"clear":[[${(t0 + 600) / 60},"4"]]}"""))!!
        assertNull(out[1].expectedDepartureDate)
    }

    @Test fun snapshotNeverClears() {
        val out = BoardLive.apply(deps, delta("""{"stop":"x","snap":true,"set":[],"clear":[[${(t0 + 600) / 60},"4"]]}"""))
        assertNull(out)
    }

    @Test fun identicalDelayIsNoChange() {
        assertNull(BoardLive.apply(deps, delta("""{"stop":"x","set":[[${(t0 + 600) / 60},"4",${t0 + 780}]],"clear":[]}""")))
    }
}
