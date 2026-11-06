package dev.padermodern.data

import java.time.ZonedDateTime

/**
 * Answers [TripSearch] from on-device timetables. It never touches the network and reports
 * "unknown" (empty) for anything it cannot answer faithfully: arrival searches, stops it has not
 * seen, and days it has no timetable for. The provider search stays the source of truth.
 */
class LocalTripSource(
    private val tableFor: (java.time.LocalDate) -> LocalTimetable?,
    private val minTransferMin: Int = 3,
    /** Any table will do for turning a coordinate into nearby stops: the stop catalog does not change by day. */
    private val catalog: () -> LocalTimetable? = { tableFor(java.time.LocalDate.now(java.time.ZoneId.of("Europe/Berlin"))) },
) : TripSearchSource {
    constructor(timetables: Map<java.time.LocalDate, LocalTimetable>, minTransferMin: Int = 3) :
        this({ timetables[it] }, minTransferMin, { timetables.values.firstOrNull() })

    private val planners = HashMap<java.time.LocalDate, LocalPlanner>()

    private fun table(at: ZonedDateTime): LocalTimetable? =
        tableFor(at.withZoneSameInstant(java.time.ZoneId.of("Europe/Berlin")).toLocalDate())

    fun covers(from: Station, to: Station, at: ZonedDateTime): Boolean {
        val tt = table(at) ?: return false
        return resolve(tt, from) && resolve(tt, to)
    }

    private fun resolve(tt: LocalTimetable, place: Station) =
        if (place.isRealStop) tt.resolve(place.id, place.name, place.latitude, place.longitude) >= 0 else tt.near(place.latitude, place.longitude, 1200).isNotEmpty()

    override suspend fun endpoints(place: Station, limit: Int): List<Endpoint> {
        if (place.isRealStop) return listOf(Endpoint(place, 0))
        val tt = catalog() ?: return listOf(Endpoint(place, 0))
        val near = tt.near(place.latitude, place.longitude, 1200)
        if (near.isEmpty()) return listOf(Endpoint(place, 0))
        val seen = HashSet<String>()
        return near.filter { seen.add(tt.stopNames[it.first]) }.take(limit).map { (i, meters) ->
            Endpoint(stationAt(tt, i, meters), TripSearch.walkMinutes(meters))
        }
    }

    override suspend fun departures(stationId: String, at: ZonedDateTime, fresh: Boolean): List<Departure> = emptyList()

    override suspend fun routesBetween(from: Station, to: Station, at: ZonedDateTime, arrival: Boolean, maxChanges: Int): List<Route> {
        if (arrival) return emptyList()
        val tt = table(at) ?: return emptyList()
        val a = tt.resolve(from.id, from.name, from.latitude, from.longitude); val b = tt.resolve(to.id, to.name, to.latitude, to.longitude)
        if (a < 0 || b < 0) return emptyList()
        val planner = planners.getOrPut(tt.day) { LocalPlanner(tt, minTransferMin) }
        // the caller's stop ids win at both ends, so live data can be matched to the provider's boards
        val alias = mapOf(a to from.id, b to to.id)
        return planner.journeys(a, b, tt.minuteOf(at), maxChanges).map { toRoute(tt, it, alias) }
    }

    private fun stationAt(tt: LocalTimetable, i: Int, meters: Int = 0, minute: Int? = null, alias: Map<Int, String?> = emptyMap()) = Station(
        type = "stop", id = alias[i] ?: tt.stopIds[i], name = tt.stopNames[i], latitude = tt.lat[i], longitude = tt.lon[i],
        date = minute?.let { tt.timeOf(it).iso() }, distance = meters,
    )

    private fun typeName(type: Int) = when (type) { 2 -> "S-Bahn"; 3 -> "Bus"; 6 -> "Zug"; else -> "Bus" }

    private fun toRoute(tt: LocalTimetable, j: LocalJourney, alias: Map<Int, String?>): Route {
        // walks between rides are anchored forwards from the previous ride; a leading walk backwards from the first ride
        val legs = ArrayList<PartialRoute>()
        var clock: Int? = null
        j.legs.forEachIndexed { index, leg ->
            when (leg) {
                is LocalLeg.Ride -> {
                    val t = tt.trips[leg.trip]
                    val dep = t.minutes[leg.board]; val arr = t.minutes[leg.alight]
                    legs += PartialRoute(
                        clazz = "vehicleRide", type = t.type, typeName = typeName(t.type), lineName = t.lineName.ifBlank { null },
                        shortName = t.shortName.ifBlank { null }, lineDirection = t.destination,
                        vehicleRideType = if (t.type == 6 || t.type == 2) "TRAIN" else "BUS",
                        stations = (leg.board..leg.alight).map { stationAt(tt, t.stops[it], 0, t.minutes[it], alias) },
                        departureDate = tt.timeOf(dep).iso(), arrivalDate = tt.timeOf(arr).iso(),
                    )
                    clock = arr
                }
                is LocalLeg.Walk -> {
                    val nextRide = j.legs.drop(index + 1).firstOrNull { it is LocalLeg.Ride } as? LocalLeg.Ride
                    val start = clock ?: (nextRide?.let { tt.trips[it.trip].minutes[it.board] - leg.minutes } ?: j.departure)
                    legs += PartialRoute(
                        clazz = "walkway", type = 100, transferTime = leg.minutes,
                        stations = listOf(stationAt(tt, leg.from, 0, start, alias), stationAt(tt, leg.to, 0, start + leg.minutes, alias)),
                        departureDate = tt.timeOf(start).iso(), arrivalDate = tt.timeOf(start + leg.minutes).iso(),
                    )
                    clock = start + leg.minutes
                }
            }
        }
        return Route(
            partialRoutes = legs, changes = j.rides - 1,
            departureDate = legs.first().departureDate, arrivalDate = legs.last().arrivalDate,
            duration = "%02d:%02d".format((j.arrival - j.departure) / 60, (j.arrival - j.departure) % 60),
        )
    }
}
