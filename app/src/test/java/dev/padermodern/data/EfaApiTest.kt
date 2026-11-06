package dev.padermodern.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EfaApiTest {
    private val routes = EfaApi.parse(javaClass.getResource("/efa-trip.json")!!.readText())

    @Test fun parsesAllJourneys() {
        assertEquals(4, routes.size)
        val first = routes.first()
        assertEquals("23207000", first.rides.first().stations.first().id)
        assertEquals("23207063", first.partialRoutes.last().let { it.stations.lastOrNull()?.id ?: first.rides.last().stations.last().id })
        assertEquals("8", first.rides.first().shortName)
        assertEquals(0, first.changes)
        assertEquals(11, first.duration!!.substringAfter(':').toInt())
    }

    @Test fun timesUseBerlinOffsetAndParse() {
        val dep = parseTime(routes.first().departureDate)
        assertNotNull(dep)
        assertEquals(7, dep!!.hour) // 06:54Z = 07:54 CET
        assertEquals(54, dep.minute)
    }

    @Test fun walkLegBecomesWalkway() {
        val withWalk = routes.first { r -> r.partialRoutes.any { it.isWalk } }
        assertTrue(withWalk.partialRoutes.first { it.isWalk }.walkMinutes > 0)
    }

    @Test fun mapsProviderIdsToGlobalIds() {
        assertEquals("de:05774:7063", EfaApi.globalId(Station(type = "stop", id = "23207063")))
        assertEquals("de:05774:7000", EfaApi.globalId(Station(type = "stop", id = "23207000")))
        val p = EfaApi.params(Station(type = "stop", id = "23207000"), Station(type = "stop", id = "23207063"), ZonedDateTime.of(2026, 11, 5, 8, 0, 0, 0, ZoneId.of("Europe/Berlin")), false, 1)!!
        assertEquals("de:05774:7000", p["name_origin"])
        assertEquals("0800", p["itdTime"])
    }
}
