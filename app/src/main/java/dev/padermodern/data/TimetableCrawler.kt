package dev.padermodern.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.padermodern.PaderApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

data class CrawlReport(val day: LocalDate, val stops: Int, val requests: Int, val trips: Int, val seconds: Long, val failedRequests: Int)

/**
 * Builds a day of local trips by walking departure boards: start at a hub, then follow every line to its
 * far end so the opposite direction is covered too. Heavy (about 0.5 MB per board), so it only runs on
 * unmetered networks while charging, see [scheduleTimetableCrawl].
 */
class TimetableCrawler(
    private val board: suspend (stopId: String, at: ZonedDateTime) -> List<Departure>,
    private val centerLat: Double = 51.7189, private val centerLon: Double = 8.7575,
    private val radiusMeters: Double = 25_000.0,
    private val parallelism: Int = 3,
    private val interiorMeters: Double = 8_000.0,
) {
    suspend fun crawl(day: LocalDate, hubs: List<String>, maxStops: Int): Pair<LocalTimetable, CrawlReport> = coroutineScope {
        val t0 = System.nanoTime()
        val builder = TimetableBuilder(day)
        val lock = Any()
        val done = HashSet<String>()
        var requests = 0; var failed = 0
        val gate = Semaphore(parallelism)

        suspend fun crawlStop(stop: String): Set<String> {
            val next = HashSet<String>()
            var at = day.atTime(LocalTime.of(3, 0)).atZone(BERLIN)
            val end = day.plusDays(1).atTime(LocalTime.of(3, 0)).atZone(BERLIN)
            var lastSeen: ZonedDateTime? = null
            while (at.isBefore(end)) {
                val deps = gate.withPermit {
                    var result: List<Departure>? = null
                    // the server answers 502 in bursts; a board is retried with a growing pause before it counts as failed
                    for (attempt in 1..BOARD_ATTEMPTS) {
                        try { result = board(stop, at); synchronized(lock) { requests++ }; break }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { if (attempt < BOARD_ATTEMPTS) kotlinx.coroutines.delay(1_500L * attempt) }
                    }
                    if (result == null) synchronized(lock) { failed++ }
                    result ?: emptyList()
                }
                if (deps.isEmpty()) break
                synchronized(lock) {
                    deps.forEach { d ->
                        val last = builder.add(d) ?: return@forEach
                        if (distance(last.latitude, last.longitude) <= radiusMeters) d.destinationID?.let { next += it }
                    }
                }
                val latest = deps.mapNotNull { parseTime(it.departureDate) }.maxOrNull() ?: break
                if (lastSeen != null && !latest.isAfter(lastSeen)) break
                lastSeen = latest
                at = latest.withZoneSameInstant(BERLIN)
            }
            return next
        }

        // Each round: some line termini (their boards hold whole trips) and some busy interior stops (they
        // reveal lines no crawled terminus has led to yet).
        val termini = ArrayDeque(hubs)
        while (done.size < maxStops) {
            val batch = ArrayList<String>()
            while (termini.isNotEmpty() && batch.size < parallelism) termini.removeFirst().let { if (it !in done) batch += it }
            if (interiorMeters > 0) synchronized(lock) {
                batch += builder.busiest(done + batch, { la, lo -> distance(la, lo) <= interiorMeters }, parallelism)
            }
            if (batch.isEmpty()) break
            val take = batch.take(maxStops - done.size)
            done.addAll(take)
            val found = take.map { async { crawlStop(it) } }.awaitAll().flatten()
            found.forEach { if (it !in done && it !in termini) termini.addLast(it) }
        }
        val table = synchronized(lock) { builder.build() }
        table to CrawlReport(day, done.size, requests, table.trips.size, (System.nanoTime() - t0) / 1_000_000_000, failed)
    }

    private fun distance(lat: Double, lon: Double): Double {
        val dy = (lat - centerLat) * 111_320.0
        val dx = (lon - centerLon) * 111_320.0 * kotlin.math.cos(Math.toRadians(centerLat))
        return kotlin.math.hypot(dx, dy)
    }

    private companion object { val BERLIN: ZoneId = ZoneId.of("Europe/Berlin"); const val BOARD_ATTEMPTS = 4 }
}

/** Files under filesDir: one gzip per day. Old days are removed on load. */
class TimetableStore(context: Context) {
    private val dir = context.filesDir

    private fun file(day: LocalDate) = File(dir, "timetable-$day.gz")

    fun load(today: LocalDate = LocalDate.now(ZoneId.of("Europe/Berlin"))): Map<LocalDate, LocalTimetable> {
        dir.listFiles { f -> f.name.startsWith("timetable-") && f.name.endsWith(".gz") }.orEmpty().forEach { f ->
            val day = runCatching { LocalDate.parse(f.name.removePrefix("timetable-").removeSuffix(".gz")) }.getOrNull()
            if (day == null || day.isBefore(today)) f.delete()
        }
        return dir.listFiles { f -> f.name.startsWith("timetable-") && f.name.endsWith(".gz") }.orEmpty().mapNotNull { f ->
            runCatching { Pm.time("timetable load ${f.name}") { f.inputStream().use(LocalTimetable::read) } }.getOrNull()
        }.associateBy { it.day }
    }

    fun save(table: LocalTimetable) {
        val tmp = File(dir, "timetable-${table.day}.tmp")
        tmp.outputStream().use { table.write(it) }
        tmp.renameTo(file(table.day))
    }

    fun has(day: LocalDate) = file(day).exists()
}

class TimetableWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PaderApp
        val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
        val crawler = TimetableCrawler(board = { id, at -> SrsApi.departures(id, at, fresh = true) })
        for (day in listOf(today, today.plusDays(1))) {
            if (app.timetables.hasFresh(day)) continue
            val (table, report) = crawler.crawl(day, listOf(SearchPolicy.HUB_PADERBORN_HBF), SearchPolicy.CRAWL_MAX_STOPS)
            Pm.note("crawl $report")
            if (report.failedRequests > report.requests / 10 + 3) return Result.retry()
            app.timetables.put(table)
        }
        return Result.success()
    }
}

/**
 * Timetables by service day. The bundled GTFS-derived days (assets/timetables, see research/gtfs_to_pmtt.py) are complete
 * for the region; crawled days from [TimetableStore] fill dates the assets do not cover.
 */
class Timetables(private val store: TimetableStore, private val assets: android.content.res.AssetManager? = null) {
    private val cache = java.util.concurrent.ConcurrentHashMap<LocalDate, LocalTimetable>()
    private val assetFiles: Map<LocalDate, String> by lazy {
        runCatching {
            assets!!.open("timetables/index.txt").bufferedReader().readLines().mapNotNull { line ->
                line.trim().split(' ').takeIf { it.size == 2 }?.let { LocalDate.parse(it[0]) to it[1] }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private val crawled = java.util.concurrent.ConcurrentHashMap<LocalDate, LocalTimetable>()

    fun load() { crawled.putAll(store.load()) }

    /** Blocking (parses a file on first use); call from a background dispatcher. */
    fun tableFor(day: LocalDate): LocalTimetable? {
        cache[day]?.let { return it }
        val file = assetFiles[day] ?: return crawled[day]
        val table = try {
            Pm.time("timetable asset $file") { assets!!.open("timetables/$file").use { LocalTimetable.read(it, day) } }
        } catch (e: Exception) {
            Pm.note("timetable asset $file unreadable: $e"); return null
        }
        return cache.putIfAbsent(day, table) ?: table
    }

    fun put(table: LocalTimetable) {
        store.save(table)
        crawled[table.day] = table
    }

    /** True when the day is covered by anything, so the crawler has nothing to add. */
    fun hasFresh(day: LocalDate) = crawled.containsKey(day) || assetFiles.containsKey(day)

    fun anyAvailable() = crawled.isNotEmpty() || assetFiles.isNotEmpty()
}

fun scheduleTimetableCrawl(context: Context) {
    val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
    // starts as soon as the phone is charging on Wi-Fi, instead of waiting for the daily slot
    WorkManager.getInstance(context).enqueueUniqueWork("timetable-crawl-now", androidx.work.ExistingWorkPolicy.KEEP,
        androidx.work.OneTimeWorkRequestBuilder<TimetableWorker>().setConstraints(constraints).build())
    val request = PeriodicWorkRequestBuilder<TimetableWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("timetable-crawl", ExistingPeriodicWorkPolicy.KEEP, request)
}
