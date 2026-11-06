package dev.padermodern.data

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// replays capture exports: BUS_LOG_DIR=/path/to/unzipped/sessions ./gradlew :app:testDebugUnitTest
class BusLogReplayTest {
    private val leg8 = PartialRoute(shortName = "8", lineDirection = "Neuenbeken", stations = listOf("Hauptbahnhof", "Westerntor",
        "Rathausplatz", "Kamp", "Am Bogen", "Gierstor", "Fröbelstraße", "Langer Weg").map { Station(name = it, locality = "Paderborn") })

    private class Read(val ms: Long, val version: Int?, val data: VehicleData)

    @Test fun everyRecordedBusPacketDecodesAndLine8IsConfirmed() {
        val root = System.getenv("BUS_LOG_DIR")?.let(::File)
        assumeTrue("BUS_LOG_DIR not set", root?.isDirectory == true)
        val logs = root!!.walk().filter { it.name == "events.jsonl" }.sortedBy { it.path }.toList()
        assumeTrue(logs.isNotEmpty())
        val report = StringBuilder()
        var undecoded = 0
        var matches = 0
        for (log in logs) {
            val addresses = mutableMapOf<String, String>()
            val versions = mutableMapOf<String, Int?>()
            val reads = mutableMapOf<String, Read>()
            val lines = sortedMapOf<String, MutableMap<String, Int>>()
            val tracker = BusMatchTracker()
            val confirmed = mutableListOf<String>()
            log.forEachLine { text ->
                val event = Json.parseToJsonElement(text).jsonObject
                val data = event["data"]?.jsonObject ?: return@forEachLine
                val profile = event["profileId"]?.jsonPrimitive?.content
                val ms = Instant.parse(event["timestampUtc"]!!.jsonPrimitive.content).toEpochMilli()
                when (event["type"]!!.jsonPrimitive.content) {
                    "gatt_read" -> {
                        val address = addresses[profile] ?: return@forEachLine
                        val bytes = data["bytes"]?.jsonPrimitive?.content ?: return@forEachLine
                        VehicleData.parse(hex(bytes))?.let { reads[address] = Read(ms, versions[address], it) }
                    }
                    "advertisement" -> {
                        val address = data["address"]!!.jsonPrimitive.content
                        profile?.let { addresses[it] = address }
                        val raw = data["bytes"]!!.jsonPrimitive.content
                        val bus = BusAdvertisement.parse(hex(raw))
                        // the app scans with a 0x1f1f filter, records without it never reach the parser
                        val filtered = "03031f1f" in raw
                        val label = bus?.let { it.line ?: "#${it.lineId} (16)" } ?: if (filtered) "nicht dekodiert" else "ohne 0x1f1f"
                        if (bus == null && filtered) undecoded++
                        lines.getOrPut(address) { sortedMapOf() }.merge(label, 1, Int::plus)
                        bus ?: return@forEachLine
                        versions[address] = bus.dataVersion
                        // same rule as VehicleDataReader: only a read with the same data version and at most 30 s old
                        val read = reads[address]?.takeIf { it.version == bus.dataVersion && ms - it.ms in 0..30_000 } ?: return@forEachLine
                        val index = bus.match(read.data, leg8) ?: return@forEachLine
                        matches++
                        // assumes gps agrees, as it would on the real trip
                        val changed = tracker.observe(address, index, ms, index)
                        if (tracker.matched && (confirmed.isEmpty() || changed != null))
                            confirmed += "${Instant.ofEpochMilli(ms)} $address → ${leg8.stations[index].name}"
                    }
                }
            }
            report.appendLine("== ${log.parentFile?.name}")
            lines.forEach { (address, counts) -> report.appendLine("  $address $counts") }
            confirmed.distinct().forEach { report.appendLine("  bestätigt: $it") }
        }
        File("build/bus-replay.txt").apply { parentFile?.mkdirs() }.writeText(report.toString())
        println(report)
        assertEquals("packets that still fail to decode", 0, undecoded)
        assertTrue("line 8 never matched its leg", matches > 0)
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
