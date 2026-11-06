package dev.padermodern.data

import java.time.ZonedDateTime
import kotlinx.coroutines.NonCancellable
import java.util.LinkedHashMap

data class RouteQuery(
    val from: Station,
    val to: Station,
    val at: ZonedDateTime,
    val arrival: Boolean,
    val maxChanges: Int,
    val includeDirect: Boolean = true,
)

/** Caches planned schedules only. Live predictions belong to RouteRealtime. */
class RoutePlans(
    private val load: suspend (RouteQuery, (List<Route>) -> Unit) -> List<Route> = { q, batch ->
        TripSearch.fetch(q.from, q.to, q.at, q.arrival, q.maxChanges, onBatch = batch, includeDirect = q.includeDirect)
    },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val store: PlanStore? = null,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val ioContext: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) {
    private data class Key(
        val from: Station,
        val to: Station,
        val minuteInstant: Long,
        val zone: String,
        val arrival: Boolean,
        val maxChanges: Int,
        val includeDirect: Boolean,
    )
    private data class Entry(val routes: List<Route>, val storedAt: Long)

    private val entries = LinkedHashMap<Key, Entry>(16, .75f, true)

    fun cached(query: RouteQuery): List<Route>? = synchronized(entries) {
        val key = query.key()
        val entry = entries[key] ?: return@synchronized null
        if (nowMillis() - entry.storedAt >= SearchPolicy.SCHEDULE_TTL_MILLIS) {
            entries.remove(key)
            null
        } else entry.routes
    }

    suspend fun fetch(query: RouteQuery, onBatch: (List<Route>) -> Unit = {}): List<Route> {
        val key = query.key()
        val snapshot = lookup(key)
        if (snapshot != null && nowMillis() - snapshot.storedAt < SearchPolicy.SCHEDULE_REFRESH_MILLIS) {
            onBatch(snapshot.routes)
            return snapshot.routes
        }
        if (snapshot != null) onBatch(snapshot.routes)

        // After a restart the phone still knows yesterday's answer for this route: show it now, refresh below.
        val persisted = if (snapshot == null) loadPersisted(query) else null
        if (persisted != null) {
            Pm.note("disk cache hit ${persisted.routes.size} routes")
            onBatch(persisted.routes)
            val age = wallMillis() - persisted.storedAt
            if (age < SearchPolicy.SCHEDULE_REFRESH_MILLIS && persisted.queryAt <= query.at.toInstant().toEpochMilli()) {
                synchronized(entries) { entries[key] = Entry(persisted.routes, nowMillis() - age) }
                return persisted.routes
            }
        }

        // Publish only after successful completion: intermediate batches are useful to the UI,
        // but must never turn a failed/partial provider search into a cache hit.
        val routes = load(query) { batch -> onBatch(batch) }
        val sanitized = routes.filterNot { it.impossible }.filterNot { it.hasCanceledBoardingOrAlighting() }.map { it.scheduleOnly() }
        synchronized(entries) {
            entries[key] = Entry(sanitized, nowMillis())
            while (entries.size > MAX_ENTRIES) entries.entries.iterator().run { next(); remove() }
        }
        persist(query, sanitized)
        return routes
    }

    private suspend fun loadPersisted(query: RouteQuery): StoredPlan? {
        val store = store ?: return null
        return kotlinx.coroutines.withContext(ioContext) {
            Pm.time("disk load") {
                store.load(query.base(), query.at.toInstant().toEpochMilli(), SearchPolicy.DISK_MATCH_BEHIND_MILLIS, SearchPolicy.DISK_MATCH_AHEAD_MILLIS)
            }
        }?.takeIf { wallMillis() - it.storedAt < SearchPolicy.DISK_CACHE_MAX_AGE_MILLIS }
    }

    private suspend fun persist(query: RouteQuery, routes: List<Route>) {
        val store = store ?: return
        kotlinx.coroutines.withContext(NonCancellable + ioContext) {
            store.save(query.base(), query.at.withSecond(0).withNano(0).toInstant().toEpochMilli(), wallMillis(), routes)
        }
    }

    /** Removes searches for times that are over and plans older than a day. */
    fun prune() {
        val now = wallMillis()
        store?.prune(now - SearchPolicy.DISK_PAST_GRACE_MILLIS, now - SearchPolicy.DISK_CACHE_MAX_AGE_MILLIS)
    }

    private fun RouteQuery.base() = "${from.key}|${to.key}|${at.zone.id}|$arrival|$maxChanges|$includeDirect"

    private fun lookup(key: Key): Entry? = synchronized(entries) {
        val entry = entries[key] ?: return@synchronized null
        if (nowMillis() - entry.storedAt >= SearchPolicy.SCHEDULE_TTL_MILLIS) {
            entries.remove(key); null
        } else entry
    }

    private fun RouteQuery.key() = Key(
        from, to, at.withSecond(0).withNano(0).toInstant().toEpochMilli(), at.zone.id,
        arrival, maxChanges, includeDirect,
    )

    private fun Route.scheduleOnly() = copy(
        expectedDepartureDate = null,
        expectedArrivalDate = null,
        partialRoutes = partialRoutes.map { leg -> leg.copy(
            expectedDepartureDate = null,
            expectedArrivalDate = null,
            stations = leg.stations.map { it.copy(expectedDate = null, platform = null, canceled = false) },
        ) },
    )

    private fun Route.hasCanceledBoardingOrAlighting(): Boolean = partialRoutes.any { leg ->
        !leg.isWalk && (leg.stations.firstOrNull()?.canceled == true || leg.stations.lastOrNull()?.canceled == true)
    }

    private companion object { const val MAX_ENTRIES = 48 }
}
