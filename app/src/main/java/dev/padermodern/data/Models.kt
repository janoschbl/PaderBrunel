package dev.padermodern.data

import kotlinx.serialization.Serializable

@Serializable
data class Station(
    val type: String? = null,
    val id: String? = null,
    val globalId: String? = null,
    val name: String? = null,
    val platform: String? = null,
    val locality: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val date: String? = null,
    val expectedDate: String? = null,
    val canceled: Boolean = false,
    val distance: Int = 0,
) {
    val key: String get() = id ?: "$latitude:$longitude"

    val isStop: Boolean get() = type == "stop"

    // api has no rail flag, the name decides
    val isRailStation: Boolean
        get() = isStop && name.orEmpty().let { n ->
            !n.contains("Busbahnhof", true) && !n.startsWith("Abzw", true) &&
                Regex("bahnhof|\\bhbf\\b|\\bbf\\b", RegexOption.IGNORE_CASE).containsMatchIn(n)
        }

    // addresses come back as pseudo stops (id 9999999x)
    val isRealStop: Boolean get() = isStop && id != null && !id.startsWith("9999999")

    val displayName: String
        get() {
            val n = name.orEmpty()
            val l = locality
            return if (l.isNullOrBlank() || n.startsWith(l) || n.contains(",")) n else "$l, $n"
        }
}

@Serializable
data class InfoMessage(
    val title: String? = null,
    val text: String? = null,
    val priority: String? = null,
)

@Serializable
data class Fare(
    val name: String? = null,
    val net: String? = null,
    val fareAdult: Double? = null,
    val priceLevel: String? = null,
)

@Serializable
data class PartialRoute(
    val clazz: String? = null,
    val stations: List<Station> = emptyList(),
    val type: Int = 0,
    val typeName: String? = null,
    val lineName: String? = null,
    val shortName: String? = null,
    val lineDirection: String? = null,
    val vehicleRideType: String? = null,
    val encodedPath: String? = null,
    val infosNew: List<InfoMessage> = emptyList(),
    val warningsNew: List<InfoMessage> = emptyList(),
    val transferTime: Int? = null,
    val departureDate: String? = null,
    val arrivalDate: String? = null,
    val expectedDepartureDate: String? = null,
    val expectedArrivalDate: String? = null,
) {
    val isWalk: Boolean get() = clazz == "walkway" || type == 100

    val walkMinutes: Int get() = if (isWalk) transferTime ?: 0 else 0
}

@Serializable
data class Route(
    val partialRoutes: List<PartialRoute> = emptyList(),
    val changes: Int = 0,
    val fares: List<Fare> = emptyList(),
    val ticketPriceAvailable: Boolean = false,
    val price: Double? = null,
    val impossible: Boolean = false,
    val duration: String? = null,
    val departureDate: String? = null,
    val arrivalDate: String? = null,
    val expectedDepartureDate: String? = null,
    val expectedArrivalDate: String? = null,
) {
    val rides: List<PartialRoute> get() = partialRoutes.filterNot { it.isWalk }

    val realDeparture: String? get() = expectedDepartureDate ?: departureDate

    val realArrival: String?
        get() = expectedArrivalDate
            ?: partialRoutes.lastOrNull()?.let { last ->
                if (last.isWalk) null else last.expectedArrivalDate
            }
            ?: arrivalDate

    val key: String
        get() = "$departureDate|$arrivalDate|" +
            partialRoutes.joinToString(",") { "${it.shortName}@${it.stations.firstOrNull()?.id}>${it.stations.lastOrNull()?.id}" }
}

@Serializable
data class RoutesResponse(
    val routes: List<Route> = emptyList(),
    val requestID: String? = null,
    val sessionID: String? = null,
)

@Serializable
data class LineStation(
    val id: String? = null,
    val name: String? = null,
    val index: Int = 0,
    val date: String? = null,
)

@Serializable
data class Departure(
    val stopName: String? = null,
    val stopId: String? = null,
    val canceled: Boolean = false,
    val departureDate: String? = null,
    val expectedDepartureDate: String? = null,
    val lineName: String? = null,
    val lineId: String? = null,
    val shortName: String? = null,
    val type: Int = 0,
    val destination: String? = null,
    val destinationID: String? = null,
    val platform: String? = null,
    val platformName: String? = null,
    val route: Route? = null,
    val lineStations: List<LineStation> = emptyList(),
    val tripCode: String? = null,
    val gid: String? = null,
) {
    val steig: String? get() = gid?.substringAfterLast(":", "")?.takeIf { it.isNotEmpty() }
    val key: String get() = "$stopId|$lineId|$tripCode|$departureDate"
    val realDeparture: String? get() = expectedDepartureDate ?: departureDate
}

@Serializable
data class DepartureResponse(
    val departures: List<Departure> = emptyList(),
)

@Serializable
data class TrafficReport(
    val lineName: String? = null,
    val type: Int = 0,
    val warningsCount: Int = 0,
    val allWarnings: String? = null,
)

@Serializable
data class LinePlan(
    val id: String,
    val title: String? = null,
    val date: String? = null,
)

@Serializable
data class Line(
    val id: String? = null,
    val name: String? = null,
    val sortOrder: Int = 0,
    val transportationType: Int = 0,
    val type: String? = null,
)

@Serializable
data class SavedTrip(
    val id: String,
    val route: Route,
    val fromName: String,
    val toName: String,
    val savedAt: Long = System.currentTimeMillis(),
)

fun Route.slim(): Route = copy(
    fares = fares.take(1),
    partialRoutes = partialRoutes.map { it.copy(infosNew = emptyList(), encodedPath = it.encodedPath) },
)

data class NearbyDeparture(val departure: Departure, val walkMinutes: Int?)
