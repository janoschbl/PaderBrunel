package dev.padermodern.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.padermodern.MainActivity
import dev.padermodern.R
import dev.padermodern.data.JourneyFix
import dev.padermodern.data.JourneyProgress
import dev.padermodern.data.LiveTrip
import dev.padermodern.data.Route
import dev.padermodern.data.SrsApi
import dev.padermodern.data.BusNames
import dev.padermodern.data.BusRecord
import dev.padermodern.data.BusMatchTracker
import dev.padermodern.data.JourneyAnnouncement
import dev.padermodern.data.match
import kotlinx.coroutines.*

class TripCompanionService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val locations by lazy { getSystemService(LocationManager::class.java) }
    private lateinit var speech: JourneySpeech
    private var route: Route? = null
    private var progress: JourneyProgress? = null
    private var latestFix: Location? = null
    private var session: Job? = null
    private var ending: Job? = null
    private var started = 0L
    private var lastNotice: String? = null
    private lateinit var buses: PassiveBusScanner
    private var busLoad: Job? = null
    private var busNames: BusNames? = null
    private var busMatch = BusMatchTracker()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Fahrtbegleitung", NotificationManager.IMPORTANCE_LOW),
        )
        speech = JourneySpeech(this) { headphones, ready, status ->
            TripCompanion.mutableState.value = TripCompanion.state.value.copy(headphones = headphones, speechReady = ready, speechStatus = status)
            if (TripCompanion.state.value.running) updateNotification()
        }
        buses = PassiveBusScanner(this, ::onBus) { scanning, status ->
            if (!scanning) busMatch = BusMatchTracker()
            TripCompanion.mutableState.value = TripCompanion.state.value.copy(busScanning = scanning,
                busMatched = false, busStatus = status)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TripCompanion.START -> startSession()
            TripCompanion.STOP -> stopSelf()
            TripCompanion.TEST -> speech.speak("Die Fahrtbegleitung ist bereit. Nächster Halt: Detmolder Tor, Steig eins. Das war eine Testansage.")
            TripCompanion.REPEAT -> TripCompanion.state.value.lastAnnouncement?.let { speech.speak(it) }
            // android 14+ allows swiping the notification away, put it back
            TripCompanion.RESHOW -> if (TripCompanion.state.value.running) { lastNotice = null; updateNotification() }
            TripCompanion.BLUETOOTH -> bluetooth(intent.getBooleanExtra("enabled", false))
        }
        // never resume an old itinerary after a process kill
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        val prefs = getSharedPreferences("trip_companion", MODE_PRIVATE)
        val snapshot = prefs.getString("route", null)?.let {
            runCatching { SrsApi.json.decodeFromString(Route.serializer(), it) }.getOrNull()
        }
        val legIndex = prefs.getInt("legIndex", 0)
        val leg = snapshot?.partialRoutes?.getOrNull(legIndex)
        if (leg == null || leg.isWalk || leg.stations.size < 2) { stopSelf(); return }
        ending?.cancel()
        session?.cancel()
        speech.cancel()
        bluetooth(false)
        locations.removeUpdates(this)
        latestFix = null
        route = snapshot
        progress = JourneyProgress(leg.stations)
        started = SystemClock.elapsedRealtime()
        TripCompanion.mutableState.value = TripCompanion.state.value.copy(
            tripId = prefs.getString("tripId", null), legIndex = legIndex, running = true,
            locationReady = null, alighting = false,
            message = "Warte auf eine Haltestelle deiner Fahrt · Standort wird geprüft", lastAnnouncement = null,
        )
        try {
            ServiceCompat.startForeground(this, NOTICE, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { locations.isProviderEnabled(it) }
            providers.forEach { locations.requestLocationUpdates(it, 3_000, 0f, this) }
            session = scope.launch {
                launch {
                    while (isActive) {
                        val current = route ?: break
                        try {
                            route = LiveTrip.refresh(current)
                            route?.partialRoutes?.getOrNull(legIndex)?.let { progress?.refreshStops(it.stations) }
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { Log.w(TAG, "Live refresh unavailable", e) }
                        delay(45_000)
                    }
                }
                while (isActive) {
                    speech.refreshRoute()
                    tick()
                    if (SystemClock.elapsedRealtime() - started > 6 * 60 * 60_000L) { stopSelf(); break }
                    delay(3_000)
                }
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Cannot start journey tracking", e)
            TripCompanion.mutableState.value = TripCompanion.state.value.copy(message = "Standortbegleitung konnte nicht gestartet werden. Bitte Berechtigungen prüfen.")
            stopSelf()
        }
    }

    override fun onLocationChanged(location: Location) {
        // never replace a fresh gps fix with an older network fix
        if (latestFix == null || location.elapsedRealtimeNanos > latestFix!!.elapsedRealtimeNanos) latestFix = location
        tick()
    }

    private fun bluetooth(enabled: Boolean) {
        busLoad?.cancel()
        buses.stop()
        busMatch = BusMatchTracker()
        TripCompanion.mutableState.value = TripCompanion.state.value.copy(busEnabled = enabled, busScanning = false,
            busMatched = false, busStatus = if (enabled) "Lade Linien- und Haltestellennamen …" else "Bus-Bluetooth optional")
        if (!enabled || !TripCompanion.state.value.running) return
        busLoad = scope.launch {
            try {
                busNames = BusNameRepository.load(this@TripCompanionService)
                ensureActive()
                buses.start()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                TripCompanion.mutableState.value = TripCompanion.state.value.copy(busStatus = "Bluetooth-Tabelle nicht verfügbar · GPS bleibt aktiv. Aus- und wieder einschalten.")
                Log.w(TAG, "Bluetooth mapping unavailable", e)
            }
        }
    }

    private fun onBus(key: String, bus: BusRecord, timestamp: Long) {
        val state = TripCompanion.state.value
        val engine = progress ?: return
        val leg = route?.partialRoutes?.getOrNull(state.legIndex) ?: return
        if (!state.running || !state.busEnabled || engine.arrived || leg.stations.any { it.canceled }) return
        if (SystemClock.elapsedRealtime() - timestamp !in 0..10_000) return
        val index = busNames?.match(bus, leg) ?: return
        if (engine.nextStopIndex?.let { index < it } == true) return
        val gpsNext = engine.nextStopIndex.takeIf { engine.hasGoodFix && latestFix?.let {
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 in 0..30_000
        } == true }
        val changed = busMatch.observe(key, index, timestamp, gpsNext)
        updateBusStatus()
        if (changed != null && busMatch.matched) {
            val announcement = engine.confirmBusNextStop(changed)
            TripCompanion.mutableState.value = TripCompanion.state.value.copy(
                message = "Nächster Halt: ${leg.stations.getOrNull(changed)?.name.orEmpty()} · Bus-Bluetooth" +
                    if (changed == leg.stations.lastIndex) " · hier steigst du aus" else "",
                alighting = changed == leg.stations.lastIndex,
            )
            announcement?.let(::announce)
        }
    }

    private fun updateBusStatus() {
        val state = TripCompanion.state.value
        busMatch.expire(SystemClock.elapsedRealtime())
        if (!state.busScanning) return
        val leg = route?.partialRoutes?.getOrNull(state.legIndex)
        val message = when {
            busMatch.ambiguous -> "Mehrere passende Busse · GPS bleibt aktiv"
            busMatch.matched -> "Bus bestätigt · ${leg?.stations?.getOrNull(busMatch.nextIndex ?: -1)?.name.orEmpty()}"
            state.busMatched -> "Bus nicht mehr empfangen · GPS bleibt aktiv"
            else -> state.busStatus
        }
        TripCompanion.mutableState.value = state.copy(busMatched = busMatch.matched, busStatus = message)
    }

    private fun announce(announcement: JourneyAnnouncement) {
        TripCompanion.mutableState.value = TripCompanion.state.value.copy(lastAnnouncement = announcement.text)
        val played = speech.speak(announcement.text)
        Log.i(TAG, "Announcement ${announcement.id}: ${if (played) "queued" else "headphones/audio unavailable"}")
    }

    private fun tick() {
        buses.refresh()
        updateBusStatus()
        val engine = progress ?: return
        val state = TripCompanion.state.value
        if (!state.running || engine.arrived) return
        val leg = route?.partialRoutes?.getOrNull(state.legIndex) ?: return
        if (leg.stations.any { it.canceled }) {
            speech.cancel()
            TripCompanion.mutableState.value = state.copy(message = "Fahrt oder Halt fällt aus · bitte Verbindung prüfen", locationReady = false, alighting = false)
            updateNotification()
            return
        }
        val fix = latestFix
        val announcement = fix?.let {
            engine.update(JourneyFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else Float.POSITIVE_INFINITY,
                (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000,
                it.elapsedRealtimeNanos / 1_000_000, if (it.hasSpeed()) it.speed else null), System.currentTimeMillis())
        }
        val message = when {
            engine.arrived -> "Ausstiegshaltestelle erreicht · Begleitung endet"
            (fix == null || !engine.hasGoodFix) && state.busMatched -> "Nächster Halt: ${leg.stations.getOrNull(engine.nextStopIndex ?: -1)?.name.orEmpty()} · Bus-Bluetooth, GPS ungenau" +
                if (engine.nextStopIndex == leg.stations.lastIndex) " · hier steigst du aus" else ""
            fix == null || !engine.hasGoodFix -> "Kein genauer Standort · keine verlässliche Haltestellenansage"
            engine.nextStopIndex == null -> "Warte auf eine Haltestelle deiner Fahrt · bitte im Fahrzeug starten"
            else -> "Nächster Halt: ${leg.stations.getOrNull(engine.nextStopIndex!!)?.name.orEmpty()}" +
                if (engine.nextStopIndex == leg.stations.lastIndex) " · hier steigst du aus" else ""
        }
        TripCompanion.mutableState.value = TripCompanion.state.value.copy(
            message = message, lastAnnouncement = announcement?.text ?: state.lastAnnouncement,
            locationReady = fix != null && engine.hasGoodFix,
            alighting = engine.arrived || engine.nextStopIndex == leg.stations.lastIndex,
        )
        announcement?.let(::announce)
        updateNotification()
        if (engine.arrived) {
            ending = scope.launch { delay(15_000); stopSelf() }
        }
    }

    private fun action(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, TripCompanionService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val state = TripCompanion.state.value
        val content = if (!state.headphones) "Kopfhörer verbinden · Ansagen pausiert" else state.message
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fahrtbegleitung").setContentText(content).setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(open).setDeleteIntent(action(TripCompanion.RESHOW, 3)).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .addAction(0, "Wiederholen", action(TripCompanion.REPEAT, 1))
            .addAction(0, "Beenden", action(TripCompanion.STOP, 2)).build()
    }

    private fun updateNotification() {
        val state = TripCompanion.state.value
        val key = "${state.headphones}|${state.message}"
        if (key == lastNotice) return
        lastNotice = key
        getSystemService(NotificationManager::class.java).notify(NOTICE, notification())
    }

    override fun onDestroy() {
        session?.cancel()
        busLoad?.cancel()
        buses.stop()
        scope.cancel()
        locations.removeUpdates(this)
        speech.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSharedPreferences("trip_companion", MODE_PRIVATE).edit().clear().apply()
        TripCompanion.mutableState.value = TripCompanion.state.value.copy(running = false, tripId = null,
            busEnabled = false, busScanning = false, busMatched = false, busStatus = "Bus-Bluetooth optional")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "trip_companion"
        private const val NOTICE = 41
        private const val TAG = "TripCompanion"
    }
}
