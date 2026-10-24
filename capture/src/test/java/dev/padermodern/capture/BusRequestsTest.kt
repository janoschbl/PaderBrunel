package dev.padermodern.capture

import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BusRequestsTest {
    @Test fun fourManualAttemptsEachMakeANewHttpCallEvenAfterFailure() {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            assertEquals("https://service.ivanto.de/busaccess/stopRequest", chain.request().url.toString())
            assertEquals("POST", chain.request().method)
            assertEquals("{\"btMac\":\"AA:BB:CC:DD:EE:FF\"}", Buffer().also {
                chain.request().body!!.writeTo(it)
            }.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (attempts == 1) 503 else 200).message("test")
                .body("{\"status\":${attempts > 1}}".toResponseBody()).build()
        }.build()
        repeat(4) { index ->
            assertEquals(index > 0, BusRequests.send(busRequest(BusAction.Stop, "AA:BB:CC:DD:EE:FF"), client).accepted)
            assertEquals(index + 1, attempts)
        }
    }

    @Test fun routesAndBodyMatchAnalysis() {
        val routes = listOf("stopRequest", "doorsignalRequest", "serviceRequest")
        BusAction.entries.zip(routes).forEach { (action, route) ->
            val r = busRequest(action, "AA:BB:CC:DD:EE:FF")
            assertEquals("https://service.ivanto.de/busaccess/$route", r.url.toString())
            assertEquals("POST", r.method)
            val body = Buffer().also { r.body!!.writeTo(it) }.readUtf8()
            assertEquals("{\"btMac\":\"AA:BB:CC:DD:EE:FF\"}", body)
        }
    }

    @Test fun requiresExplicitServerConfirmation() {
        assertTrue(BusReply(200, "{\"status\":true}", false).accepted)
        assertFalse(BusReply(200, "{\"status\":false}", false).accepted)
        assertFalse(BusReply(200, "{}", false).accepted)
        assertFalse(BusReply(204, "", false).accepted)
        assertFalse(BusReply(500, "{\"status\":true}", false).accepted)
        assertFalse(BusReply(200, "not json", false).accepted)
        assertFalse(BusReply(200, "{\"status\":true}", true).accepted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidAddress() { busRequest(BusAction.Stop, "not-a-mac") }

    @Test fun actionsRespectAdvertisementFlags() {
        val ad = RawAd(0, "AA:BB:CC:DD:EE:FF", rssi = -50, bytes = "", length = 0, hash = "", candidate = "test",
            decoded = Decoded("ok", flags = 5))
        assertTrue(BusAction.Stop.supported(ad))
        assertTrue(BusAction.Door.supported(ad))
        assertFalse(BusAction.Service.supported(ad))
        assertFalse(BusAction.Stop.supported(ad.copy(decoded = Decoded("too_short", flags = 7))))
        assertFalse(BusAction.Stop.supported(null))
    }

    @Test fun replayKeepsResultsAndMarksMissingOutcomesAsUnknown() {
        val index = CaptureIndex()
        fun event(type: String, op: String, data: kotlinx.serialization.json.JsonObject) =
            Event(sessionId = "test", sequence = 1, eventId = "test-1", type = type,
                timestampUtc = "2026-10-23T22:00:00Z", elapsedRealtimeNanos = 0,
                profileId = "bus", operationId = op, data = data)
        listOf("done", "interrupted").forEach { op ->
            index.apply(event("op_start", op, buildJsonObject { put("kind", "network"); put("title", "Haltewunsch") }), 1)
        }
        index.apply(event("op_result", "done", buildJsonObject {
            put("status", "ok"); put("detail", "HTTP 200 · status: true")
        }), 2)
        index.markUnfinishedOperations()
        val ops = index.snapshot().ops.associateBy { it.id }
        assertEquals("ok", ops.getValue("done").status)
        assertEquals("HTTP 200 · status: true", ops.getValue("done").detail)
        assertEquals("error", ops.getValue("interrupted").status)
        assertTrue(ops.getValue("interrupted").detail.contains("Zustellung unbekannt"))
    }
}
