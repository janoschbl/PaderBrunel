package dev.padermodern.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer

enum class ThemeMode(val label: String) { System("System"), Light("Hell"), Dark("Dunkel") }

enum class RouteStyle(val label: String, val hint: String) {
    Fastest("Schnell", "Kürzeste Reisezeit, auch mit knappen Umstiegen"),
    Balanced("Ausgewogen", "Ein Umstieg mehr nur, wenn er spürbar Zeit spart"),
    Relaxed("Entspannt", "Wenige Umstiege und Puffer, auch wenn es etwas länger dauert"),
}

data class TripPrefs(
    val style: RouteStyle = RouteStyle.Balanced,
    val minTransferMin: Int = 3,
    val maxChanges: Int = 2,
)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("padermodern", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(Station.serializer())

    private val _favorites = MutableStateFlow(read("favorites"))
    val favorites: StateFlow<List<Station>> = _favorites

    private val _recents = MutableStateFlow(read("recents"))
    val recents: StateFlow<List<Station>> = _recents

    private val tripSerializer = ListSerializer(SavedTrip.serializer())

    private val _savedTrips = MutableStateFlow(readTrips())
    val savedTrips: StateFlow<List<SavedTrip>> = _savedTrips

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "System") }.getOrDefault(ThemeMode.System),
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    private val _tripBar = MutableStateFlow(prefs.getBoolean("tripBar", true))
    val tripBar: StateFlow<Boolean> = _tripBar

    private val _leadMinutes = MutableStateFlow(prefs.getInt("leadMinutes", 30))
    val leadMinutes: StateFlow<Int> = _leadMinutes

    private val _tripPrefs = MutableStateFlow(
        TripPrefs(
            style = runCatching { RouteStyle.valueOf(prefs.getString("routeStyle", null) ?: "") }.getOrDefault(RouteStyle.Balanced),
            minTransferMin = prefs.getInt("minTransfer", 3),
            maxChanges = prefs.getInt("maxChanges", 2),
        ),
    )
    val tripPrefs: StateFlow<TripPrefs> = _tripPrefs

    fun setTripPrefs(p: TripPrefs) {
        _tripPrefs.value = p
        prefs.edit().putString("routeStyle", p.style.name).putInt("minTransfer", p.minTransferMin).putInt("maxChanges", p.maxChanges).apply()
    }

    fun setThemeMode(m: ThemeMode) {
        _themeMode.value = m
        prefs.edit().putString("theme", m.name).apply()
    }

    fun setTripBar(on: Boolean) {
        _tripBar.value = on
        prefs.edit().putBoolean("tripBar", on).apply()
    }

    fun setLeadMinutes(m: Int) {
        _leadMinutes.value = m
        prefs.edit().putInt("leadMinutes", m).apply()
    }

    private fun readTrips(): List<SavedTrip> {
        val all = prefs.getString("trips", null)?.let {
            runCatching { SrsApi.json.decodeFromString(tripSerializer, it) }.getOrNull()
        } ?: emptyList()
        val cutoff = java.time.ZonedDateTime.now().minusHours(2)
        return all.filter { parseTime(it.route.arrivalDate)?.isAfter(cutoff) != false }.sortedBy { parseTime(it.route.departureDate) }
    }

    private fun writeTrips(list: List<SavedTrip>) {
        prefs.edit().putString("trips", SrsApi.json.encodeToString(tripSerializer, list)).apply()
    }

    fun isTripSaved(r: Route) = _savedTrips.value.any { it.id == r.key }

    fun toggleTrip(r: Route, from: String, to: String) {
        val cur = _savedTrips.value
        val next = if (cur.any { it.id == r.key }) cur.filterNot { it.id == r.key }
        else (cur + SavedTrip(r.key, r.slim(), from, to)).sortedBy { parseTime(it.route.departureDate) }
        _savedTrips.value = next
        writeTrips(next)
    }

    fun updateTripRoute(id: String, route: Route) {
        val next = _savedTrips.value.map { if (it.id == id) it.copy(route = route.slim()) else it }
        _savedTrips.value = next
        writeTrips(next)
    }

    fun removeTrip(id: String) {
        val next = _savedTrips.value.filterNot { it.id == id }
        _savedTrips.value = next
        writeTrips(next)
    }

    private fun read(key: String): List<Station> =
        prefs.getString(key, null)?.let {
            runCatching { SrsApi.json.decodeFromString(serializer, it) }.getOrNull()
        } ?: emptyList()

    private fun write(key: String, list: List<Station>) {
        prefs.edit().putString(key, SrsApi.json.encodeToString(serializer, list)).apply()
    }

    fun isFavorite(s: Station) = _favorites.value.any { it.key == s.key }

    fun toggleFavorite(s: Station) {
        val cur = _favorites.value
        val next = if (cur.any { it.key == s.key }) cur.filterNot { it.key == s.key } else cur + s.copy(date = null, expectedDate = null)
        _favorites.value = next
        write("favorites", next)
    }

    fun addRecent(s: Station) {
        if (s.type == "coord") return
        val next = (listOf(s.copy(date = null, expectedDate = null)) + _recents.value.filterNot { it.key == s.key }).take(8)
        _recents.value = next
        write("recents", next)
    }

    fun saveTrip(from: Station?, to: Station?) {
        prefs.edit().putString("trip", SrsApi.json.encodeToString(serializer, listOfNotNull(from, to).take(2)))
            .putBoolean("tripHasFrom", from != null).apply()
    }

    fun loadTrip(): Pair<Station?, Station?> {
        val list = read("trip")
        val hasFrom = prefs.getBoolean("tripHasFrom", true)
        return if (hasFrom) list.getOrNull(0) to list.getOrNull(1) else null to list.getOrNull(0)
    }

    fun clearRecents() {
        _recents.value = emptyList()
        write("recents", emptyList())
    }
}
