package dev.padermodern.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import dev.padermodern.data.SrsApi
import dev.padermodern.data.Station
import dev.padermodern.data.Store
import dev.padermodern.data.TrafficReport
import dev.padermodern.data.parseTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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

    private var rawRoutes: List<Route> = emptyList()
    private var window: SearchWindow? = null
    private var searchJob: Job? = null
    private val fetchCache = HashMap<String, Pair<Long, List<Route>>>()

    init {
        store.loadTrip().let { (f, t) -> from = f; to = t }
        viewModelScope.launch { store.tripPrefs.drop(1).collect { if (rawRoutes.isNotEmpty()) merge(emptyList()) } }
    }

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
        if (from != null && to != null) searchTrips()
    }

    // 60 s cache so flipping between searches is instant
    private suspend fun fetch(f: Station, t: Station, at: ZonedDateTime, byArrival: Boolean, maxChanges: Int) {
        val key = "${f.key}|${t.key}|${at.toEpochSecond() / 60}|$byArrival|$maxChanges"
        fetchCache[key]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { merge(it.second); return }
        TripSearch.fetch(f, t, at, byArrival, maxChanges, onBatch = ::merge)
            .also { fetchCache[key] = System.currentTimeMillis() to it }
    }

    private fun merge(extra: List<Route>) {
        rawRoutes = (rawRoutes + extra).distinctBy { it.key }
        val w = window ?: return
        val ranked = TripSearch.rank(rawRoutes, w, store.tripPrefs.value)
        // empty first batch must not flash no connection
        if (ranked.isNotEmpty() || trips.data != null) trips = Loadable(ranked)
    }

    // one change returns in ~1.5 s, two changes take ~10 s
    private suspend fun loadPasses(f: Station, t: Station, at: ZonedDateTime, byArrival: Boolean) {
        val cap = store.tripPrefs.value.maxChanges
        fetch(f, t, at, byArrival, minOf(1, cap))
        if (cap < 2) return
        searchingMore = true
        try {
            runCatching { fetch(f, t, at, byArrival, 2) }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        } finally {
            searchingMore = false
        }
    }

    fun searchTrips() {
        val f = from ?: return
        val t = to ?: return
        store.addRecent(f)
        store.addRecent(t)
        store.saveTrip(f, t)
        val at = tripTime ?: ZonedDateTime.now()
        val byArrival = arrival
        searchJob?.cancel()
        rawRoutes = emptyList()
        window = TripSearch.window(at, byArrival)
        tripFormExpanded = false
        searchJob = viewModelScope.launch {
            trips = Loadable(loading = true)
            searchingMore = false
            try {
                loadPasses(f, t, at, byArrival)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (rawRoutes.isEmpty()) trips = Loadable(error = e.userMessage())
            }
            if (trips.data == null && trips.error == null) trips = Loadable(emptyList())
        }
    }

    fun pageTrips(next: Boolean) {
        val f = from ?: return
        val t = to ?: return
        val w = window ?: return
        if (pagingEarlier || pagingLater) return
        viewModelScope.launch {
            if (next) pagingLater = true else pagingEarlier = true
            try {
                val limit = w.limit + if (next != w.byArrival) 2 else 0
                val nearLimit = w.nearLimit + if (next == w.byArrival) 2 else 0
                if (next) {
                    val newTo = w.to.plusMinutes(90)
                    window = w.copy(to = newTo, limit = limit, nearLimit = nearLimit)
                    loadPasses(f, t, if (w.byArrival) newTo else w.to, w.byArrival)
                } else {
                    val newFrom = w.from.minusMinutes(90)
                    window = w.copy(from = newFrom, limit = limit, nearLimit = nearLimit)
                    loadPasses(f, t, w.from, true)
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
                TripSearch.fetch(t.from, t.to, t.notBefore, false, 1, onBatch = ::add)
                if (prefs.maxChanges >= 2) runCatching { TripSearch.fetch(t.from, t.to, t.notBefore, false, 2, onBatch = ::add) }
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
