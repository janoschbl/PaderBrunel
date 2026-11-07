package dev.padermodern.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendApiTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lines = javaClass.getResource("/backend-plan.ndjson")!!.readText().lines().filter { it.isNotBlank() }
    private val plan = json.decodeFromString<BPlan>(lines[0])
    private val live = lines.getOrNull(1)?.let { json.decodeFromString<BLive>(it) }

    @Test fun mapsEveryItineraryWithoutLive() {
        val routes = BackendApi.toRoutes(plan, null)
        assertEquals(plan.itins.size, routes.size)
        val ride = routes.first().rides.first()
        assertNotNull(ride.shortName)
        // Paderborn district ids are mapped to the provider's numbering
        assertEquals("23207000", routes.first().partialRoutes.flatMap { it.stations }.first { it.globalId == "de:05774:7000" }.id)
        assertTrue(routes.all { parseTime(it.departureDate) != null && parseTime(it.arrivalDate) != null })
    }

    @Test fun liveFrameMovesTimesAndDropsGoneItineraries() {
        val l = live ?: return
        val routes = BackendApi.toRoutes(plan, l)
        assertEquals(plan.itins.size - l.gone.size, routes.size)
        val delayed = l.legs.firstOrNull { it.dep != plan.itins[it.itin].legs[it.leg].dep } ?: return
        val idx = plan.itins.indices.filter { it !in l.gone }.indexOf(delayed.itin)
        assertTrue(routes[idx].partialRoutes[delayed.leg].expectedDepartureDate != null)
    }

    @Test fun globalIdFromProviderId() {
        assertEquals("de:05774:7063", BackendApi.globalId(Station(type = "stop", id = "23207063")))
        assertEquals("de:05774:7000", BackendApi.globalId(Station(type = "stop", id = "x", globalId = "de:05774:7000:A:A2")))
    }

    @Test fun walkLegBecomesWalkway() {
        val withWalk = BackendApi.toRoutes(plan, null).first { r -> r.partialRoutes.any { it.isWalk } }
        assertTrue(withWalk.partialRoutes.first { it.isWalk }.walkMinutes > 0)
    }

    @Test fun cancelledLegStaysListedAndIsMarked() {
        val i = plan.itins.indexOfFirst { it.legs.any { l -> l.mode != "WALK" } }
        val k = plan.itins[i].legs.indexOfFirst { it.mode != "WALK" }
        val leg = plan.itins[i].legs[k]
        val routes = BackendApi.toRoutes(plan, BLive(legs = listOf(BLegLive(i, k, leg.dep, leg.arr, cancelled = true, src = "vrr"))))
        assertEquals(plan.itins.size, routes.size)
        assertTrue(routes[i].partialRoutes[k].stations.first().canceled)
    }
}
