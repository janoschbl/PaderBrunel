package dev.padermodern.capture

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class Run(
    val lineId: Int?, val destinationId: Int?, val nextStopId: Int?, val course: Int?,
    val line: String?, val destination: String?, val nextStop: String?,
    val firstMs: Long, val lastMs: Long, val count: Int,
)

data class Op(
    val id: String, val profileId: String?, val kind: String, val title: String,
    val startedMs: Long, val finishedMs: Long? = null, val status: String = "running", val detail: String = "",
)

data class Note(val ms: Long, val profileId: String?, val text: String)

data class GattService(val uuid: String, val characteristics: List<String>)

data class GattRead(val ms: Long, val characteristic: String, val status: Int?, val bytes: String?, val error: String?)

data class BusProfile(
    val id: String,
    val addresses: List<String>,
    val hardwareIds: List<Int>,
    val protocols: List<Int>,
    val companies: List<Int>,
    val packets: Int,
    val failures: Int,
    val firstMs: Long,
    val lastMs: Long,
    val rssiMin: Int,
    val rssiMax: Int,
    val runs: List<Run>,
    val ads: List<RawAd>,
    val services: List<GattService>,
    val reads: List<GattRead>,
    val related: List<String>,
    val test: Boolean,
) {
    val current: Run? get() = runs.lastOrNull()
    val title: String get() = hardwareIds.firstOrNull()?.let { "Bus HW $it" } ?: "Bus-Kandidat"
    val shortAddress: String get() = addresses.firstOrNull().orEmpty()
}

data class Snapshot(
    val profiles: List<BusProfile> = emptyList(),
    val ops: List<Op> = emptyList(),
    val notes: List<Note> = emptyList(),
    val totalAds: Int = 0,
    val failedAds: Int = 0,
)

fun lineLabel(id: Int?, name: String?): String = name ?: if (id == null) "Unbekannte Linie" else "Linie #$id"
fun destinationLabel(id: Int?, name: String?): String = name ?: if (id == null) "Unbekanntes Ziel" else "Ziel #$id"
fun stopLabel(id: Int?, name: String?): String = name ?: if (id == null) "Unbekannter Halt" else "Halt #$id"

class CaptureIndex {
    private class Mutable(val id: String, val test: Boolean, val firstMs: Long) {
        val addresses = linkedSetOf<String>()
        val hardwareIds = linkedSetOf<Int>()
        val protocols = linkedSetOf<Int>()
        val companies = linkedSetOf<Int>()
        var packets = 0
        var failures = 0
        var lastMs = firstMs
        var rssiMin = Int.MAX_VALUE
        var rssiMax = Int.MIN_VALUE
        val runs = ArrayDeque<Run>()
        val ads = ArrayDeque<RawAd>()
        val services = mutableListOf<GattService>()
        val reads = mutableListOf<GattRead>()
    }

    private val buses = linkedMapOf<String, Mutable>()
    private val ops = linkedMapOf<String, Op>()
    private val notes = mutableListOf<Note>()
    private var totalAds = 0
    private var failedAds = 0
    var dirty = true
        private set

    fun apply(e: Event, ms: Long) {
        dirty = true
        val d = e.data
        when (e.type) {
            "advertisement" -> advertisement(e.profileId ?: return, CaptureJson.decodeFromJsonElement(RawAd.serializer(), d))
            "op_start" -> ops[e.operationId ?: return] = Op(
                e.operationId, e.profileId, d.str("kind") ?: "?", d.str("title") ?: "", ms,
            )
            "op_result" -> ops[e.operationId ?: return]?.let {
                ops[it.id] = it.copy(finishedMs = ms, status = d.str("status") ?: "ok", detail = d.str("detail").orEmpty())
            }
            "gatt_services" -> buses[e.profileId]?.services?.let { list ->
                list.clear()
                d["services"]?.jsonArray?.forEach { s ->
                    val o = s.jsonObject
                    list += GattService(o.str("uuid").orEmpty(), o["characteristics"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
                }
            }
            "gatt_read" -> buses[e.profileId]?.reads?.add(
                GattRead(ms, d.str("characteristic").orEmpty(), d["status"]?.jsonPrimitive?.longOrNull?.toInt(), d.str("bytes"), d.str("error")),
            )
            "note" -> notes += Note(ms, e.profileId, d.str("text").orEmpty())
        }
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    fun markUnfinishedOperations() {
        ops.replaceAll { _, op ->
            if (op.status == "running") op.copy(status = "error", detail =
                "Kein Ergebnis im Log. Sitzung beendet oder unterbrochen; Zustellung unbekannt. Nicht automatisch wiederholt.")
            else op
        }
        dirty = true
    }

    private fun advertisement(profileId: String, ad: RawAd) {
        totalAds++
        if (!ad.decoded.ok) failedAds++
        val b = buses.getOrPut(profileId) { Mutable(profileId, ad.test, ad.receivedMs) }
        b.addresses += ad.address
        ad.decoded.hardwareId?.let { b.hardwareIds += it }
        ad.decoded.protocol?.let { b.protocols += it }
        ad.decoded.company?.let { b.companies += it }
        b.packets++
        if (!ad.decoded.ok) b.failures++
        b.lastMs = ad.receivedMs
        b.rssiMin = minOf(b.rssiMin, ad.rssi)
        b.rssiMax = maxOf(b.rssiMax, ad.rssi)
        b.ads.addFirst(ad)
        if (b.ads.size > MAX_ADS) b.ads.removeLast()
        val d = ad.decoded
        val last = b.runs.lastOrNull()
        if (last != null && last.lineId == d.lineId && last.destinationId == d.destinationId &&
            last.nextStopId == d.nextStopId && last.course == d.course
        ) {
            b.runs[b.runs.lastIndex] = last.copy(lastMs = ad.receivedMs, count = last.count + 1)
        } else {
            b.runs.addLast(Run(d.lineId, d.destinationId, d.nextStopId, d.course, ad.line, ad.destination, ad.nextStop, ad.receivedMs, ad.receivedMs, 1))
            if (b.runs.size > MAX_RUNS) b.runs.removeFirst()
        }
    }

    fun snapshot(): Snapshot {
        dirty = false
        val byHardware = buses.values.flatMap { b -> b.hardwareIds.map { it to b.id } }.groupBy({ it.first }, { it.second })
        return Snapshot(
            profiles = buses.values.map { b ->
                BusProfile(
                    b.id, b.addresses.toList(), b.hardwareIds.toList(), b.protocols.toList(), b.companies.toList(),
                    b.packets, b.failures, b.firstMs, b.lastMs, b.rssiMin, b.rssiMax,
                    b.runs.toList(), b.ads.toList(), b.services.toList(), b.reads.toList(),
                    b.hardwareIds.flatMap { byHardware[it].orEmpty() }.filter { it != b.id }.distinct(), b.test,
                )
            }.sortedByDescending { it.lastMs },
            ops = ops.values.sortedByDescending { it.startedMs },
            notes = notes.sortedByDescending { it.ms },
            totalAds = totalAds, failedAds = failedAds,
        )
    }

    companion object {
        const val MAX_ADS = 300
        const val MAX_RUNS = 400
    }
}
