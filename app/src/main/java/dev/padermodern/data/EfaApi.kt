package dev.padermodern.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The provider's `routes` call is a proxy for the Westfalenfahrplan EFA and takes 15 to 40 s or fails with a pool timeout.
 * The DELFI EFA at VRR knows the same stops (stopId equals the provider id) and answers in about 1 s.
 */
object EfaApi {
    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ENGLISH)
    private val clock = DateTimeFormatter.ofPattern("HHmm", Locale.ENGLISH)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Provider ids of the Paderborn district are 232 plus the five digit number of the global id `de:05774:<n>`. */
    fun globalId(station: Station): String? {
        station.globalId?.takeIf { it.startsWith("de:") }?.let { return it }
        val id = station.id ?: return null
        if (id.length == 8 && id.startsWith("232")) return "de:05774:${id.drop(3).trimStart('0').ifEmpty { "0" }}"
        return null
    }

    fun params(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): Map<String, String>? {
        val local = at.withZoneSameInstant(berlin)
        val out = mutableMapOf(
            "outputFormat" to "rapidJSON",
            "coordOutputFormat" to "WGS84[dd.ddddd]",
            "useRealtime" to "1",
            "calcNumberOfTrips" to "6",
            "itdDate" to local.format(day),
            "itdTime" to local.format(clock),
            "itdTripDateTimeDepArr" to if (arrival) "arr" else "dep",
            "maxChanges" to maxChanges.toString(),
        )
        if (!placeInto(out, "origin", from) || !placeInto(out, "destination", to)) return null
        return out
    }

    private fun placeInto(out: MutableMap<String, String>, prefix: String, s: Station): Boolean {
        val gid = if (s.isStop) globalId(s) else null
        when {
            gid != null -> { out["type_$prefix"] = "any"; out["name_$prefix"] = gid }
            s.latitude != 0.0 || s.longitude != 0.0 -> {
                out["type_$prefix"] = "coord"
                out["name_$prefix"] = String.format(Locale.ENGLISH, "%.6f:%.6f:WGS84[dd.ddddd]", s.longitude, s.latitude)
            }
            else -> return false
        }
        return true
    }

    /** Null when the stops cannot be expressed in the EFA, so the caller falls back to the provider. */
    suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route>? {
        val p = params(from, to, at, arrival, maxChanges) ?: return null
        val body = SrsApi.getUrl("https://efa.vrr.de/standard/XML_TRIP_REQUEST2", p)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(body) }.also {
            if (it.isEmpty()) Pm.note("efa empty ${p["name_origin"]} -> ${p["name_destination"]}: ${body.take(240)}")
        }
    }

    /** Name lookup in about 0.4 s, the provider's `stations` call often answers 502 first. */
    suspend fun searchStations(name: String): List<Station> {
        val body = SrsApi.getUrl(
            "https://efa.vrr.de/standard/XML_STOPFINDER_REQUEST",
            mapOf("outputFormat" to "rapidJSON", "coordOutputFormat" to "WGS84[dd.ddddd]", "type_sf" to "any", "name_sf" to name),
        )
        // Vague names ("Geseke") rank an unrelated stop first; only a confident best match is trusted over the provider.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            if (confident(body)) parseLocations(body) else emptyList()
        }
    }

    fun confident(body: String): Boolean {
        val l = (json.parseToJsonElement(body) as? JsonObject)?.get("locations") as? JsonArray ?: return false
        return l.any { (it as? JsonObject)?.get("isBest")?.let { b -> (b as? JsonPrimitive)?.contentOrNull == "true" } == true }
    }

    fun parseLocations(body: String): List<Station> {
        val locations = (json.parseToJsonElement(body) as? JsonObject)?.get("locations") as? JsonArray ?: return emptyList()
        return locations.mapNotNull { it as? JsonObject }.mapNotNull { l ->
            val coord = (l["coord"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            val lat = coord?.getOrNull(0) ?: 0.0
            val lon = coord?.getOrNull(1) ?: 0.0
            val type = l.str("type")
            val locality = (l["parent"] as? JsonObject)?.str("name")
            when (type) {
                "stop" -> {
                    val stopId = ((l["properties"] as? JsonObject)?.str("stopId")) ?: return@mapNotNull null
                    Station(type = "stop", id = stopId, globalId = l.str("id"),
                        name = l.str("disassembledName")?.takeIf { it.isNotBlank() } ?: l.str("name"),
                        locality = locality, latitude = lat, longitude = lon)
                }
                "suburb", "locality", "city" -> null
                else -> if (lat == 0.0 && lon == 0.0) null else
                    Station(type = "address", name = l.str("name"), locality = locality, latitude = lat, longitude = lon)
            }
        }
    }

    fun parse(body: String): List<Route> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val journeys = root["journeys"] as? JsonArray ?: return emptyList()
        return journeys.mapNotNull { (it as? JsonObject)?.let(::route) }
    }

    private fun route(j: JsonObject): Route? {
        val legs = (j["legs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val parts = legs.map(::partial)
        // The EFA also returns interchange footpaths that last seconds.
        val first = parts.firstOrNull() ?: return null
        val last = parts.last()
        val dep = first.departureDate
        val arr = last.arrivalDate
        val ride = parts.filterNot { it.isWalk }
        val minutes = Duration.between(parseTime(first.expectedDepartureDate ?: dep) ?: return null, parseTime(last.expectedArrivalDate ?: arr) ?: return null).toMinutes()
        return Route(
            partialRoutes = parts,
            changes = (ride.size - 1).coerceAtLeast(0),
            ticketPriceAvailable = false,
            duration = "%02d:%02d".format(minutes / 60, minutes % 60),
            departureDate = dep,
            arrivalDate = arr,
            expectedDepartureDate = first.expectedDepartureDate,
            expectedArrivalDate = last.expectedArrivalDate,
        )
    }

    private fun partial(leg: JsonObject): PartialRoute {
        val transport = leg["transportation"] as? JsonObject
        val cls = ((transport?.get("product") as? JsonObject)?.get("class") as? JsonPrimitive)?.intOrNull ?: 0
        val origin = leg["origin"] as? JsonObject
        val destination = leg["destination"] as? JsonObject
        val depPlan = origin?.str("departureTimePlanned")
        val depEst = origin?.str("departureTimeEstimated")
        val arrPlan = destination?.str("arrivalTimePlanned")
        val arrEst = destination?.str("arrivalTimeEstimated")
        val walk = cls >= 99
        val seq = (leg["stopSequence"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val stations = seq.map { station(it, arrival = true) }.mapIndexed { i, s ->
            // the first stop shows its departure, the others their arrival, like the provider
            if (i == 0) station(seq[0], arrival = false) else s
        }
        val number = transport?.str("number") ?: transport?.str("disassembledName")
        val name = transport?.str("name")
        return PartialRoute(
            clazz = if (walk) "walkway" else "vehicleRide",
            type = if (walk) 100 else providerType(cls),
            typeName = if (walk) null else ((transport?.get("product") as? JsonObject)?.str("name")),
            lineName = if (walk) null else name,
            shortName = if (walk) null else number,
            lineDirection = ((transport?.get("destination") as? JsonObject)?.str("name")),
            transferTime = if (walk) (((leg["duration"] as? JsonPrimitive)?.intOrNull ?: 0) + 59) / 60 else null,
            stations = if (walk) emptyList() else stations,
            departureDate = iso(depPlan),
            arrivalDate = iso(arrPlan),
            expectedDepartureDate = iso(depEst)?.takeIf { it != iso(depPlan) },
            expectedArrivalDate = iso(arrEst)?.takeIf { it != iso(arrPlan) },
        )
    }

    // EFA product classes: 0 train, 1 S-Bahn, 2 U-Bahn, 3 Stadtbahn, 4 tram, 5 bus, 6 regional bus, 7 Schnellbus, 10 on demand
    private fun providerType(cls: Int): Int = when (cls) {
        0, 1 -> 1
        2, 3 -> 2
        4 -> 4
        else -> 3
    }

    private fun station(e: JsonObject, arrival: Boolean): Station {
        val parent = e["parent"] as? JsonObject
        val props = (parent?.get("properties") as? JsonObject) ?: (e["properties"] as? JsonObject)
        val stopId = props?.str("stopId")
        val coord = (e["coord"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        val plan = e.str(if (arrival) "arrivalTimePlanned" else "departureTimePlanned") ?: e.str("departureTimePlanned") ?: e.str("arrivalTimePlanned")
        val est = e.str(if (arrival) "arrivalTimeEstimated" else "departureTimeEstimated") ?: e.str("departureTimeEstimated") ?: e.str("arrivalTimeEstimated")
        return Station(
            type = "stop",
            id = stopId,
            globalId = parent?.str("id"),
            name = parent?.str("disassembledName")?.takeIf { it.isNotBlank() } ?: e.str("name"),
            platform = (e["properties"] as? JsonObject)?.str("platformName"),
            locality = ((parent?.get("parent") as? JsonObject)?.str("name")),
            latitude = coord?.getOrNull(0) ?: 0.0,
            longitude = coord?.getOrNull(1) ?: 0.0,
            date = iso(plan),
            expectedDate = iso(est)?.takeIf { it != iso(plan) },
        )
    }

    private val out = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ENGLISH)

    private fun iso(utc: String?): String? = utc?.let {
        runCatching { Instant.parse(it).atZone(berlin).format(out) }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
