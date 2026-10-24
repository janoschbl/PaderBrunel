package dev.padermodern.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

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

    private fun url(path: String, params: Map<String, String?>): HttpUrl {
        val b = (BASE + path).toHttpUrl().newBuilder()
            .addQueryParameter("tenant", "pader")
            .addQueryParameter("bundleIdentifier", "de.ivanto.padersprinter")
            .addQueryParameter("appVersion", "Android ${Build.VERSION.RELEASE}---8.7.8-9872")
        params.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }

    private suspend fun get(path: String, params: Map<String, String?> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url(path, params)).header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("Server antwortet mit ${resp.code}")
                resp.body.string()
            }
        }

    fun formatDate(dt: ZonedDateTime): String = dt.withZoneSameInstant(berlin).format(dateFormat)

    suspend fun searchStations(name: String, onlyStations: Boolean = false): List<Station> =
        json.decodeFromString(
            get(
                "stations",
                mapOf(
                    "name" to name,
                    "showStreets" to "true",
                    "onlyKnown" to "false",
                    "onlyStations" to onlyStations.toString(),
                ),
            ),
        )

    suspend fun nearbyStations(lat: Double, lon: Double, distance: Int = 800): List<Station> =
        json.decodeFromString(
            get(
                "stations",
                mapOf(
                    "latitude" to lat.toString(),
                    "longitude" to lon.toString(),
                    "distance" to distance.toString(),
                ),
            ),
        )

    suspend fun mapObjects(lat: Double, lon: Double, distance: Int, limit: Int = 200): List<Station> =
        json.decodeFromString(
            get(
                "mapobjects",
                mapOf(
                    "mapObjectVersion" to "1",
                    "limit" to limit.toString(),
                    "latitude" to lat.toString(),
                    "longitude" to lon.toString(),
                    "distance" to distance.toString(),
                    "services" to "STATION",
                ),
            ),
        )

    suspend fun departures(stationId: String, at: ZonedDateTime = ZonedDateTime.now()): List<Departure> =
        json.decodeFromString<DepartureResponse>(
            get(
                "departuresinfo",
                mapOf("stationID" to stationId, "date" to formatDate(at), "sortBy" to "departureDate"),
            ),
        ).departures

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
    ): List<Route> {
        val params = buildMap {
            putAll(stationParams("origin", from))
            putAll(stationParams("destination", to))
            put("dateType", if (arrival) "1" else "0")
            put("date", formatDate(at))
            put("maxChanges", maxChanges.toString())
            put("showWalkway", "true")
            put("interchange", "true")
            put("indoor360", "false")
        }
        return json.decodeFromString<RoutesResponse>(get("routes", params)).routes
    }

    suspend fun trafficReports(): List<TrafficReport> = json.decodeFromString(get("infos"))

    suspend fun linePlans(): List<LinePlan> = json.decodeFromString(get("lineplans/"))

    fun linePlanUrl(id: String): String = url("lineplans/$id", emptyMap()).toString()

    suspend fun lines(): List<Line> = json.decodeFromString(get("trafficIncident/lines"))

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
