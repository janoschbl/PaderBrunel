package dev.padermodern.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.padermodern.data.Departure
import dev.padermodern.data.Line
import dev.padermodern.data.LinePlan
import dev.padermodern.data.LocationProvider
import dev.padermodern.data.NearbyDeparture
import dev.padermodern.data.LiveTrip
import dev.padermodern.data.Route
import dev.padermodern.data.SavedTrip
import dev.padermodern.data.SearchWindow
import dev.padermodern.data.TripSearch
import dev.padermodern.data.TripDraft
import dev.padermodern.data.TripPreparation
import dev.padermodern.data.TripInputs
import dev.padermodern.data.TripResultSet
import dev.padermodern.data.RoutePlans
import dev.padermodern.data.RouteQuery
import dev.padermodern.data.RouteRealtime
import dev.padermodern.data.iso
import dev.padermodern.data.hhmm
import dev.padermodern.data.RequestCache
import dev.padermodern.data.SearchTimeAnchor
import dev.padermodern.data.routePasses
import dev.padermodern.data.additionalRoutePasses
import dev.padermodern.data.SrsApi
import dev.padermodern.data.HabitPredictor
import dev.padermodern.data.LocalTripSource
import dev.padermodern.data.Pm
import dev.padermodern.data.ShadowComparison
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.padermodern.data.Station
import dev.padermodern.data.Store
import dev.padermodern.data.TrafficReport
import dev.padermodern.data.parseTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.roundToInt

data class Loadable<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

private fun Throwable.userMessage(): String = when (this) {
    is java.net.UnknownHostException -> "Keine Internetverbindung"
    is java.net.SocketTimeoutException -> "Server antwortet nicht"
    else -> message ?: "Unbekannter Fehler"
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)

    var userLat by mutableStateOf<Double?>(null); private set
    var userLon by mutableStateOf<Double?>(null); private set

    private suspend fun locate() {
        LocationProvider.current(getApplication())?.let {
            userLat = it.latitude
            userLon = it.longitude
        }
    }

    var nearbyStops by mutableStateOf<List<Station>>(emptyList()); private set
    var nearby by mutableStateOf(Loadable<List<NearbyDeparture>>()); private set
    var usingFallbackLocation by mutableStateOf(false); private set
    private var nearbyJob: Job? = null

    fun refreshNearby() {
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            nearby = nearby.copy(loading = true, error = null)
            runCatching {
                locate()
                usingFallbackLocation = userLat == null
                val lat = userLat ?: LocationProvider.FALLBACK_LAT
                val lon = userLon ?: LocationProvider.FALLBACK_LON
                val stops = SrsApi.nearbyStations(lat, lon, 1000)
                    .filter { it.isStop && it.id != null }
                    .distinctBy { it.name }
                    .sortedBy { it.distance }
                    .take(4)
                nearbyStops = stops
                stops.map { stop ->
                    async {
                        val walk = ((stop.distance / 80.0) + 0.5).roundToInt().coerceAtLeast(1)
                        runCatching { SrsApi.departures(stop.id!!) }.getOrDefault(emptyList())
                            .map { NearbyDeparture(it, walk) }
                    }
                }.awaitAll().flatten()
                    .distinctBy { it.departure.key }
                    .filter { nd ->
                        val t = parseTime(nd.departure.realDeparture) ?: return@filter true
                        t.isAfter(ZonedDateTime.now().minusMinutes(1))
                    }
                    .sortedBy { parseTime(it.departure.realDeparture) }
            }.onSuccess {
                nearby = Loadable(it)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                nearby = nearby.copy(loading = false, error = it.userMessage())
            }
        }
    }

    var boardStation by mutableStateOf<Station?>(null); private set
    var board by mutableStateOf(Loadable<List<Departure>>()); private set

    fun openBoard(station: Station) {
        boardStation = station
        store.addRecent(station)
        refreshBoard()
    }

    fun refreshBoard() {
        val id = boardStation?.id ?: return
        viewModelScope.launch {
            board = board.copy(loading = true, error = null)
            board = runCatching { Loadable(SrsApi.departures(id).distinctBy { it.key }.sortedBy { parseTime(it.realDeparture) }) }
                .getOrElse { Loadable(board.data, false, it.userMessage()) }
        }
    }

    private var boardLive: kotlinx.coroutines.Job? = null

    /** Push for the open board: the backend sends only changes, nothing is polled. Call from a started screen. */
    fun startBoardLive() {
        stopBoardLive()
        val gid = boardStation?.let { dev.padermodern.data.BackendApi.globalId(it) } ?: return
        boardLive = viewModelScope.launch { dev.padermodern.data.LiveWatch.run(listOf(gid), ::applyBoardDelta) }
    }

    fun stopBoardLive() { boardLive?.cancel(); boardLive = null }

    private fun applyBoardDelta(d: dev.padermodern.data.BDelta) {
        val current = board.data ?: return
        dev.padermodern.data.BoardLive.apply(current, d)?.let { board = board.copy(data = it) }
    }

    var selectedDeparture by mutableStateOf<Departure?>(null)

    var from by mutableStateOf<Station?>(null)
    var to by mutableStateOf<Station?>(null)
    var tripTime by mutableStateOf<ZonedDateTime?>(null)
    var arrival by mutableStateOf(false)
    var trips by mutableStateOf(Loadable<List<Route>>()); private set
    var tripFormExpanded by mutableStateOf(true)
    var searchingMore by mutableStateOf(false); private set
    var pagingEarlier by mutableStateOf(false); private set
    var pagingLater by mutableStateOf(false); private set
    var selectedRoute by mutableStateOf<Route?>(null)

    private val resultSet = TripResultSet()
    private val rawRoutes get() = resultSet.routes
    private val paderApp = app as dev.padermodern.PaderApp
    private val routePlans = paderApp.routePlans
    var tripRefreshing by mutableStateOf(false); private set
    var tripPreviewLocal by mutableStateOf(false); private set
    private val timeAnchor = SearchTimeAnchor()
    var tripLiveUpdating by mutableStateOf(false); private set
    var tripLiveUnverified by mutableStateOf(false); private set
    var tripLiveCheckedAt by mutableStateOf<ZonedDateTime?>(null); private set
    var tripScheduleCached by mutableStateOf(false); private set
    private var liveScope: CoroutineScope? = null
    private var liveBoards: RequestCache<String, List<Departure>>? = null
    private val liveRequested = mutableSetOf<String>()
    private val livePending = mutableSetOf<String>()
    private var window: SearchWindow? = null
    private var searchJob: Job? = null
    private var pagingJob: Job? = null
    private var searchGeneration = 0
    private var extendedLoads = 0
    private var submittedInputs: TripInputs? = null
    private var submittedAt: ZonedDateTime? = null
    private var requestedMaxChanges = -1
    private val preparation = TripPreparation(viewModelScope, loadRoutes = { f, t, at, byArrival, cap ->
        routePlans.fetch(RouteQuery(f, t, at, byArrival, cap, rules = store.tripPrefs.value.rules)); Unit
    })
    val tripPreparation = preparation.state

    private fun inputs() = TripInputs(from, to, tripTime, arrival, store.tripPrefs.value.maxChanges)

    init {
        store.loadTrip().let { (f, t) -> from = f; to = t }
        viewModelScope.launch { store.tripPrefs.drop(1).collect { if (rawRoutes.isNotEmpty()) merge(emptyList()) } }
        viewModelScope.launch {
            combine(snapshotFlow { inputs() }, store.tripPrefs) { draft, prefs -> draft.copy(maxChanges = prefs.maxChanges) }
                .collectLatest { draft ->
                    if (submittedInputs?.sameJourney(draft) == false) {
                        searchGeneration++
                        searchJob?.cancel()
                        pagingJob?.cancel()
                        submittedInputs = null
                        submittedAt = null
                        liveScope?.cancel()
                        resultSet.clear()
                        resetLiveState()
                        window = null
                        trips = Loadable()
                        tripFormExpanded = true
                        extendedLoads = 0
                        searchingMore = false
                        pagingEarlier = false
                        pagingLater = false
                        tripRefreshing = false
                        tripPreviewLocal = false
                    }
                    if (submittedInputs?.sameJourney(draft) == true) extendSubmittedSearch(draft.maxChanges)
                    delay(250)
                    prepareTrips()
                }
        }
    }

    fun prepareTrips() {
        preparation.prepare(TripDraft(from, to, timeAnchor.resolve(tripTime), arrival, store.tripPrefs.value.maxChanges))
    }

    fun previewTripStation(station: Station?, origin: Boolean) =
        preparation.preview(station, origin, timeAnchor.resolve(tripTime), arrival)

    fun myLocationStation(): Station? {
        val lat = userLat ?: return null
        val lon = userLon ?: return null
        return Station(type = "coord", name = "Mein Standort", latitude = lat, longitude = lon)
    }

    fun locateForTrip(onResult: (Station?) -> Unit) = viewModelScope.launch {
        locate()
        onResult(myLocationStation())
    }

    fun swap() {
        val f = from
        from = to
        to = f
        tripFormExpanded = true
    }

    private suspend fun fetch(f: Station, t: Station, at: ZonedDateTime, byArrival: Boolean, maxChanges: Int, generation: Int, includeDirect: Boolean) {
        routePlans.fetch(RouteQuery(f, t, at, byArrival, maxChanges, includeDirect, store.tripPrefs.value.rules), onBatch = {
            if (generation == searchGeneration) merge(it)
        })
    }

    private fun merge(extra: List<Route>) {
        resultSet.add(extra)
        selectedRoute?.key?.let { key -> rawRoutes.firstOrNull { it.key == key }?.let { selectedRoute = it } }
        val w = window ?: return
        val ranked = TripSearch.rank(rawRoutes, w, store.tripPrefs.value)
        // empty first batch must not flash no connection
        if (ranked.isNotEmpty() || trips.data != null) trips = trips.copy(data = ranked, loading = false)
        refreshVisible(ranked)
    }

    private fun extendSubmittedSearch(cap: Int) {
        val active = submittedInputs ?: return
        val at = submittedAt ?: return
        val f = active.from ?: return
        val t = active.to ?: return
        val extra = additionalRoutePasses(requestedMaxChanges, cap)
        if (extra.isEmpty()) return
        requestedMaxChanges = cap
        val generation = searchGeneration
        liveScope?.launch {
            try {
                loadPasses(f, t, at, active.arrival, cap, generation, passes = extra)
                merge(emptyList())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                trips = trips.copy(error = "Weitere Umstiege konnten nicht vollständig geprüft werden: ${e.userMessage()}")
            }
        }
    }

    private fun resetLiveState() {
        liveRequested.clear()
        livePending.clear()
        tripLiveUpdating = false
        tripLiveUnverified = false
        tripLiveCheckedAt = null
        tripScheduleCached = false
    }

    private fun refreshVisible(routes: List<Route>) {
        val scope = liveScope ?: return
        val boards = liveBoards ?: return
        val generation = searchGeneration
        routes.filter { liveRequested.add(it.key) }.forEach { route ->
            livePending.add(route.key)
            tripLiveUpdating = true
            scope.launch {
                val fresh = RouteRealtime.refresh(route) { id, at ->
                    val minute = at.withSecond(0).withNano(0)
                    boards.get("$id|${minute.toEpochSecond()}") { SrsApi.departures(id, minute, fresh = true) }
                }
                if (generation != searchGeneration) return@launch
                resultSet.update(fresh)
                if (!realtimeLogged) { realtimeLogged = true; Pm.note("realtime first result ${(System.nanoTime() - searchStartedAt) / 1_000_000} ms after tap verified=${fresh.verified}") }
                if (!fresh.verified) tripLiveUnverified = true
                livePending.remove(route.key)
                merge(emptyList())
                tripLiveUpdating = livePending.isNotEmpty()
                if (!tripLiveUpdating) tripLiveCheckedAt = ZonedDateTime.now()
            }
        }
    }

    // The 2-change pass starts only after the 1-change pass: together they overloaded the server and caused 502s.
    private suspend fun loadPasses(f: Station, t: Station, at: ZonedDateTime, byArrival: Boolean, cap: Int, generation: Int, includeDirect: Boolean = true, passes: List<Int> = routePasses(cap)) {
        var failure: Throwable? = null
        for (changes in passes) {
            if (changes >= 2) { extendedLoads++; searchingMore = true }
            try {
                runCatching { fetch(f, t, at, byArrival, changes, generation, includeDirect) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; failure = failure ?: it }
            } finally {
                if (changes >= 2 && generation == searchGeneration) {
                    extendedLoads--
                    searchingMore = extendedLoads > 0
                }
            }
        }
        failure?.let { throw it }
    }

    fun searchTrips() {
        val f = from ?: return
        val t = to ?: return
        store.addRecent(f)
        store.addRecent(t)
        store.saveTrip(f, t)
        val now = ZonedDateTime.now()
        val at = timeAnchor.resolve(tripTime, now)
        val byArrival = arrival
        submittedInputs = inputs()
        submittedAt = at
        val generation = ++searchGeneration
        searchJob?.cancel()
        pagingJob?.cancel()
        liveScope?.cancel()
        resetLiveState()
        liveScope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
        liveBoards = RequestCache(liveScope!!, 15_000, 24)
        resultSet.clear()
        window = TripSearch.window(tripTime ?: now, byArrival)
        tripFormExpanded = false
        trips = Loadable(loading = true)
        extendedLoads = 0
        searchingMore = false
        pagingEarlier = false
        pagingLater = false
        val cap = store.tripPrefs.value.maxChanges
        requestedMaxChanges = cap
        val passes = routePasses(cap)
        passes.forEach { changes -> routePlans.cached(RouteQuery(f, t, at, byArrival, changes, rules = store.tripPrefs.value.rules))?.let {
            tripScheduleCached = true
            merge(it)
        } }
        tripPreviewLocal = false
        tripRefreshing = true
        searchStartedAt = System.nanoTime(); realtimeLogged = false; localRanked = emptyList()
        recordHabit(f, t, byArrival, tripTime == null)
        val localJob = startLocalPreview(f, t, at, byArrival, cap, generation)
        searchJob = viewModelScope.launch {
            var failed = false
            dev.padermodern.data.UserSearches.begin()
            try {
            try {
                loadPasses(f, t, at, byArrival, cap, generation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                if (resultSet.hasLocal) {
                    // the preview stays; say that it is not confirmed instead of hiding it
                    trips = trips.copy(loading = false, error = "Aktualisierung fehlgeschlagen, zeige lokalen Fahrplan: ${e.userMessage()}")
                } else trips = trips.copy(loading = false, error = "Fahrplansuche unvollständig: ${e.userMessage()}")
            }
            localJob.join()
            if (generation == searchGeneration) {
                if (!failed) {
                    shadowCompare()
                    resultSet.dropLocal()
                    tripPreviewLocal = false
                    merge(emptyList())
                }
                tripRefreshing = false
            }
            if (trips.data == null && trips.error == null) trips = Loadable(emptyList())
            } finally { dev.padermodern.data.UserSearches.end() }
        }
    }

    private var localRanked: List<Route> = emptyList()
    private var searchStartedAt = 0L
    private var realtimeLogged = false

    private fun recordHabit(f: Station, t: Station, byArrival: Boolean, nowSearch: Boolean) {
        if (!nowSearch) return
        val now = ZonedDateTime.now()
        viewModelScope.launch(Dispatchers.Default) {
            val predicted = HabitPredictor.predict(paderApp.habits.all(), now, 2, horizonMin = 15)
                .any { it.from.key == f.key && it.to.key == t.key }
            store.countHabitSearch(predicted)
            Pm.note("habit search predicted=$predicted ${store.habitStats()}")
            paderApp.habits.record(f, t, byArrival, now.dayOfWeek.value, now.hour, now.minute)
        }
    }

    /** Answers from the on-device timetable in milliseconds; the provider search then replaces it. */
    private fun startLocalPreview(f: Station, t: Station, at: ZonedDateTime, byArrival: Boolean, cap: Int, generation: Int): Job =
        viewModelScope.launch(Dispatchers.Default) {
            if (byArrival || !paderApp.timetables.anyAvailable()) return@launch
            val source = LocalTripSource(paderApp.timetables::tableFor, store.tripPrefs.value.minTransferMin)
            if (!source.covers(f, t, at)) return@launch
            val routes = try {
                Pm.time("local search") { TripSearch.fetch(f, t, at, false, cap, includeDirect = false, source = source) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return@launch }
            withContext(Dispatchers.Main.immediate) {
                if (generation != searchGeneration) return@withContext
                localRanked = routes
                resultSet.addLocal(routes)
                tripPreviewLocal = routes.isNotEmpty()
                merge(emptyList())
                Pm.note("local preview shown ${trips.data?.size ?: 0} rows")
            }
        }

    /** P5: how often the on-device planner agrees with the provider on the first three connections. */
    private fun shadowCompare() {
        val w = window ?: return
        if (localRanked.isEmpty() || resultSet.serverRoutes.isEmpty()) return
        val prefs = store.tripPrefs.value
        val result = ShadowComparison.compare(TripSearch.rank(resultSet.serverRoutes, w, prefs), TripSearch.rank(localRanked, w, prefs))
        store.countShadow(result.matched, result.total)
        Pm.note("shadow matched=${result.matched}/${result.total} ${store.shadowStats()}")
    }

    /** Debug only (see MainActivity): resolves two names, searches, and logs time to first rows and to completion. */
    fun benchSearch(fromName: String, toName: String, hhmm: String?, seq: String, plusDays: Long = 0) {
        viewModelScope.launch {
            try {
                suspend fun stop(name: String): Station {
                    var last: Exception? = null
                    repeat(5) {
                        try { return SrsApi.searchStations(name, onlyStations = true).firstOrNull { it.isRealStop } ?: SrsApi.searchStations(name).first() }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { last = e; delay(1_000) }
                    }
                    // provider down: fall back to the stop catalog inside the bundled timetable
                    val tt = paderApp.timetables.tableFor(ZonedDateTime.now().plusDays(plusDays).toLocalDate())
                    val i = (0 until (tt?.stopCount ?: 0)).firstOrNull { tt!!.stopNames[it].equals(name, ignoreCase = true) } ?: throw last!!
                    return Station(type = "stop", id = tt!!.stopIds[i], name = tt.stopNames[i], latitude = tt.lat[i], longitude = tt.lon[i])
                }
                val f = stop(fromName); val t = stop(toName)
                from = f; to = t; arrival = false
                tripTime = hhmm?.let { ZonedDateTime.now().plusDays(plusDays).withHour(it.substringBefore(':').toInt()).withMinute(it.substringAfter(':').toInt()).withSecond(0).withNano(0) }
                val t0 = System.nanoTime()
                searchTrips()
                val gen = searchGeneration
                val first = kotlinx.coroutines.withTimeoutOrNull(90_000) { snapshotFlow { (trips.data?.size ?: 0) > 0 }.first { it } }
                val firstMs = (System.nanoTime() - t0) / 1_000_000
                val done = kotlinx.coroutines.withTimeoutOrNull(90_000) { snapshotFlow { !tripRefreshing && !searchingMore && !trips.loading }.first { it } }
                val doneMs = (System.nanoTime() - t0) / 1_000_000
                trips.data.orEmpty().forEach { r ->
                    Pm.note("ROW ${r.departureDate.hhmm()}->${r.arrivalDate.hhmm()} chg=${r.changes} " + r.rides.joinToString(">") { it.shortName ?: "?" })
                }
                Pm.note("BENCH $seq first=${if (first == null) -1 else firstMs} done=${if (done == null) -1 else doneMs} rows=${trips.data?.size ?: 0} local=$tripPreviewLocal err=${trips.error != null} gen=$gen")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Pm.note("BENCH $seq failed ${e.message}")
            }
        }
    }

    fun pageTrips(next: Boolean) {
        val active = submittedInputs ?: return
        val f = active.from ?: return
        val t = active.to ?: return
        val w = window ?: return
        if (pagingEarlier || pagingLater) return
        val generation = searchGeneration
        pagingJob = viewModelScope.launch {
            if (next) pagingLater = true else pagingEarlier = true
            try {
                val limit = w.limit + if (next != w.byArrival) 2 else 0
                val nearLimit = w.nearLimit + if (next == w.byArrival) 2 else 0
                if (next) {
                    val newTo = w.to.plusMinutes(90)
                    window = w.copy(to = newTo, limit = limit, nearLimit = nearLimit)
                    loadPasses(f, t, if (w.byArrival) newTo else w.to, w.byArrival, store.tripPrefs.value.maxChanges, generation)
                } else {
                    val newFrom = w.from.minusMinutes(90)
                    window = w.copy(from = newFrom, limit = limit, nearLimit = nearLimit)
                    loadPasses(f, t, w.from, true, store.tripPrefs.value.maxChanges, generation, includeDirect = false)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                trips = trips.copy(error = e.userMessage())
            }
            pagingEarlier = false
            pagingLater = false
        }
    }

    var liveRoutes by mutableStateOf<Map<String, Route>>(emptyMap()); private set
    var liveTripId by mutableStateOf<String?>(null)

    fun routeFor(t: SavedTrip): Route = liveRoutes[t.id] ?: t.route

    fun toggleSaved(r: Route) {
        val f = r.partialRoutes.firstOrNull()?.stations?.firstOrNull()?.displayName ?: from?.displayName.orEmpty()
        val t = r.partialRoutes.lastOrNull()?.stations?.lastOrNull()?.displayName ?: to?.displayName.orEmpty()
        store.toggleTrip(r, f, t)
    }

    fun refreshSaved(trip: SavedTrip) {
        viewModelScope.launch {
            val fresh = runCatching { LiveTrip.refresh(routeFor(trip)) }.getOrNull() ?: return@launch
            liveRoutes = liveRoutes + (trip.id to fresh)
        }
    }

    var tailAlternatives by mutableStateOf<Loadable<List<Route>>?>(null); private set
    private var tailJob: Job? = null
    private var tailSearch: LiveTrip.TailSearch? = null

    fun findTailAlternatives(trip: SavedTrip, fromLeg: Int) {
        val t = LiveTrip.tailSearch(routeFor(trip), fromLeg) ?: return
        tailSearch = t
        tailJob?.cancel()
        tailAlternatives = Loadable(loading = true)
        tailJob = viewModelScope.launch {
            val prefs = store.tripPrefs.value
            val found = LinkedHashMap<String, Route>()
            fun add(rs: List<Route>) {
                rs.filter { LiveTrip.fitsTail(it, t, prefs.minTransferMin) }.forEach { found[it.key] = it }
                val sorted = found.values.sortedWith(compareBy({ parseTime(it.realArrival) }, { parseTime(it.departureDate) })).take(10)
                tailAlternatives = Loadable(sorted, loading = true)
            }
            try {
                TripSearch.fetch(t.from, t.to, t.notBefore, false, 1, onBatch = ::add, rules = prefs.rules)
                if (prefs.maxChanges >= 2) runCatching { TripSearch.fetch(t.from, t.to, t.notBefore, false, 2, onBatch = ::add, rules = prefs.rules) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                tailAlternatives = Loadable(tailAlternatives?.data.orEmpty())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                tailAlternatives = if (found.isEmpty()) Loadable(error = e.userMessage()) else Loadable(tailAlternatives?.data)
            }
        }
    }

    fun applyTailAlternative(trip: SavedTrip, alt: Route) {
        val t = tailSearch ?: return
        val merged = LiveTrip.replaceTail(routeFor(trip), t, alt)
        store.updateTripRoute(trip.id, merged)
        liveRoutes = liveRoutes + (trip.id to merged)
        closeTailAlternatives()
    }

    fun closeTailAlternatives() {
        tailJob?.cancel()
        tailAlternatives = null
    }

    fun refreshActiveTrips() {
        val lead = maxOf(store.leadMinutes.value.toLong(), 60L)
        store.savedTrips.value
            .filter { it.id == liveTripId || LiveTrip.isActive(it.route, lead) }
            .forEach { refreshSaved(it) }
    }

    var reports by mutableStateOf(Loadable<List<TrafficReport>>()); private set
    var linePlans by mutableStateOf(Loadable<List<LinePlan>>()); private set
    var lines by mutableStateOf(Loadable<List<Line>>()); private set

    fun loadInfo(force: Boolean = false) {
        if (!force && reports.data != null) return
        viewModelScope.launch {
            reports = reports.copy(loading = true, error = null)
            linePlans = linePlans.copy(loading = true, error = null)
            lines = lines.copy(loading = true, error = null)
            val r = async { runCatching { SrsApi.trafficReports() } }
            val p = async { runCatching { SrsApi.linePlans() } }
            val l = async { runCatching { SrsApi.lines() } }
            reports = r.await().fold({ Loadable(it.sortedByDescending { x -> x.warningsCount }) }, { Loadable(error = it.userMessage()) })
            linePlans = p.await().fold({ Loadable(it) }, { Loadable(error = it.userMessage()) })
            lines = l.await().fold({ Loadable(it) }, { Loadable(error = it.userMessage()) })
        }
    }

    var mapStops by mutableStateOf<List<Station>>(emptyList()); private set
    private val loadedCenters = mutableListOf<Pair<Double, Double>>()
    private var mapJob: Job? = null

    fun loadMapStops(lat: Double, lon: Double) {
        if (mapJob?.isActive == true) return
        if (loadedCenters.any { android.location.Location("").apply { latitude = it.first; longitude = it.second }.distanceTo(android.location.Location("").apply { latitude = lat; longitude = lon }) < 8000 }) return
        mapJob = viewModelScope.launch {
            runCatching { SrsApi.mapObjects(lat, lon, 15000, 1500) }.onSuccess { r ->
                loadedCenters += lat to lon
                mapStops = (mapStops + r.filter { it.id != null && it.isStop }).distinctBy { it.id }
            }
        }
    }

    suspend fun stopPlatforms(s: Station): List<Pair<String, List<Departure>>> {
        val id = s.id ?: return emptyList()
        return runCatching { SrsApi.departures(id) }.getOrDefault(emptyList())
            .groupBy { it.steig ?: "" }
            .toSortedMap()
            .map { (k, v) -> k to v }
    }

    fun ensureLocation() = viewModelScope.launch { locate() }
}
