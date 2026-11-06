package dev.padermodern.data

import java.time.ZonedDateTime

/** One leg of a local journey: a ride on [trip] between call positions, or a walk between stops. */
sealed class LocalLeg {
    data class Ride(val trip: Int, val board: Int, val alight: Int) : LocalLeg()
    data class Walk(val from: Int, val to: Int, val minutes: Int, val startMinute: Int) : LocalLeg()
}

class LocalJourney(val legs: List<LocalLeg>, val departure: Int, val arrival: Int, val rides: Int)

/** Round-based earliest-arrival search over [LocalTimetable] trips (RAPTOR without route patterns). */
class LocalPlanner(private val tt: LocalTimetable, private val minTransferMin: Int = 3) {
    private companion object { const val INF = Int.MAX_VALUE; const val HORIZON_MIN = 240; const val ROUNDS_PER_ITERATION = 4 }

    /** Up to [count] journeys leaving [from] at or after [startMinute], with at most [maxChanges] changes. */
    fun journeys(from: Int, to: Int, startMinute: Int, maxChanges: Int, count: Int = 8): List<LocalJourney> {
        if (from == to) return emptyList()
        val found = LinkedHashMap<String, LocalJourney>()
        var t = startMinute
        repeat(count + 4) {
            if (t > startMinute + HORIZON_MIN || found.size >= count * 3) return@repeat
            val round = search(from, to, t, maxChanges.coerceAtLeast(0) + 1)
            if (round.isEmpty()) return@repeat
            round.forEach { found.putIfAbsent(it.legs.joinToString { l -> l.toString() }, it) }
            t = round.minOf { it.departure } + 1
        }
        return found.values.sortedWith(compareBy({ it.departure }, { it.arrival }))
    }

    private fun search(origin: Int, dest: Int, start: Int, maxRides: Int): List<LocalJourney> {
        val n = tt.stopCount
        val best = IntArray(n) { INF }
        // labels per round: -1 none, otherwise trip index (ride) or -2 (walk)
        val lblKind = Array(maxRides + 1) { IntArray(n) { -1 } }
        val lblFrom = Array(maxRides + 1) { IntArray(n) { -1 } }
        val lblBoard = Array(maxRides + 1) { IntArray(n) }
        val lblAlight = Array(maxRides + 1) { IntArray(n) }
        val lblWalkMin = Array(maxRides + 1) { IntArray(n) }
        best[origin] = start
        var marked = arrayListOf(origin)
        fun walkFrom(round: Int, sources: List<Int>, out: MutableList<Int>) {
            for (q in sources) {
                val fp = tt.footpaths[q]
                var i = 0
                while (i < fp.size) {
                    val r = fp[i]; val w = fp[i + 1]; i += 2
                    val a = best[q] + w
                    if (a < best[r]) { best[r] = a; lblKind[round][r] = -2; lblFrom[round][r] = q; lblWalkMin[round][r] = w; out += r }
                }
            }
        }
        run { val extra = ArrayList<Int>(); walkFrom(0, marked, extra); marked.addAll(extra) }
        val journeys = ArrayList<LocalJourney>()
        var bestDest = INF
        for (k in 1..maxRides) {
            val prev = best.copyOf()
            val boarded = HashMap<Int, Int>()
            val improved = ArrayList<Int>()
            val seen = BooleanArray(n)
            for (p in marked) {
                val byRide = k > 1 && lblKind[k - 1][p] >= 0
                val minDep = prev[p] + if (byRide) minTransferMin else 0
                val deps = tt.depTrip[p]; val poss = tt.depPos[p]
                var lo = 0; var hi = deps.size
                while (lo < hi) { val mid = (lo + hi) ushr 1; if (tt.trips[deps[mid]].minutes[poss[mid]] < minDep) lo = mid + 1 else hi = mid }
                var i = lo
                while (i < deps.size) {
                    val trip = tt.trips[deps[i]]; val pos = poss[i]
                    if (trip.minutes[pos] > minDep + HORIZON_MIN) break
                    val ti = deps[i]; i++
                    val already = boarded[ti]
                    if (already != null && already <= pos) continue
                    boarded[ti] = pos
                    for (j in pos + 1 until trip.stops.size) {
                        val s = trip.stops[j]; val a = trip.minutes[j]
                        if (a >= best[s] || a >= bestDest) continue
                        best[s] = a
                        lblKind[k][s] = ti; lblFrom[k][s] = p; lblBoard[k][s] = pos; lblAlight[k][s] = j
                        if (!seen[s]) { seen[s] = true; improved += s }
                    }
                }
            }
            val walked = ArrayList<Int>()
            walkFrom(k, improved, walked)
            walked.forEach { if (!seen[it]) { seen[it] = true; improved += it } }
            if (best[dest] < bestDest && lblKind[k][dest] != -1) {
                bestDest = best[dest]
                journeys += rebuild(origin, dest, k, start, lblKind, lblFrom, lblBoard, lblAlight, lblWalkMin)
            }
            if (improved.isEmpty()) break
            marked = improved
        }
        return journeys
    }

    private fun rebuild(
        origin: Int, dest: Int, rounds: Int, start: Int,
        kind: Array<IntArray>, from: Array<IntArray>, board: Array<IntArray>, alight: Array<IntArray>, walk: Array<IntArray>,
    ): LocalJourney {
        val legs = ArrayList<LocalLeg>()
        var k = rounds; var s = dest
        while (!(k == 0 && s == origin)) {
            when {
                kind[k][s] >= 0 -> {
                    legs += LocalLeg.Ride(kind[k][s], board[k][s], alight[k][s]); s = from[k][s]; k--
                }
                kind[k][s] == -2 -> {
                    legs += LocalLeg.Walk(from[k][s], s, walk[k][s], 0); s = from[k][s]
                }
                else -> error("broken label chain")
            }
        }
        legs.reverse()
        val first = legs.first()
        val departure = when (first) {
            is LocalLeg.Ride -> tt.trips[first.trip].minutes[first.board]
            is LocalLeg.Walk -> (legs.firstOrNull { it is LocalLeg.Ride } as? LocalLeg.Ride)?.let { tt.trips[it.trip].minutes[it.board] - first.minutes } ?: start
        }
        val last = legs.last()
        val arrival = when (last) {
            is LocalLeg.Ride -> tt.trips[last.trip].minutes[last.alight]
            is LocalLeg.Walk -> (legs.lastOrNull { it is LocalLeg.Ride } as? LocalLeg.Ride)?.let { tt.trips[it.trip].minutes[it.alight] + last.minutes } ?: start
        }
        return LocalJourney(legs, departure, arrival, legs.count { it is LocalLeg.Ride })
    }
}
