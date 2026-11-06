package dev.padermodern.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

data class TripDraft(val from: Station?, val to: Station?, val at: ZonedDateTime, val arrival: Boolean, val maxChanges: Int)
data class PreparationState(val loading: Boolean = false, val routesReady: Boolean = false)

/** Warms reusable search data only. It never publishes search results. */
class TripPreparation(
    private val scope: CoroutineScope,
    private val loadPlace: suspend (Station, ZonedDateTime, Boolean) -> Unit = TripSearch::preparePlace,
    private val loadRoutes: suspend (Station, Station, ZonedDateTime, Boolean, Int) -> Unit = { f, t, at, arrival, changes -> TripSearch.fetch(f, t, at, arrival, changes) },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val loadDestination: suspend (Station) -> Unit = { TripSearch.endpoints(it, 7); Unit },
) {
    private enum class Kind { ORIGIN, DESTINATION, ROUTE }
    private data class Key(val kind: Kind, val from: Station, val to: Station? = null, val at: ZonedDateTime? = null, val arrival: Boolean = false, val changes: Int = 0)
    private class Work { lateinit var job: Job; var readyAt: Long? = null; var finished = false }
    private val work = LinkedHashMap<Key, Work>()
    private var previewJob: Job? = null
    private var previewLoading = false
    private var previewGeneration = 0
    private var expiryJob: Job? = null
    private val mutableState = MutableStateFlow(PreparationState())
    val state: StateFlow<PreparationState> = mutableState

    fun prepare(draft: TripDraft) {
        val at = draft.at.withSecond(0).withNano(0)
        val desired = buildSet {
            draft.from?.let { add(Key(Kind.ORIGIN, it, at = at, arrival = draft.arrival)) }
            if (draft.to != null) add(Key(Kind.DESTINATION, draft.to))
            if (draft.from != null && draft.to != null) {
                add(Key(Kind.ROUTE, draft.from, draft.to, at, draft.arrival, minOf(1, draft.maxChanges)))
                if (draft.maxChanges >= 2) add(Key(Kind.ROUTE, draft.from, draft.to, at, draft.arrival, 2))
            }
        }
        work.keys.toList().filter { it !in desired }.forEach { work.remove(it)?.job?.cancel() }
        desired.forEach { key -> ensureWork(key) }
        updateState()
    }

    /** Speculative suggestions are intentionally endpoint-only; route warmers belong to confirmed drafts. */
    fun preview(station: Station?, origin: Boolean, at: ZonedDateTime = ZonedDateTime.now(), arrival: Boolean = false) {
        val generation = ++previewGeneration
        previewJob?.cancel()
        previewJob = null
        previewLoading = station != null
        if (station != null) previewJob = scope.launch {
            try { if (origin) loadPlace(station, at, arrival) else loadDestination(station) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* optional speculation */ }
            finally { if (generation == previewGeneration) { previewLoading = false; updateState() } }
        }
        updateState()
    }

    private fun ensureWork(key: Key) {
        val old = work[key]
        val ttl = if (key.kind == Kind.ROUTE) SearchPolicy.SCHEDULE_REFRESH_MILLIS else SearchPolicy.DEPARTURE_TTL_MILLIS
        if (old != null && (old.job.isActive || old.readyAt?.let { nowMillis() - it < ttl } == true)) return
        val item = Work()
        item.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                when (key.kind) {
                    Kind.ORIGIN -> loadPlace(key.from, key.at!!, key.arrival)
                    Kind.DESTINATION -> loadDestination(key.from)
                    Kind.ROUTE -> loadRoutes(key.from, key.to!!, key.at!!, key.arrival, key.changes)
                }
                item.readyAt = nowMillis()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* failed work is retried by the next prepare */ }
            finally { item.finished = true; if (work[key] === item) { scheduleExpiry(); updateState() } }
        }
        work[key] = item
        item.job.start()
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        val deadlines = work.mapNotNull { (key, item) -> item.readyAt?.let { it + if (key.kind == Kind.ROUTE) SearchPolicy.SCHEDULE_TTL_MILLIS else SearchPolicy.DEPARTURE_TTL_MILLIS } }
        val next = deadlines.minOrNull() ?: return
        expiryJob = scope.launch {
            delay((next - nowMillis()).coerceAtLeast(0))
            val now = nowMillis()
            work.entries.removeAll { (key, item) ->
                val ready = item.readyAt ?: return@removeAll false
                now - ready >= if (key.kind == Kind.ROUTE) SearchPolicy.SCHEDULE_TTL_MILLIS else SearchPolicy.DEPARTURE_TTL_MILLIS
            }
            updateState()
            if (work.values.any { it.readyAt != null }) scheduleExpiry()
        }
    }

    private fun updateState() {
        mutableState.value = PreparationState(
            loading = work.values.any { !it.finished } || previewLoading,
            routesReady = work.any { (key, item) -> key.kind == Kind.ROUTE && item.readyAt?.let { nowMillis() - it < SearchPolicy.SCHEDULE_TTL_MILLIS } == true },
        )
    }
}
