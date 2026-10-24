package dev.padermodern.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.ZonedDateTime

enum class Phase { Upcoming, Riding, Transfer, Walking, Arrived }

data class TripStatus(
    val phase: Phase,
    val legIndex: Int,
    val headline: String,
    val detail: String?,
    val broken: Boolean,
    val brokenText: String?,
    val brokenLeg: Int? = null,
)

private fun PartialRoute.effDeparture() = parseTime(expectedDepartureDate ?: stations.firstOrNull()?.expectedDate ?: departureDate)
private fun PartialRoute.effArrival() = parseTime(expectedArrivalDate ?: stations.lastOrNull()?.expectedDate ?: arrivalDate)
fun Station.effective() = parseTime(expectedDate ?: date)

object LiveTrip {
    suspend fun refresh(route: Route): Route = coroutineScope {
        route.copy(
            partialRoutes = route.partialRoutes.map { leg ->
                async { if (leg.isWalk) leg else refreshLeg(leg) }
            }.awaitAll(),
        )
    }

    private suspend fun refreshLeg(leg: PartialRoute): PartialRoute = runCatching {
        val first = leg.stations.first()
        val plannedDep = parseTime(first.date ?: leg.departureDate)!!
        val deps = SrsApi.departures(first.id!!, plannedDep.minusMinutes(2))
        val match = deps.firstOrNull { it.shortName == leg.shortName && parseTime(it.departureDate) == plannedDep }
            ?: return@runCatching leg
        val live = match.route?.partialRoutes?.firstOrNull { !it.isWalk }?.stations.orEmpty().associateBy { it.id }
        val stations = leg.stations.mapIndexed { i, s ->
            val rt = live[s.id]
            if (rt == null) s else s.copy(
                expectedDate = rt.expectedDate,
                platform = rt.platform ?: s.platform,
                canceled = rt.canceled || (i == 0 && match.canceled),
            )
        }
        leg.copy(
            stations = stations,
            expectedDepartureDate = stations.first().expectedDate ?: match.expectedDepartureDate ?: leg.expectedDepartureDate,
            expectedArrivalDate = stations.last().expectedDate ?: leg.expectedArrivalDate,
        )
    }.getOrDefault(leg)

    private fun mins(a: ZonedDateTime, b: ZonedDateTime) = Duration.between(a, b).toMinutes()

    private fun brokenTransfer(r: Route): Pair<Int, String>? {
        val parts = r.partialRoutes
        parts.forEachIndexed { i, leg ->
            if (!leg.isWalk && leg.stations.any { it.canceled }) return i to "${leg.shortName} fällt aus"
        }
        val rides = parts.indices.filter { !parts[it].isWalk }
        for (k in 0 until rides.size - 1) {
            val a = rides[k]
            val b = rides[k + 1]
            val arr = parts[a].effArrival() ?: continue
            val dep = parts[b].effDeparture() ?: continue
            val walk = (a + 1 until b).sumOf { parts[it].walkMinutes }
            if (mins(arr, dep) - walk < 0) return b to "Anschluss ${parts[b].shortName} nicht mehr erreichbar"
        }
        return null
    }

    fun status(r: Route, now: ZonedDateTime = ZonedDateTime.now()): TripStatus {
        val parts = r.partialRoutes
        val broken = brokenTransfer(r)
        val rides = parts.indices.filter { !parts[it].isWalk }
        fun make(phase: Phase, idx: Int, headline: String, detail: String?) =
            TripStatus(phase, idx, headline, detail, broken != null, broken?.second, broken?.first)
        if (rides.isEmpty()) return make(Phase.Arrived, 0, "Fußweg", null)

        val firstRide = rides.first()
        val firstDep = parts[firstRide].effDeparture()
        val tripStart = parseTime(r.expectedDepartureDate ?: r.departureDate)
        if (firstDep != null && now.isBefore(firstDep)) {
            val leg = parts[firstRide]
            val m = mins(now, firstDep)
            val platform = leg.stations.first().platform?.let { " · $it" }.orEmpty()
            val head = if (firstRide > 0 && tripStart != null && now.isBefore(tripStart.plusMinutes(parts[0].walkMinutes.toLong()))) {
                "Los zur Haltestelle ${leg.stations.first().name}"
            } else "Abfahrt in ${m.coerceAtLeast(0)} min"
            val late = delayMinutes(leg.departureDate, leg.expectedDepartureDate)?.takeIf { it > 0 }?.let { " (+$it)" }.orEmpty()
            return make(Phase.Upcoming, firstRide, head, "${leg.shortName} ${leg.lineDirection?.let { "nach $it" }.orEmpty()}$platform · ${firstDep.hhmm()}$late".trim())
        }
        for ((k, idx) in rides.withIndex()) {
            val leg = parts[idx]
            val dep = leg.effDeparture() ?: continue
            val arr = leg.effArrival() ?: continue
            if (!now.isBefore(dep) && now.isBefore(arr)) {
                val next = leg.stations.firstOrNull { (it.effective() ?: dep).isAfter(now) } ?: leg.stations.last()
                val isFinal = next == leg.stations.last()
                val platform = next.platform?.let { " · $it" }.orEmpty()
                val head = "Unterwegs mit ${leg.shortName}"
                val detail = if (isFinal) {
                    val follow = rides.getOrNull(k + 1)?.let { parts[it] }
                    "Ausstieg ${next.name} um ${next.effective().hhmm()}$platform" +
                        (follow?.let { " · weiter mit ${it.shortName}" } ?: "")
                } else "Nächster Halt ${next.name} · ${next.effective().hhmm()}"
                return make(Phase.Riding, idx, head, detail)
            }
            val nextRide = rides.getOrNull(k + 1)?.let { parts[it] }
            if (nextRide != null && !now.isBefore(arr)) {
                val nextDep = nextRide.effDeparture() ?: continue
                if (now.isBefore(nextDep)) {
                    val platform = nextRide.stations.first().platform?.let { " · $it" }.orEmpty()
                    return make(
                        Phase.Transfer, rides[k + 1], "Umstieg in ${leg.stations.last().name}",
                        "${nextRide.shortName} ${nextRide.lineDirection?.let { "nach $it" }.orEmpty()}$platform · in ${mins(now, nextDep).coerceAtLeast(0)} min (${nextDep.hhmm()})".trim(),
                    )
                }
            }
        }
        val lastArr = parts[rides.last()].effArrival()
        val end = parseTime(r.expectedArrivalDate ?: r.arrivalDate)
        if (lastArr != null && end != null && now.isBefore(end) && rides.last() < parts.lastIndex) {
            return make(Phase.Walking, parts.lastIndex, "Fußweg zum Ziel", "Ankunft ${end.hhmm()}")
        }
        return make(Phase.Arrived, parts.lastIndex, "Angekommen", null)
    }

    data class TailSearch(
        val keepUntil: Int,
        val from: Station,
        val to: Station,
        val notBefore: ZonedDateTime,
        val transfer: Boolean = true,
    )

    fun replaceableFrom(r: Route, s: TripStatus): Int? {
        val parts = r.partialRoutes
        val idx = s.brokenLeg ?: when (s.phase) {
            Phase.Riding -> (s.legIndex + 1 until parts.size).firstOrNull { !parts[it].isWalk }
            Phase.Transfer -> s.legIndex
            else -> null
        } ?: return null
        return idx.takeIf { tailSearch(r, it) != null }
    }

    fun swappableLegs(r: Route, now: ZonedDateTime = ZonedDateTime.now()): List<Int> =
        r.partialRoutes.indices.filter { i ->
            val leg = r.partialRoutes[i]
            !leg.isWalk && leg.effDeparture()?.isAfter(now) == true && tailSearch(r, i, now) != null
        }

    fun tailSearch(r: Route, fromLeg: Int, now: ZonedDateTime = ZonedDateTime.now()): TailSearch? {
        val parts = r.partialRoutes
        val to = parts.lastOrNull()?.stations?.lastOrNull() ?: return null
        val prev = (fromLeg - 1 downTo 0).firstOrNull { !parts[it].isWalk }
        if (prev == null) {
            // first ride has no previous arrival, search from now
            val from = parts.getOrNull(fromLeg)?.stations?.firstOrNull() ?: return null
            return TailSearch(fromLeg - 1, from, to, now, transfer = false)
        }
        val from = parts[prev].stations.lastOrNull() ?: return null
        val arr = parts[prev].effArrival() ?: return null
        return TailSearch(prev, from, to, arr)
    }

    fun fitsTail(alt: Route, t: TailSearch, minTransferMin: Int): Boolean {
        val dep = parseTime(alt.expectedDepartureDate ?: alt.departureDate) ?: return false
        val earliest = if (t.transfer) t.notBefore.plusMinutes(minTransferMin.toLong()) else t.notBefore
        return !alt.impossible && alt.rides.isNotEmpty() && !dep.isBefore(earliest)
    }

    fun replaceTail(r: Route, t: TailSearch, alt: Route): Route {
        val parts = r.partialRoutes.take(t.keepUntil + 1) + alt.partialRoutes
        val fromStart = t.keepUntil < 0
        return r.copy(
            partialRoutes = parts,
            changes = (parts.count { !it.isWalk } - 1).coerceAtLeast(0),
            departureDate = if (fromStart) alt.departureDate else r.departureDate,
            expectedDepartureDate = if (fromStart) alt.expectedDepartureDate else r.expectedDepartureDate,
            arrivalDate = alt.arrivalDate,
            expectedArrivalDate = alt.expectedArrivalDate,
            duration = null,
            impossible = false,
        )
    }

    fun isActive(r: Route, leadMinutes: Long, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        val dep = parseTime(r.expectedDepartureDate ?: r.departureDate) ?: return false
        val arr = parseTime(r.expectedArrivalDate ?: r.arrivalDate) ?: return false
        return now.isAfter(dep.minusMinutes(leadMinutes)) && now.isBefore(arr.plusMinutes(5))
    }

    private fun ZonedDateTime?.hhmm(): String = this?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) ?: "–"
}
