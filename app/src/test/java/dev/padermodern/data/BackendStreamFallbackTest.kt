package dev.padermodern.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.time.ZoneId
import java.time.ZonedDateTime

/** 2026-11-07: on a bad link the plan stream stalled, and the search fell back to stop pairs with one way per train. */
class BackendStreamFallbackTest {
    private val plan = """{"t":"plan","at":0,"itins":[{"dep":1794157680,"arr":1794160980,"changes":1,"legs":[
        {"mode":"WALK","from_name":"START","to_name":"Berliner Ring","dep":1794157680,"arr":1794158280,"from_pos":[51.728,8.767],"to_pos":[51.73,8.77]},
        {"mode":"BUS","line":"8","to":"Hbf","from_name":"Berliner Ring","to_name":"Hbf","from_id":"de-DELFI_de:05774:7078","to_id":"de-DELFI_de:05774:7000","dep":1794158280,"arr":1794159180,"from_pos":[51.73,8.77],"to_pos":[51.71,8.74]},
        {"mode":"REGIONAL_RAIL","line":"RB89","to":"Münster","from_name":"Hbf","to_name":"Geseke","from_id":"de-DELFI_de:05774:7000","to_id":"de-DELFI_de:05974:32585","dep":1794160260,"arr":1794160980,"from_pos":[51.71,8.74],"to_pos":[51.64,8.51]}]}]}"""

    @Test fun stalledStreamFallsBackToTimetable() = runBlocking {
        val server = runCatching { HttpServer.create(InetSocketAddress("127.0.0.1", 8099), 0) }.getOrNull()
        assumeTrue("port 8099 in use", server != null)
        server!!.createContext("/v1/plan") { ex ->
            if (ex.requestURI.path == "/v1/plan/static") {
                val body = plan.toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            } else {
                // headers, a piece of frame 1, then nothing: the client's read timeout ends it
                ex.sendResponseHeaders(200, 0); ex.responseBody.write(plan.take(100).toByteArray()); ex.responseBody.flush()
                Thread.sleep(9_000); ex.close()
            }
        }
        server.start()
        try {
            val from = Station(type = "address", name = "Paderborn, Marsenweg", latitude = 51.72818, longitude = 8.76743)
            val to = Station(type = "stop", id = "de:05974:32585", globalId = "de:05974:32585", name = "Geseke, Bahnhof", latitude = 51.645, longitude = 8.51)
            val routes = BackendApi.routesBetween(from, to, ZonedDateTime.of(2026, 11, 8, 18, 0, 0, 0, ZoneId.of("Europe/Berlin")), false, 2)
            assertEquals(1, routes?.size)
            assertEquals(listOf("8", "RB89"), routes!!.single().rides.map { it.shortName })
            assertEquals("Paderborn, Marsenweg", routes.single().partialRoutes.first().stations.first().name)
        } finally {
            server.stop(0)
        }
    }
}
