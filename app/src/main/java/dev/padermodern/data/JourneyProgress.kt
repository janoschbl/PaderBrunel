package dev.padermodern.data

import kotlin.math.*

data class JourneyFix(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val ageMillis: Long,
    val elapsedMillis: Long,
    val speed: Float? = null,
)

data class JourneyAnnouncement(val id: String, val text: String)

class JourneyProgress(private var stops: List<Station>) {
    var nextStopIndex: Int? = null; private set
    var arrived = false; private set
    var hasGoodFix = false; private set
    private var reached = -1
    private var candidate = -1
    private var candidateSince = 0L
    private var announcedNext = -1

    fun refreshStops(updated: List<Station>) {
        if (updated.size == stops.size && updated.map { it.id } == stops.map { it.id }) stops = updated
    }

    fun update(fix: JourneyFix, nowMillis: Long): JourneyAnnouncement? {
        hasGoodFix = fix.ageMillis in 0..30_000 && fix.accuracy.isFinite() && fix.accuracy in 0f..60f &&
            fix.latitude.isFinite() && fix.longitude.isFinite() &&
            fix.latitude in -90.0..90.0 && fix.longitude in -180.0..180.0
        if (!hasGoodFix || arrived) {
            candidate = -1
            return null
        }
        val distances = stops.map { s ->
            if (s.latitude == 0.0 || s.longitude == 0.0 || !s.latitude.isFinite() || !s.longitude.isFinite())
                Double.POSITIVE_INFINITY else distance(fix.latitude, fix.longitude, s.latitude, s.longitude)
        }
        val near = stops.indices.filter { i ->
            i >= reached && !stops[i].canceled && distances[i] <= 70 &&
                (reached >= 0 || parseTime(stops[i].expectedDate ?: stops[i].date)?.let {
                    abs(it.toInstant().toEpochMilli() - nowMillis) <= 30 * 60_000
                } != false)
        }.minByOrNull { distances[it] }
        if (near != null && near != reached) {
            if (candidate != near) {
                candidate = near
                candidateSince = fix.elapsedMillis
            } else if (fix.elapsedMillis - candidateSince >= 3_000) {
                if (near == stops.lastIndex && (fix.speed == null || fix.speed > 3f)) return null
                reached = near
                nextStopIndex = (near + 1).takeIf { it <= stops.lastIndex }
                candidate = -1
                if (near == stops.lastIndex) {
                    arrived = true
                    return JourneyAnnouncement("arrived:$near", "Du bist an deiner Ausstiegshaltestelle ${spoken(stops[near])}. Bitte prüfe vor dem Aussteigen die Haltestelle.")
                }
            }
        } else if (near == null || near == reached) candidate = -1

        val next = nextStopIndex ?: return null
        if (announcedNext == next || reached < 0 || distances[reached] < 140 || stops[next].canceled) return null
        return announceNext(next)
    }

    fun confirmBusNextStop(next: Int): JourneyAnnouncement? {
        val current = nextStopIndex ?: return null
        if (arrived || next !in 1..stops.lastIndex || next < current || stops[next].canceled) return null
        reached = maxOf(reached, next - 1)
        nextStopIndex = next
        candidate = -1
        return if (announcedNext == next) null else announceNext(next)
    }

    private fun announceNext(next: Int): JourneyAnnouncement {
        announcedNext = next
        val text = "Nächster Halt: ${spoken(stops[next])}." +
            if (next == stops.lastIndex) " Hier steigst du aus. Bitte rechtzeitig den Halteknopf drücken." else ""
        return JourneyAnnouncement("next:$next", text)
    }

    companion object {
        fun spoken(stop: Station): String {
            val name = stop.name.orEmpty().replace(Regex("str\\.", RegexOption.IGNORE_CASE), "straße")
            val platform = stop.platform?.takeIf { it.isNotBlank() } ?: return name
            return "$name, " + if (platform.contains(Regex("Steig|Gleis|Bahnsteig", RegexOption.IGNORE_CASE))) platform else "Steig $platform"
        }

        private fun distance(a: Double, b: Double, c: Double, d: Double): Double {
            val lat = Math.toRadians(c - a)
            val lon = Math.toRadians(d - b)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(lon / 2).pow(2)
            return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
}
