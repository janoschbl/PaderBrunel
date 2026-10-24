package dev.padermodern.companion

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.padermodern.data.Route
import dev.padermodern.data.SrsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CompanionState(
    val tripId: String? = null,
    val legIndex: Int = 0,
    val running: Boolean = false,
    val headphones: Boolean = false,
    val speechReady: Boolean = false,
    val locationReady: Boolean? = null,
    val alighting: Boolean = false,
    val busEnabled: Boolean = false,
    val busScanning: Boolean = false,
    val busMatched: Boolean = false,
    val busStatus: String = "Bus-Bluetooth optional",
    val message: String = "Haltestellenansagen über deine Kopfhörer",
    val speechStatus: String = "Sprachausgabe wird vorbereitet",
    val lastAnnouncement: String? = null,
)

object TripCompanion {
    internal val mutableState = MutableStateFlow(CompanionState())
    val state = mutableState.asStateFlow()
    const val START = "dev.padermodern.companion.START"
    const val STOP = "dev.padermodern.companion.STOP"
    const val TEST = "dev.padermodern.companion.TEST"
    const val REPEAT = "dev.padermodern.companion.REPEAT"
    const val RESHOW = "dev.padermodern.companion.RESHOW"
    const val BLUETOOTH = "dev.padermodern.companion.BLUETOOTH"

    fun bluetooth(context: Context, enabled: Boolean) {
        if (state.value.running) context.startService(Intent(context, TripCompanionService::class.java)
            .setAction(BLUETOOTH).putExtra("enabled", enabled))
    }

    fun start(context: Context, tripId: String, route: Route, legIndex: Int) {
        // keep route payloads out of binder transactions
        val snapshot = route.copy(partialRoutes = route.partialRoutes.map { it.copy(encodedPath = null) })
        check(context.getSharedPreferences("trip_companion", Context.MODE_PRIVATE).edit()
            .putString("route", SrsApi.json.encodeToString(Route.serializer(), snapshot))
            .putString("tripId", tripId).putInt("legIndex", legIndex).commit()) { "Fahrt konnte nicht gespeichert werden" }
        ContextCompat.startForegroundService(context, Intent(context, TripCompanionService::class.java).setAction(START))
    }

    fun command(context: Context, action: String) {
        if (state.value.running) context.startService(Intent(context, TripCompanionService::class.java).setAction(action))
    }
}
