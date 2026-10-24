package dev.padermodern.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.padermodern.companion.CompanionState
import dev.padermodern.companion.TripCompanion
import dev.padermodern.data.LiveTrip
import dev.padermodern.data.PartialRoute
import dev.padermodern.data.Phase
import dev.padermodern.data.Route

private class Problem(val text: String, val action: String? = null, val intent: Intent? = null)

private enum class Level { Ok, Wait, Bad }

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

private fun Context.open(intent: Intent) = runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

@Composable
fun CompanionDialog(tripId: String, route: Route, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by TripCompanion.state.collectAsState()
    val active = state.running && state.tripId == tripId
    var problem by remember { mutableStateOf<Problem?>(null) }
    var testAsked by remember { mutableStateOf(false) }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val status = LiveTrip.status(route)
    val leg = route.partialRoutes.getOrNull(status.legIndex)
    val canStart = !status.broken && status.phase in listOf(Phase.Upcoming, Phase.Riding, Phase.Transfer) && leg?.isWalk == false
    val blocked = when {
        canStart -> null
        status.broken -> "Diese Verbindung ist nicht mehr fahrbar."
        status.phase == Phase.Arrived -> "Die Fahrt ist beendet."
        else -> "Gerade ist ein Fußweg dran. Starte im nächsten Fahrzeug."
    }

    fun start() {
        runCatching { TripCompanion.start(context, tripId, route, status.legIndex) }
            .onFailure { problem = Problem(it.message ?: "Begleitung konnte nicht gestartet werden.") }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val locationOn = context.getSystemService(LocationManager::class.java)?.isLocationEnabled != false
        when {
            !granted(context, Manifest.permission.ACCESS_FINE_LOCATION) -> problem = Problem(
                if (granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)) "Du hast nur den ungefähren Standort erlaubt. Für Haltestellenansagen braucht die App den genauen Standort."
                else "Ohne Standort erkennt die App nicht, wo du bist. Erlaube den genauen Standort in den App-Einstellungen.",
                "App-Einstellungen", context.appSettings(),
            )
            !locationOn -> problem = Problem("Die Ortung ist ausgeschaltet. Schalte sie ein und starte dann erneut.", "Ortung", Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            else -> start()
        }
    }

    val bluetoothPermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
    val bluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (bluetoothPermissions.all { granted(context, it) }) TripCompanion.bluetooth(context, true)
        else problem = Problem(
            "Bluetooth-Hilfe braucht „Geräte in der Nähe“ und den genauen Standort. Die Ansagen laufen auch ohne.",
            "App-Einstellungen", context.appSettings(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("companion_controls"),
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Fahrtbegleitung") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LegLabel(if (active) route.partialRoutes.getOrNull(state.legIndex) else leg)
                if (active) {
                    ActiveContent(
                        state, notificationsOn, context, testAsked,
                        onTest = { testAsked = true; TripCompanion.command(context, TripCompanion.TEST) },
                        onBluetooth = { on ->
                            problem = null
                            if (!on || bluetoothPermissions.all { granted(context, it) }) TripCompanion.bluetooth(context, on)
                            else bluetooth.launch(bluetoothPermissions)
                        },
                    )
                } else {
                    Text(
                        "Sagt dir nächste Halte an und erinnert ans Aussteigen. Nur über Kopfhörer, dein Standort bleibt auf dem Gerät.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    blocked?.let { Note(Icons.Rounded.Info, it) }
                    if (state.running) Note(Icons.Rounded.Info, "Für eine andere Fahrt läuft schon eine Begleitung. Sie wird beim Start beendet.")
                }
                problem?.let { p ->
                    Note(Icons.Rounded.ErrorOutline, p.text, error = true, action = p.action?.let { label -> label to { p.intent?.let { context.open(it) }; Unit } })
                }
            }
        },
        confirmButton = {
            if (active) TextButton(
                { TripCompanion.command(context, TripCompanion.STOP) },
                Modifier.heightIn(min = 48.dp).testTag("companion_stop"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Beenden") }
            else Button(
                onClick = {
                    problem = null
                    permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                },
                enabled = canStart,
                modifier = Modifier.heightIn(min = 48.dp).testTag("companion_start"),
            ) { Text("Starten") }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Schließen") } },
    )
}

@Composable
private fun LegLabel(leg: PartialRoute?) {
    leg ?: return
    val name = leg.shortName ?: return
    val target = leg.stations.lastOrNull()?.name
    if (target == null) Text(name, style = MaterialTheme.typography.titleSmall)
    else RouteText(name, target, style = MaterialTheme.typography.titleSmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveContent(
    state: CompanionState,
    notificationsOn: Boolean,
    context: Context,
    testAsked: Boolean,
    onTest: () -> Unit,
    onBluetooth: (Boolean) -> Unit,
) {
    val alight = state.alighting
    val gps = when (state.locationReady) {
        null -> Level.Wait to "Standort wird gesucht"
        false -> Level.Bad to "Standort ungenau"
        true -> Level.Ok to "Standort genau"
    }
    val voiceMissing = !state.speechReady && state.speechStatus.contains("fehlt")
    val voice = when {
        state.speechReady -> Level.Ok to "Stimme bereit"
        voiceMissing -> Level.Bad to "Stimme fehlt"
        else -> Level.Wait to "Stimme lädt"
    }
    val hero by animateColorAsState(
        if (alight) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer, label = "companionHero",
    )
    Surface(shape = MaterialTheme.shapes.medium, color = hero,
        contentColor = if (alight) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (alight) Icons.Rounded.NotificationsActive else Icons.Rounded.Navigation, null, Modifier.size(24.dp))
            Text(state.message, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
    run {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusLine(if (state.headphones) Icons.Rounded.Headphones else Icons.Rounded.HeadsetOff,
                if (state.headphones) "Kopfhörer verbunden" else "Keine Kopfhörer", if (state.headphones) Level.Ok else Level.Bad)
            StatusLine(when (gps.first) { Level.Ok -> Icons.Rounded.GpsFixed; Level.Wait -> Icons.Rounded.GpsNotFixed; Level.Bad -> Icons.Rounded.GpsOff }, gps.second, gps.first)
            StatusLine(if (voiceMissing) Icons.Rounded.VoiceOverOff else Icons.Rounded.RecordVoiceOver, voice.second, voice.first)
        }
    }
    if (!state.headphones) Note(Icons.Rounded.HeadsetOff, "Ansagen sind pausiert, bis Kopfhörer als Audioausgabe erkannt werden.")
    if (voiceMissing) Note(Icons.Rounded.VoiceOverOff, state.speechStatus, error = true, action = "Spracheinstellungen" to {
        context.open(Intent("com.android.settings.TTS_SETTINGS")) || context.open(Intent(Settings.ACTION_SETTINGS))
    })
    else if (!state.speechStatus.startsWith("Bereit") && !state.speechStatus.contains("vorbereitet"))
        Note(if (state.speechStatus.contains("fehlgeschlagen")) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, state.speechStatus, error = state.speechStatus.contains("fehlgeschlagen"))
    // only the speech engine knows if it played
    else if (testAsked && state.headphones && state.speechReady)
        Note(Icons.Rounded.Info, "Testansage an die Sprachausgabe übergeben. Hörst du nichts, prüfe Lautstärke und Ausgabegerät.")
    if (!notificationsOn) Note(Icons.Rounded.NotificationsOff,
        "Benachrichtigungen sind aus. Wiederholen und Beenden gibt es nur hier in der App, nicht auf dem Sperrbildschirm.",
        action = "Benachrichtigungen" to { context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onTest, Modifier.heightIn(min = 48.dp).testTag("companion_test"), enabled = state.headphones && state.speechReady,
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Testansage", maxLines = 1)
        }
        TextButton({ TripCompanion.command(context, TripCompanion.REPEAT) }, Modifier.heightIn(min = 48.dp).testTag("companion_repeat"),
            enabled = state.headphones && state.speechReady && state.lastAnnouncement != null,
            contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Wiederholen", maxLines = 1)
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.busEnabled, role = Role.Switch, onValueChange = onBluetooth).testTag("companion_bluetooth"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            when { state.busMatched -> Icons.Rounded.BluetoothConnected; state.busScanning -> Icons.Rounded.BluetoothSearching; else -> Icons.Rounded.Bluetooth },
            null, Modifier.size(20.dp), tint = if (state.busMatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text("Bluetooth im Bus", style = MaterialTheme.typography.bodyMedium)
            Text(state.busStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(state.busEnabled, null)
    }
}

@Composable
private fun StatusLine(icon: ImageVector, label: String, level: Level) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (level) { Level.Ok -> scheme.primary; Level.Wait -> scheme.onSurfaceVariant; Level.Bad -> scheme.error }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

@Composable
private fun Note(icon: ImageVector, text: String, error: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = if (error) scheme.errorContainer else scheme.surfaceContainerHighest,
        contentColor = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (action == null) 12.dp else 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, Modifier.size(20.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            action?.let { (label, onClick) -> TextButton(onClick, Modifier.heightIn(min = 48.dp).align(Alignment.End)) { Text(label) } }
        }
    }
}
