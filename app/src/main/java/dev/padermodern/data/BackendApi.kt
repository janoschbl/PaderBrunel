package dev.padermodern.data

import dev.padermodern.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@Serializable data class BVia(val id: String = "", val name: String = "", val arr: Long = 0, val dep: Long = 0)

@Serializable
data class BLeg(
    val mode: String = "",
    val line: String = "",
    val to: String = "",
    val from_name: String = "",
    val to_name: String = "",
    val from_id: String = "",
    val to_id: String = "",
    val from_pos: List<Double> = emptyList(),
    val to_pos: List<Double> = emptyList(),
    /** the ride's track as an encoded polyline (precision 5), empty for walks and older backends */
    val geom: String = "",
    val dep: Long = 0,
    val arr: Long = 0,
    val via: List<BVia> = emptyList(),
)

@Serializable data class BItin(val dep: Long = 0, val arr: Long = 0, val changes: Int = 0, val legs: List<BLeg> = emptyList())
@Serializable data class BPlan(val itins: List<BItin> = emptyList())
/** `src` is "vrr", "transitous", or "arr" (only the arrival board knew the leg). */
@Serializable data class BLegLive(val itin: Int, val leg: Int, val dep: Long, val arr: Long, val cancelled: Boolean = false, val src: String = "")
@Serializable data class BLive(val legs: List<BLegLive> = emptyList(), val gone: List<Int> = emptyList())

/** Push message: `set` entries are [planned minute, line, expected unix seconds], `clear` entries [planned minute, line]. */
@Serializable
data class BDelta(
    val stop: String = "",
    val snap: Boolean = false,
    val set: List<List<JsonPrimitive>> = emptyList(),
    val clear: List<List<JsonPrimitive>> = emptyList(),
)

/**
 * Own backend: timetable from Transitous, live data from VRR (NRW), both merged and cached server side.
 * A connection search costs well under 1 KB on the wire; live changes arrive as pushes.
 */
object BackendApi {
    /** Debug switch to compare against the provider-only behaviour. */
    @Volatile var forceOff = false
    /** Debug: also skip the direct EFA path, so only the provider answers (the original behaviour). */
    @Volatile var providerOnly = false
    val enabled: Boolean get() = BuildConfig.BACKEND_URL.isNotEmpty() && !forceOff

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    internal val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    /** Bytes received over the backend since the process started, read from the response sizes. */
    @Volatile var bytesReceived = 0L

    private fun url(path: String, params: Map<String, String>) =
        (BuildConfig.BACKEND_URL.trimEnd('/') + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()

    private suspend fun <T> call(req: Request, read: (BufferedSource) -> T): T {
        val call = client.newCall(req)
        return blocking(call) {
            call.execute().use { r ->
                if (!r.isSuccessful) throw ApiException("Backend antwortet mit ${r.code}", r.code)
                read(r.body.source())
            }
        }
    }

    /**
     * Runs a blocking OkHttp read on IO and cancels the call the moment the coroutine is cancelled. A completion
     * handler on the job would only fire after the blocked read returned, which for a stream is never.
     */
    internal suspend fun <T> blocking(call: okhttp3.Call, block: () -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val killer = launch { try { awaitCancellation() } finally { call.cancel() } }
            try { block() } finally { killer.cancel() }
        }
    }

    /** Global DELFI id (`de:05774:7000`) of a stop, or null when the backend cannot place it. */
    fun globalId(s: Station): String? {
        s.globalId?.takeIf { it.startsWith("de:") }?.let { return it.split(":").take(3).joinToString(":") }
        val id = s.id ?: return null
        if (id.length == 8 && id.startsWith("232")) return "de:05774:${id.drop(3).trimStart('0').ifEmpty { "0" }}"
        return null
    }

    private fun place(s: Station): String? = when {
        s.isRealStop -> globalId(s)
        s.latitude != 0.0 || s.longitude != 0.0 -> "%.5f,%.5f".format(java.util.Locale.ENGLISH, s.latitude, s.longitude)
        else -> null
    }

    /** Null when this search cannot be answered here (arrival search, unknown place), so the caller falls back. */
    suspend fun routesBetween(
        from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int? = null, rules: SearchRules = SearchRules(),
    ): List<Route>? {
        if (!enabled) return null
        val f = place(from) ?: return null
        val t = place(to) ?: return null
        // positions let the backend find a platform id for stops that Transitous knows only by platform
        fun pos(s: Station) = "%.5f,%.5f".format(java.util.Locale.ENGLISH, s.latitude, s.longitude).takeIf { s.latitude != 0.0 }
        val params = buildMap {
            put("from", f); put("to", t); put("time", at.toEpochSecond().toString())
            if (from.isRealStop) pos(from)?.let { put("from_pos", it) }
            if (to.isRealStop) pos(to)?.let { put("to_pos", it) }
            if (rules.minTransferMin > 0) put("transfer", rules.minTransferMin.toString())
            maxChanges?.let { put("changes", it.toString()) }
            if (arrival) put("arrive", "1")
            put("variants", rules.variants.toString())
        }
        val req = Request.Builder().url(url("/v1/plan", params)).build()
        val (plan, live) = try {
            call(req) { src ->
                val first = src.readUtf8Line() ?: throw ApiException("Backend: leere Antwort")
                // the live frame is optional: the stream ends after frame 1 when every board lookup failed
                val second = runCatching { src.readUtf8Line() }.getOrNull()
                Pm.note("backend frames ${first.length} + ${second?.length ?: 0} chars")
                json.decodeFromString<BPlan>(first) to second?.let { runCatching { json.decodeFromString<BLive>(it) }.getOrNull() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A stalled stream (bad mobile link, a proxy holding the body) used to drop the search to the old stop
            // pairs, one way per train. The timetable alone is one small JSON answer and usually still gets through.
            Pm.note("backend stream failed (${e.message}), asking for the timetable only")
            call(Request.Builder().url(url("/v1/plan/static", params)).build()) { src -> json.decodeFromString<BPlan>(src.readUtf8()) } to null
        }
        return toRoutes(plan, live, from.name, to.name)
    }

    // ---- mapping to the app's models ----

    private fun iso(unix: Long): String = ZonedDateTime.ofInstant(Instant.ofEpochSecond(unix), berlin).iso()

    private fun providerId(stopId: String): String? {
        val gid = stopId.removePrefix("de-DELFI_").split(":").take(3).joinToString(":")
        if (!gid.startsWith("de:")) return null
        // districts other than Paderborn have no known provider id, the DELFI id stays as the key
        return if (gid.startsWith("de:05774:")) "232" + gid.substringAfterLast(":").padStart(5, '0') else gid
    }

    private fun station(id: String, name: String, pos: List<Double>, unix: Long, expected: Long?, canceled: Boolean = false): Station {
        val gid = id.removePrefix("de-DELFI_").split(":").take(3).joinToString(":").takeIf { it.startsWith("de:") }
        val planned = iso(unix)
        return Station(
            type = if (id.isEmpty()) "address" else "stop", id = providerId(id), globalId = gid, name = name,
            latitude = pos.getOrNull(0) ?: 0.0, longitude = pos.getOrNull(1) ?: 0.0,
            date = planned, expectedDate = expected?.takeIf { it != unix }?.let(::iso), canceled = canceled,
        )
    }

    private fun typeOf(mode: String) = when {
        mode == "WALK" -> 100
        mode.contains("TRAM") -> 4
        mode.contains("SUBWAY") || mode.contains("METRO") -> 2
        mode == "BUS" || mode == "COACH" -> 3
        else -> 1
    }

    private fun typeName(mode: String) = when (typeOf(mode)) { 4 -> "Straßenbahn"; 2 -> "U-Bahn"; 3 -> "Bus"; 100 -> null; else -> "Zug" }

    /** [fromName] and [toName] replace Transitous' "START" and "END" when the search began or ended at a position. */
    internal fun toRoutes(plan: BPlan, live: BLive?, fromName: String? = null, toName: String? = null): List<Route> {
        val gone = live?.gone?.toSet().orEmpty()
        val liveBy = live?.legs.orEmpty().associateBy { it.itin to it.leg }
        return plan.itins.mapIndexedNotNull { i, it ->
            if (i in gone) return@mapIndexedNotNull null
            val parts = it.legs.mapIndexed { k, l ->
                val lv = liveBy[i to k]
                val walk = l.mode == "WALK"
                val stops = buildList {
                    // a cancelled leg stays in the list, marked "Fällt aus" by its first stop
                    val off = lv?.cancelled == true
                    val fromLabel = if (l.from_id.isEmpty() && k == 0) fromName ?: l.from_name else l.from_name
                    val toLabel = if (l.to_id.isEmpty() && k == it.legs.lastIndex) toName ?: l.to_name else l.to_name
                    add(station(l.from_id, fromLabel, l.from_pos, l.dep, lv?.dep, off))
                    if (!walk) l.via.forEach { v -> add(station(v.id, v.name, emptyList(), v.arr, null)) }
                    add(station(l.to_id, toLabel, l.to_pos, l.arr, lv?.arr, off))
                }
                PartialRoute(
                    clazz = if (walk) "walkway" else "vehicleRide",
                    stations = stops,
                    type = typeOf(l.mode),
                    typeName = typeName(l.mode),
                    lineName = if (walk) null else listOfNotNull(typeName(l.mode), l.line.takeIf { s -> s.isNotEmpty() }).joinToString(" "),
                    shortName = l.line.takeIf { s -> !walk && s.isNotEmpty() },
                    encodedPath = l.geom.takeIf { s -> s.isNotEmpty() },
                    trainLabel = if (l.mode == "HIGHSPEED_RAIL") "ICE" else null,
                    lineDirection = l.to.takeIf { s -> !walk && s.isNotEmpty() },
                    transferTime = if (walk) ((l.arr - l.dep + 59) / 60).toInt() else null,
                    departureDate = iso(l.dep), arrivalDate = iso(l.arr),
                    expectedDepartureDate = lv?.dep?.takeIf { d -> d != l.dep }?.let(::iso),
                    expectedArrivalDate = lv?.arr?.takeIf { a -> a != l.arr }?.let(::iso),
                )
            }
            // delays of the first and last ride move the whole connection, including a walk before or after it
            val firstRide = it.legs.indexOfFirst { l -> l.mode != "WALK" }
            val lastRide = it.legs.indexOfLast { l -> l.mode != "WALK" }
            val depShift = liveBy[i to firstRide]?.let { lv -> lv.dep - it.legs[firstRide].dep } ?: 0
            val arrShift = liveBy[i to lastRide]?.let { lv -> lv.arr - it.legs[lastRide].arr } ?: 0
            val minutes = Duration.ofSeconds(it.arr + arrShift - (it.dep + depShift)).toMinutes()
            Route(
                partialRoutes = parts,
                changes = it.changes,
                ticketPriceAvailable = false,
                duration = "%02d:%02d".format(minutes / 60, minutes % 60),
                departureDate = iso(it.dep), arrivalDate = iso(it.arr),
                expectedDepartureDate = if (depShift != 0L) iso(it.dep + depShift) else null,
                expectedArrivalDate = if (arrShift != 0L) iso(it.arr + arrShift) else null,
            )
        }
    }
}

/**
 * Push client. One SSE connection per watched set of stops: a snapshot on connect, then only changes.
 * Nothing is polled. Reconnects with backoff, and the caller cancels the job when the screen goes away.
 */
object LiveWatch {
    private val sseClient = BackendApi.client.newBuilder().readTimeout(75, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(stops: List<String>, onDelta: (BDelta) -> Unit) {
        if (!BackendApi.enabled || stops.isEmpty()) return
        val req = Request.Builder()
            .url((BuildConfig.BACKEND_URL.trimEnd('/') + "/v1/watch").toHttpUrl().newBuilder().addQueryParameter("stops", stops.joinToString(",")).build())
            .header("Accept", "text/event-stream").build()
        var backoff = 2_000L
        while (currentCoroutineContext().isActive) {
            try {
                val call = sseClient.newCall(req)
                BackendApi.blocking(call) {
                    call.execute().use { r ->
                        if (!r.isSuccessful) throw ApiException("Push ${r.code}", r.code)
                        backoff = 2_000L
                        val src = r.body.source()
                        while (true) {
                            val line = src.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                val d = runCatching { json.decodeFromString<BDelta>(line.removePrefix("data: ")) }.getOrNull() ?: continue
                                Pm.note("push ${d.stop} snap=${d.snap} set=${d.set.size} clear=${d.clear.size} (${line.length} chars)")
                                onDelta(d)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Pm.note("push connection lost: ${e.message}")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }
}
