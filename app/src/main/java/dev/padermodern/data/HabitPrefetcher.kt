package dev.padermodern.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.padermodern.PaderApp
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Counts user-started searches; prefetching stands down while any is running. */
object UserSearches {
    private val running = java.util.concurrent.atomic.AtomicInteger()
    val active: Boolean get() = running.get() > 0
    inline fun <T> track(block: () -> T): T { begin(); try { return block() } finally { end() } }
    fun begin() { running.incrementAndGet() }
    fun end() { running.decrementAndGet() }
}

/** Warms the plan cache for the routes this person usually searches around now. Yields to user searches. */
class HabitPrefetcher(
    private val habits: HabitStore,
    private val plans: RoutePlans,
    private val prefs: () -> TripPrefs,
    private val allowed: () -> Boolean,
    private val ledger: PrefetchLedger,
) {
    suspend fun run(now: ZonedDateTime, maxRoutes: Int): Int {
        if (!allowed()) { Pm.note("prefetch not allowed (battery/data saver)"); return 0 }
        val budget = minOf(maxRoutes, SearchPolicy.PREFETCH_PER_HOUR - ledger.usedLastHour(now.toInstant().toEpochMilli()))
        if (budget <= 0) return 0
        val picks = HabitPredictor.predict(habits.all(), now, budget)
        Pm.note("prefetch budget=$budget picks=${picks.size}")
        for (h in picks) {
            if (UserSearches.active) { Pm.note("prefetch yields to user search"); break }
            ledger.record(now.toInstant().toEpochMilli())
            Pm.note("prefetch ${h.from.name} -> ${h.to.name} (seen ${h.count}x)")
            routePasses(prefs().maxChanges).forEach { cap ->
                runCatching { plans.fetch(RouteQuery(h.from, h.to, now, h.arrival, cap)) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
        }
        return picks.size
    }
}

class PrefetchLedger(private val context: Context) {
    private val prefs = context.getSharedPreferences("prefetch", Context.MODE_PRIVATE)
    fun usedLastHour(nowMillis: Long): Int = stamps().count { nowMillis - it < 3_600_000L }
    fun record(nowMillis: Long) {
        val kept = stamps().filter { nowMillis - it < 3_600_000L } + nowMillis
        prefs.edit().putString("stamps", kept.joinToString(",")).apply()
    }
    private fun stamps() = prefs.getString("stamps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
}

/** Battery above 20 percent and not in data saver mode. */
fun prefetchAllowed(context: Context): Boolean {
    val sticky = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    if (level >= 0 && level * 100 / scale < SearchPolicy.PREFETCH_MIN_BATTERY_PERCENT) return false
    val cm = context.getSystemService(ConnectivityManager::class.java)
    // data saver only limits metered networks
    return !(cm.isActiveNetworkMetered && cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
}

class PrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PaderApp
        app.prefetcher.run(ZonedDateTime.now(), 2)
        schedulePrefetch(applicationContext, app.habits)
        return Result.success()
    }
}

/** Schedules one worker shortly before the next usual search time; the worker schedules the next one. */
fun schedulePrefetch(context: Context, habits: HabitStore) {
    val delay = HabitPredictor.nextWarmDelayMinutes(habits.all(), ZonedDateTime.now()) ?: 60L
    val request = OneTimeWorkRequestBuilder<PrefetchWorker>()
        .setInitialDelay(delay.coerceIn(1, 7 * 24 * 60L), TimeUnit.MINUTES)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork("habit-prefetch", ExistingWorkPolicy.REPLACE, request)
}
