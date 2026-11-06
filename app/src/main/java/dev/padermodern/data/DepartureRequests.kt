package dev.padermodern.data

import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope

/** Coalesces concurrent departure loads while keeping a separate short-lived warm snapshot. */
class DepartureRequests(
    scope: CoroutineScope,
    private val load: suspend (String, ZonedDateTime) -> List<Departure>,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Key(val stationId: String, val at: ZonedDateTime)
    private data class Snapshot(val departures: List<Departure>, val completedAt: Long)

    private val lock = Any()
    private val inFlight = RequestCache<Key, List<Departure>>(scope, ttlMillis = 0, maxEntries = 24, nowMillis = nowMillis)
    private val warm = LinkedHashMap<Key, Snapshot>(16, 0.75f, true)
    private val active = mutableMapOf<Key, Int>()

    suspend fun get(stationId: String, at: ZonedDateTime, fresh: Boolean = true): List<Departure> {
        val minute = at.withSecond(0).withNano(0)
        val key = Key(stationId, minute)
        synchronized(lock) {
            if (!fresh && key !in active) {
                val snapshot = warm[key]
                if (snapshot != null) {
                    if (nowMillis() - snapshot.completedAt < SearchPolicy.DEPARTURE_TTL_MILLIS) {
                        return snapshot.departures
                    }
                    warm.remove(key)
                }
            }
            active[key] = (active[key] ?: 0) + 1
        }

        try {
            return inFlight.get(key) {
                val result = load(stationId, minute)
                synchronized(lock) {
                    warm[key] = Snapshot(result, nowMillis())
                    trimWarm()
                }
                result
            }
        } finally {
            synchronized(lock) {
                val remaining = (active[key] ?: 1) - 1
                if (remaining == 0) active.remove(key) else active[key] = remaining
            }
        }
    }

    private fun trimWarm() {
        while (warm.size > MAX_WARM_ENTRIES) {
            val eldest = warm.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    private companion object {
        const val MAX_WARM_ENTRIES = 24
    }
}
