package dev.padermodern.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val code: Int? = null) : IOException(message)

object SrsApi {
    private const val BASE = "https://service.ivanto.de/srs/api/v1/"
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .build()

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stationCache = RequestCache<String, List<Station>>(requestScope, 300_000, 64)
    private val departureRequests = DepartureRequests(requestScope, { id, minute ->
        request<DepartureResponse>(
            "departuresinfo",
            mapOf("stationID" to id, "date" to formatDate(minute), "sortBy" to "departureDate"),
        ).departures
    })
    private val routeCache = RequestCache<String, List<Route>>(requestScope, 45_000, 48)
    // Slow two-change queries must not occupy every slot ahead of the fast pass.
    private val routeRequests = RouteRequestLimiter()
    private val hedgeBudget = HedgeBudget()

    private fun url(path: String, params: Map<String, String?>): HttpUrl {
        val b = (BASE + path).toHttpUrl().newBuilder()
            .addQueryParameter("tenant", "pader")
            .addQueryParameter("bundleIdentifier", "de.ivanto.padersprinter")
            .addQueryParameter("appVersion", "Android ${Build.VERSION.RELEASE}---8.7.8-9872")
        params.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    // 502s and timeouts are usually transient on this server; one retry after a short pause.
    private suspend fun get(path: String, params: Map<String, String?> = emptyMap()): String =
        try {
            getOnce(path, params)
        } catch (e: IOException) {
            if (!retryable(e)) throw e
            Pm.note("retry $path after ${e.message}")
            delay(SearchPolicy.RETRY_DELAY_MILLIS)
            getOnce(path, params)
        }

    private fun retryable(e: IOException): Boolean =
        e !is ApiException || e.code == null || e.code >= 500

    private suspend fun getOnce(path: String, params: Map<String, String?>): String = getOnce(url(path, params))

    /** Plain GET on another host, used for the EFA. Same client, same single retry. */
    suspend fun getUrl(absolute: String, params: Map<String, String>): String {
        val u = absolute.toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return try {
            getOnce(u)
        } catch (e: IOException) {
            if (!retryable(e)) throw e
            delay(SearchPolicy.RETRY_DELAY_MILLIS)
            getOnce(u)
        }
    }

    private suspend fun getOnce(url: HttpUrl): String =
        suspendCancellableCoroutine { continuation ->
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            val call = client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            if (!it.isSuccessful) throw ApiException("Server antwortet mit ${it.code}", it.code)
                            it.body.string()
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    // The 500 KB payloads are parsed on Default so slow parsing never occupies the IO threads that wait for sockets.
    private suspend inline fun <reified T> request(path: String, params: Map<String, String?> = emptyMap()): T {
        val t0 = System.nanoTime()
        val body = get(path, params)
        val t1 = System.nanoTime()
        return withContext(Dispatchers.Default) { json.decodeFromString<T>(body) }.also {
            Pm.note("$path net=${(t1 - t0) / 1_000_000} ms parse=${(System.nanoTime() - t1) / 1_000_000} ms bytes=${body.length}")
        }
    }

    /** Opens the TLS connection ahead of the first search so OkHttp pools it. */
    suspend fun warmUp() {
        try { Pm.time("warmup") { get("lineplans/") } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }

    fun formatDate(dt: ZonedDateTime): String = dt.withZoneSameInstant(berlin).format(dateFormat)

    suspend fun searchStations(name: String, onlyStations: Boolean = false): List<Station> =
        stationCache.get("search|${name.trim()}|$onlyStations") {
            val efa = try {
                Pm.time("efa stations") { EfaApi.searchStations(name.trim()) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Pm.note("efa stations failed: $e")
                null
            }
            if (!efa.isNullOrEmpty() && (!onlyStations || efa.any { it.isStop })) {
                return@get if (onlyStations) efa.filter { it.isStop } else efa
            }
            request(
                "stations",
                mapOf(
                    "name" to name.trim(),
                    "showStreets" to "true",
                    "onlyKnown" to "false",
                    "onlyStations" to onlyStations.toString(),
                ),
            )
        }

    suspend fun nearbyStations(lat: Double, lon: Double, distance: Int = 800): List<Station> =
        stationCache.get("nearby|$lat|$lon|$distance") {
            request(
                "stations",
                mapOf(
                    "latitude" to lat.toString(),
                    "longitude" to lon.toString(),
                    "distance" to distance.toString(),
                ),
            )
        }

    suspend fun mapObjects(lat: Double, lon: Double, distance: Int, limit: Int = 200): List<Station> =
        request(
            "mapobjects",
            mapOf(
                "mapObjectVersion" to "1",
                "limit" to limit.toString(),
                "latitude" to lat.toString(),
                "longitude" to lon.toString(),
                "distance" to distance.toString(),
                "services" to "STATION",
            ),
        )

    // User refreshes bypass completed warm snapshots, but overlapping requests still share HTTP work.
    suspend fun departures(stationId: String, at: ZonedDateTime = ZonedDateTime.now(), fresh: Boolean = true): List<Departure> =
        departureRequests.get(stationId, at, fresh)

    private fun stationParams(prefix: String, s: Station): Map<String, String?> = buildMap {
        s.type?.takeIf { it.isNotEmpty() }?.let { put("${prefix}Type", it) }
        s.id?.takeIf { it.isNotEmpty() }?.let { put("${prefix}ID", it) }
        // server expects the misspelled Localtiy
        s.locality?.takeIf { it.isNotEmpty() }?.let { put("${prefix}Localtiy", it) }
        s.name?.takeIf { it.isNotEmpty() }?.let { put("${prefix}Name", it) }
        if (s.type != "stop") {
            put(
                "${prefix}Coordinates",
                String.format(Locale.ENGLISH, "%f:%f:WGS84[DD.DDDDD]", s.longitude, s.latitude),
            )
            s.name?.takeIf { it.isNotEmpty() }?.let { put("${prefix}CoordinatesName", it) }
        }
    }

    suspend fun routesBetween(
        from: Station,
        to: Station,
        at: ZonedDateTime,
        arrival: Boolean,
        maxChanges: Int,
        rules: SearchRules = SearchRules(),
    ): List<Route> {
        val minute = at.withSecond(0).withNano(0)
        val params = buildMap {
            putAll(stationParams("origin", from))
            putAll(stationParams("destination", to))
            put("dateType", if (arrival) "1" else "0")
            put("date", formatDate(minute))
            put("maxChanges", maxChanges.toString())
            put("showWalkway", "true")
            put("interchange", "true")
            put("indoor360", "false")
        }
        return routeCache.get("$params|$rules") {
            // Own backend first (Transitous timetable + VRR live, under 1 KB), then the DELFI EFA, then the slow provider.
            val own = try {
                Pm.time("backend routes") { BackendApi.routesBetween(from, to, at, arrival, maxChanges, rules) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Pm.note("backend failed: ${e.message}")
                null
            }
            if (!own.isNullOrEmpty()) return@get own
            // The DELFI EFA answers in about 1 s, the provider proxy needs 15 to 40 s. The provider stays as fallback.
            val efa = if (BackendApi.providerOnly) null else try {
                Pm.time("efa routes") { EfaApi.routesBetween(from, to, at, arrival, maxChanges) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Pm.note("efa failed: ${e.message}")
                null
            }
            if (!efa.isNullOrEmpty()) return@get efa
            routeRequests.run(maxChanges) {
                // Individual requests sometimes stall for 20 s while the same request takes 1.5 s.
                hedged(SearchPolicy.HEDGE_AFTER_MILLIS, hedgeBudget) { request<RoutesResponse>("routes", params).routes }
            }
        }
    }

    suspend fun trafficReports(): List<TrafficReport> = request("infos")

    suspend fun linePlans(): List<LinePlan> = request("lineplans/")

    fun linePlanUrl(id: String): String = url("lineplans/$id", emptyMap()).toString()

    suspend fun lines(): List<Line> = request("trafficIncident/lines")

    suspend fun bluetoothDatabase(): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("bluetooth/", mapOf("filename" to "Padersprinter"))).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException("Bluetooth-Tabelle: Server antwortet mit ${response.code}")
            val output = ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 1_048_576) throw ApiException("Bluetooth-Tabelle ist zu groß")
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
    }
}
