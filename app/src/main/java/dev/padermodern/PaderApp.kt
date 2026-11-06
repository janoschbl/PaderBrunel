package dev.padermodern

import android.app.Application
import android.content.pm.ApplicationInfo
import dev.padermodern.data.HabitPrefetcher
import dev.padermodern.data.Pm
import dev.padermodern.data.PrefetchLedger
import dev.padermodern.data.RoutePlans
import dev.padermodern.data.SearchPolicy
import dev.padermodern.data.SqliteHabitStore
import dev.padermodern.data.SqlitePlanStore
import dev.padermodern.data.SrsApi
import dev.padermodern.data.Store
import dev.padermodern.data.TimetableStore
import dev.padermodern.data.Timetables
import dev.padermodern.data.scheduleTimetableCrawl
import dev.padermodern.data.prefetchAllowed
import dev.padermodern.data.schedulePrefetch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

class PaderApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val routePlans by lazy { RoutePlans(store = SqlitePlanStore(this)) }
    val timetables by lazy { Timetables(TimetableStore(this), assets) }
    val habits by lazy { SqliteHabitStore(this) }
    val prefetcher by lazy {
        HabitPrefetcher(habits, routePlans, { Store(this).tripPrefs.value }, { prefetchAllowed(this) }, PrefetchLedger(this))
    }

    override fun onCreate() {
        super.onCreate()
        Pm.enabled = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        scope.launch {
            timetables.load()
            // parse today and tomorrow now; later days load on their first search
            val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Berlin"))
            timetables.tableFor(today); timetables.tableFor(today.plusDays(1))
        }
        scheduleTimetableCrawl(this)
        scope.launch {
            // Open the TLS connection first: OkHttp pools it for the first real search.
            SrsApi.warmUp()
            routePlans.prune()
            prefetcher.run(ZonedDateTime.now(), SearchPolicy.PREFETCH_AT_STARTUP)
            schedulePrefetch(this@PaderApp, habits)
        }
    }
}
