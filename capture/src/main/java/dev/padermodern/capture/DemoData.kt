package dev.padermodern.capture

import android.os.SystemClock
import dev.padermodern.data.BusNames
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object DemoData {
    val names = BusNames(
        lines = mapOf(1 to "4", 2 to "9", 3 to "11", 4 to "50", 5 to "100", 6 to "S 5"),
        destinations = mapOf(
            10 to "Elsen Bf", 11 to "Schloß Neuhaus Bf", 12 to "Hauptbahnhof", 13 to "Universität",
            14 to "Paderborn, Technologiepark Zukunftsmeile Süd über Rathaus und Kasseler Tor (Sonderfahrt)",
        ),
        stops = mapOf(100 to "Rathaus", 101 to "Kamp", 102 to "Hauptbahnhof Bstg. A3", 103 to "Detmolder Tor", 104 to "Heinz Nixdorf Institut", 105 to "Westerntor"),
    )

    private class Bus(
        val address: String, val hardwareId: Int, var line: Int, var dest: Int, val stops: List<Int>, val startTick: Int,
        val course: Int, val protocol: Int = 16, val company: Int = 4, val simulator: Boolean = false,
    )

    private val buses = listOf(
        Bus("C4:1F:5A:00:00:01", 4711, 1, 10, listOf(100, 101, 102, 103), 0, 7),
        Bus("C4:1F:5A:00:00:02", 4712, 1, 11, listOf(103, 102, 101, 100), 2, 8),
        Bus("C4:1F:5A:00:00:03", 3300, 2, 12, listOf(104, 105, 100), 1, 31),
        Bus("C4:1F:5A:00:00:04", 5001, 777, 999, listOf(555, 556), 3, 9),
        Bus("C4:1F:5A:00:00:05", 4711, 1, 10, listOf(102, 103), 25, 7),
        Bus("C4:1F:5A:00:00:06", 6100, 4, 14, listOf(104, 100, 105), 4, 12),
        Bus("C4:1F:5A:00:00:07", 9, 5, 13, listOf(100), 5, 1, simulator = true),
        Bus("C4:1F:5A:00:00:08", 7700, 6, 12, listOf(102), 6, 2, protocol = 18),
        Bus("C4:1F:5A:00:00:09", 7701, 3, 12, listOf(102), 7, 3, company = 9),
    )

    private fun record(b: Bus, tick: Int): ByteArray {
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        val data = byteArrayOf(if (b.simulator) 0x79 else 0x78, 0x7d) +
            byteArrayOf((b.protocol shr 8).toByte(), b.protocol.toByte(), if (b.hardwareId == 4711) 0x07 else 0x05, b.company.toByte()) +
            le(b.line) + le(b.dest) + le(b.stops[(tick / 4) % b.stops.size]) + le(b.hardwareId) + le(b.course) + byteArrayOf(0x00, 0x01)
        return byteArrayOf(2, 1, 6, 3, 3, 0x1f, 0x1f, (data.size + 1).toByte(), 0xff.toByte()) + data
    }

    private fun scan(address: String, bytes: ByteArray, rssi: Int, name: String? = null) = RawScan(
        receivedMs = System.currentTimeMillis(), bytes = bytes, address = address, addressType = null, name = name, rssi = rssi,
        scanTimestampNanos = SystemClock.elapsedRealtimeNanos(), connectable = true, legacy = false, primaryPhy = 1, secondaryPhy = 0,
        sid = null, txPower = null, dataStatus = 0, test = true,
    )

    suspend fun run(submit: (RawScan) -> Unit, emit: (String, String?, String?, JsonObject) -> Unit) {
        emit("note", null, null, buildJsonObject { put("text", "Demo: Haltestelle Rathaus, Beobachtungspunkt Bstg. A3") })
        emit("op_start", null, "demo-net", buildJsonObject { put("kind", "network"); put("title", "GET service.ivanto.de/srs/api/v1/bluetooth/?filename=Padersprinter") })
        emit("op_result", null, "demo-net", buildJsonObject { put("status", "ok"); put("detail", "Demo-Tabelle, 0 Byte") })
        val rnd = Random(42)
        var tick = 0
        while (true) {
            for (b in buses) {
                if (tick < b.startTick) continue
                if (b.hardwareId == 3300 && tick == 30) { b.line = 3; b.dest = 12 }
                repeat(if (rnd.nextInt(4) == 0) 2 else 1) { submit(scan(b.address, record(b, tick), -45 - rnd.nextInt(40), if (b.hardwareId == 4711) "ivanto-4711" else null)) }
            }
            if (tick % 9 == 5) {
                submit(scan("DE:AD:BE:EF:00:01", byteArrayOf(2, 1, 6, 3, 3, 0x1f, 0x1f, 6, 0xff.toByte(), 0x78, 0x7d, 0, 16, 5), -80))
                submit(scan("DE:AD:BE:EF:00:02", byteArrayOf(2, 1, 6, 3, 3, 0x1f, 0x1f), -77))
                submit(scan("DE:AD:BE:EF:00:03", byteArrayOf(2, 1, 6, 3, 3, 0x1f, 0x1f, 40, 0xff.toByte(), 1), -90))
            }
            tick++
            delay(if (tick < 40) 60 else 1000)
        }
    }

    suspend fun inspect(profileId: String, op: String, emit: (String, String?, String?, JsonObject) -> Unit) {
        emit("op_start", profileId, op, buildJsonObject { put("kind", "connect"); put("title", "GATT-Verbindung (nur Lesen), Demo") })
        delay(700)
        emit("connection_state", profileId, op, buildJsonObject { put("status", 0); put("newState", 2) })
        delay(500)
        emit("gatt_services", profileId, op, buildJsonObject {
            put("services", JsonArray(listOf(
                buildJsonObject { put("uuid", "00001800-0000-1000-8000-00805f9b34fb"); put("characteristics", JsonArray(listOf(JsonPrimitive("00002a00-0000-1000-8000-00805f9b34fb props=0x02 desc=[]")))) },
                buildJsonObject { put("uuid", "00001f1f-0000-1000-8000-00805f9b34fb"); put("characteristics", JsonArray(listOf(
                    JsonPrimitive("00001f20-0000-1000-8000-00805f9b34fb props=0x08 desc=[]"),
                    JsonPrimitive("00002020-0000-1000-8000-00805f9b34fb props=0x02 desc=[]"),
                ))) },
            )))
        })
        delay(400)
        if (profileId.hashCode() % 2 == 0) {
            emit("gatt_read", profileId, op, buildJsonObject { put("characteristic", "00002020-0000-1000-8000-00805f9b34fb"); put("status", 0); put("bytes", "0b44656d6f20486f6c7a68617573656e"); put("length", 16) })
            emit("op_result", profileId, op, buildJsonObject { put("status", "ok"); put("detail", "Gelesen: 16 Byte · 1620 ms · GATT geschlossen") })
        } else {
            emit("gatt_read", profileId, op, buildJsonObject { put("characteristic", "00002020-0000-1000-8000-00805f9b34fb"); put("status", 5); put("error", "Callback-Status 5") })
            emit("op_result", profileId, op, buildJsonObject { put("status", "blocked"); put("detail", "Authentifizierung nötig (Status 5), nicht fortgesetzt · 1700 ms · GATT geschlossen") })
        }
    }
}
