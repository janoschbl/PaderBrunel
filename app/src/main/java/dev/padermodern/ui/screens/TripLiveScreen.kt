package dev.padermodern.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.clickable
import dev.padermodern.data.Route
import dev.padermodern.data.hhmm
import dev.padermodern.data.delayMinutes
import dev.padermodern.ui.Loadable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.padermodern.companion.TripCompanion
import dev.padermodern.data.LiveTrip
import dev.padermodern.data.Phase
import dev.padermodern.data.Station
import dev.padermodern.data.TripStatus
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.StatusBarScrim
import dev.padermodern.ui.components.TripTimeline
import dev.padermodern.ui.components.CompanionDialog
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.RouteText
import dev.padermodern.ui.components.mode
import dev.padermodern.ui.theme.LocalTransitColors
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

@Composable
fun TripLiveScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onStation: (Station) -> Unit,
    onAlternatives: () -> Unit,
) {
    val saved by vm.store.savedTrips.collectAsState()
    val trip = saved.firstOrNull { it.id == vm.liveTripId }
    if (trip == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(trip.id) {
        while (true) {
            vm.refreshSaved(trip)
            delay(30_000)
        }
    }
    val now = rememberTime()
    val route = vm.routeFor(trip)
    val status = LiveTrip.status(route, now)
    val companion by TripCompanion.state.collectAsState()
    var showCompanion by remember { mutableStateOf(false) }
    var showSwap by remember { mutableStateOf(false) }
    val swappable = LiveTrip.swappableLegs(route, now)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("live_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                PageHeader(
                    "Meine Fahrt",
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { showCompanion = true }, Modifier.testTag("companion_open")) {
                            Icon(
                                Icons.Rounded.Headphones, "Fahrtbegleitung",
                                tint = if (companion.running && companion.tripId == trip.id) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        IconButton(
                            onClick = { if (swappable.size == 1) vm.findTailAlternatives(trip, swappable.first()) else showSwap = true },
                            enabled = swappable.isNotEmpty(),
                            modifier = Modifier.testTag("swap_open"),
                        ) { Icon(Icons.Rounded.AltRoute, "Teil der Fahrt austauschen") }
                        IconButton(onClick = { vm.refreshSaved(trip) }) { Icon(Icons.Rounded.Refresh, "Aktualisieren") }
                    },
                    subtitle = { RouteText(trip.fromName, trip.toName) },
                )
            }
            item {
                val replaceFrom = LiveTrip.replaceableFrom(route, status)
                StatusCard(status, onAlternatives, onReplace = replaceFrom?.let { { vm.findTailAlternatives(trip, it) } })
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { TripTimeline(route, onStation, status = status) }
            item {
                OutlinedButton(
                    onClick = { vm.store.removeTrip(trip.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Icon(Icons.Rounded.StarBorder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Fahrt nicht mehr merken")
                }
            }
        }
        StatusBarScrim()
    }
    vm.tailAlternatives?.let { state ->
        TailAlternativesSheet(
            state,
            current = route,
            onPick = { vm.applyTailAlternative(trip, it) },
            onDismiss = vm::closeTailAlternatives,
        )
    }
    if (showSwap) {
        SwapLegSheet(
            route, swappable,
            onPick = { showSwap = false; vm.findTailAlternatives(trip, it) },
            onDismiss = { showSwap = false },
        )
    }
    if (showCompanion) CompanionDialog(trip.id, route) { showCompanion = false }
}

@Composable
private fun rememberTime(): ZonedDateTime {
    val tick = rememberNow()
    return androidx.compose.runtime.remember(tick) { ZonedDateTime.now() }
}

@Composable
private fun StatusCard(status: TripStatus, onAlternatives: () -> Unit, onReplace: (() -> Unit)?) {
    val tc = LocalTransitColors.current
    val container by animateColorAsState(
        if (status.broken) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        label = "statusColor",
    )
    val onContainer = if (status.broken) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = container,
        contentColor = onContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("live_status"),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (status.broken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Verbindung nicht mehr fahrbar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(status.brokenText.orEmpty() + ". Bitte suche eine Alternative.", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(14.dp))
                if (onReplace != null) {
                    Button(onClick = onReplace, modifier = Modifier.testTag("live_replace")) { Text("Anderen Anschluss wählen") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onAlternatives, modifier = Modifier.testTag("live_alternatives")) { Text("Ganz neue Verbindung suchen") }
                } else {
                    Button(onClick = onAlternatives, modifier = Modifier.testTag("live_alternatives")) { Text("Alternativen suchen") }
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (status.phase) {
                        Phase.Upcoming -> Icons.Rounded.Schedule
                        Phase.Riding -> Icons.Rounded.Train
                        Phase.Transfer -> Icons.Rounded.SwapHoriz
                        Phase.Walking -> Icons.Rounded.DirectionsWalk
                        Phase.Arrived -> Icons.Rounded.CheckCircle
                    },
                    null, Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(status.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    status.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (onReplace != null && !status.broken) {
                TextButton(onClick = onReplace, modifier = Modifier.testTag("live_replace")) { Text("Anschluss ersetzen") }
            }
        }
    }
}

@Composable
private fun SwapLegSheet(route: Route, legs: List<Int>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("swap_sheet")) {
            Text("Was möchtest du tauschen?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Du suchst dir eine andere Verbindung ab diesem Abschnitt aus. Der Anfang der Fahrt bleibt.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            legs.forEach { i ->
                val leg = route.partialRoutes[i]
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPick(i) }.testTag("swap_leg"),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(leg.shortName, leg.mode())
                        Spacer(Modifier.width(12.dp))
                        Column {
                            val late = delayMinutes(leg.departureDate, leg.expectedDepartureDate)?.takeIf { it > 0 }?.let { " (+$it)" }.orEmpty()
                            Text(
                                "${leg.departureDate.hhmm()}$late ab ${leg.stations.firstOrNull()?.name.orEmpty()}",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            )
                            leg.lineDirection?.let {
                                Text("nach $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TailAlternativesSheet(
    state: Loadable<List<Route>>,
    current: Route,
    onPick: (Route) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentRides = current.rides.map { "${it.shortName}@${it.departureDate}" }.toSet()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("tail_sheet")) {
            Text("Anderer Anschluss", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Passend zur aktuellen Verspätung. Du tauschst nur den Rest der Fahrt, der Anfang bleibt.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            val list = state.data.orEmpty()
            list.forEach { alt ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPick(alt) }.testTag("tail_option"),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        val late = delayMinutes(alt.departureDate, alt.expectedDepartureDate)?.takeIf { it > 0 }?.let { " (+$it)" }.orEmpty()
                        val isCurrent = alt.rides.firstOrNull()?.let { "${it.shortName}@${it.departureDate}" } in currentRides
                        Text(
                            "${alt.departureDate.hhmm()}$late → ${alt.realArrival.hhmm()}" + if (isCurrent) " · aktuell" else "",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            alt.rides.joinToString(" → ") { it.shortName.orEmpty() } +
                                alt.rides.firstOrNull()?.let { r -> r.stations.firstOrNull()?.platform?.let { " · $it" } }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        alt.rides.firstOrNull()?.lineDirection?.let {
                            Text("nach $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            when {
                state.loading -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Suche läuft …", style = MaterialTheme.typography.bodyMedium)
                }
                state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                list.isEmpty() -> Text("Keine passende Verbindung gefunden.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
