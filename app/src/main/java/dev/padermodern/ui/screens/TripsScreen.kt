package dev.padermodern.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TripOrigin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.padermodern.data.Route
import dev.padermodern.data.TripSearch
import dev.padermodern.data.SavedTrip
import dev.padermodern.data.Station
import dev.padermodern.data.delayMinutes
import dev.padermodern.data.durationLabel
import dev.padermodern.data.euro
import dev.padermodern.data.hhmm
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.ErrorState
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.LoadingState
import dev.padermodern.ui.components.MessageState
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.RouteText
import dev.padermodern.ui.components.StatusBarScrim
import dev.padermodern.ui.components.mode
import dev.padermodern.ui.theme.LocalTransitColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PickTarget { From, To }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TripsScreen(vm: AppViewModel, onPick: (PickTarget) -> Unit, onRoute: (Route) -> Unit, onSaved: (SavedTrip) -> Unit) {
    val state = vm.trips
    val saved by vm.store.savedTrips.collectAsState()
    var showTime by remember { mutableStateOf(false) }
    val expanded = vm.tripFormExpanded || (state.data == null && !state.loading)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("trips_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PageHeader("Verbindungen", "Plane deine Fahrt") }
            item {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    AnimatedContent(
                        targetState = expanded,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label = "tripForm",
                    ) { open ->
                        if (open) {
                            Column(Modifier.padding(12.dp)) {
                                Box {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PlaceField(Icons.Rounded.TripOrigin, "Start", vm.from, Modifier.testTag("from_field")) { onPick(PickTarget.From) }
                                        PlaceField(Icons.Rounded.Place, "Ziel", vm.to, Modifier.testTag("to_field")) { onPick(PickTarget.To) }
                                    }
                                    FilledIconButton(
                                        onClick = { vm.swap() },
                                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(44.dp).testTag("swap"),
                                        shapes = IconButtonDefaults.shapes(),
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        ),
                                    ) { Icon(Icons.Rounded.SwapVert, "Start und Ziel tauschen") }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                        ToggleButton(
                                            checked = !vm.arrival,
                                            onCheckedChange = { vm.arrival = false },
                                            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                        ) { Text("Abfahrt") }
                                        ToggleButton(
                                            checked = vm.arrival,
                                            onCheckedChange = { vm.arrival = true },
                                            shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                        ) { Text("Ankunft") }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f).testTag("time_button")) {
                                        Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(timeLabel(vm.tripTime), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = { vm.searchTrips() },
                                    enabled = vm.from != null && vm.to != null && !state.loading,
                                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("search_trips"),
                                    shapes = androidx.compose.material3.ButtonDefaults.shapes(),
                                ) { Text("Verbindungen suchen", style = MaterialTheme.typography.titleMedium) }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.tripFormExpanded = true }.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                                    .testTag("trip_summary"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    RouteText(
                                        vm.from?.displayName.orEmpty(), vm.to?.displayName.orEmpty(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        (if (vm.arrival) "Ankunft " else "Abfahrt ") + timeLabel(vm.tripTime),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                androidx.compose.material3.IconButton(onClick = { vm.tripFormExpanded = true }) { Icon(Icons.Rounded.Tune, "Suche ändern") }
                            }
                        }
                    }
                }
            }

            when {
                state.loading && state.data == null -> item { LoadingState(Modifier.padding(vertical = 24.dp), label = "Suche Verbindungen …") }
                state.error != null && state.data == null -> item { ErrorState(state.error) { vm.searchTrips() } }
                state.data == null -> {
                    if (saved.isEmpty()) item {
                        MessageState(Icons.Rounded.Route, "Wohin soll's gehen?", "Wähle Start und Ziel. Haltestellen, Adressen und Orte funktionieren.")
                    } else {
                        item { SectionTitle("Gespeicherte Fahrten") }
                        items(saved, key = { it.id }) { t -> SavedTripCard(vm, t, Modifier.animateItem()) { onSaved(t) } }
                    }
                }
                state.data.isEmpty() && vm.searchingMore -> item { LoadingState(Modifier.padding(vertical = 24.dp), label = "Suche weitere Verbindungen …") }
                state.data.isEmpty() -> item {
                    MessageState(Icons.Rounded.Route, "Keine Verbindung gefunden", "Versuche eine andere Uhrzeit, einen Ort in der Nähe oder lockere in den Einstellungen Umstiegszeit und Umstiege.")
                }
                else -> {
                    item { PagerButton("Früher", Icons.Rounded.ExpandLess, vm.pagingEarlier) { vm.pageTrips(false) } }
                    val fastest = TripSearch.fastest(state.data)
                    items(state.data, key = { it.key }) { r ->
                        RouteCard(r, vm.store.isTripSaved(r), r.key in fastest, Modifier.animateItem()) { onRoute(r) }
                    }
                    if (vm.searchingMore) item(key = "more") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                            Text("Suche weitere Verbindungen …", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                    item { PagerButton("Später", Icons.Rounded.ExpandMore, vm.pagingLater) { vm.pageTrips(true) } }
                }
            }
        }
        StatusBarScrim()
    }

    if (showTime) TimeDialog(vm.tripTime, onDismiss = { showTime = false }) {
        vm.tripTime = it
        showTime = false
        if (vm.from != null && vm.to != null) vm.searchTrips()
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun SavedTripCard(vm: AppViewModel, t: SavedTrip, modifier: Modifier, onClick: () -> Unit) {
    val r = vm.routeFor(t)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RouteText(t.fromName, t.toName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${dayLabel(r.departureDate)} · ${r.departureDate.hhmm()} – ${r.arrivalDate.hhmm()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun dayLabel(iso: String?): String {
    val d = dev.padermodern.data.parseTime(iso)?.toLocalDate() ?: return ""
    return when (d) {
        LocalDate.now() -> "Heute"
        LocalDate.now().plusDays(1) -> "Morgen"
        else -> d.format(DateTimeFormatter.ofPattern("EE, d. MMM", Locale.GERMAN))
    }
}

private fun timeLabel(t: ZonedDateTime?): String {
    if (t == null) return "Jetzt"
    val day = when (t.toLocalDate()) {
        LocalDate.now() -> "Heute"
        LocalDate.now().plusDays(1) -> "Morgen"
        else -> t.format(DateTimeFormatter.ofPattern("EE, d. MMM", Locale.GERMAN))
    }
    return "$day, ${t.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

@Composable
private fun PlaceField(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: Station?, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    value?.displayName ?: "$label wählen",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PagerButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (loading) LoadingIndicator(Modifier.size(40.dp))
        else TextButton(onClick = onClick) {
            Icon(icon, null)
            Spacer(Modifier.width(4.dp))
            Text(label)
        }
    }
}

@Composable
fun RouteCard(r: Route, saved: Boolean, fastest: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tc = LocalTransitColors.current
    val firstRide = r.rides.firstOrNull()
    val lastRide = r.rides.lastOrNull()
    val depDelay = firstRide?.let { delayMinutes(it.departureDate, it.expectedDepartureDate) }
    val arrDelay = lastRide?.let { delayMinutes(it.arrivalDate, it.expectedArrivalDate) }
    val canceled = r.rides.any { leg -> leg.stations.firstOrNull()?.canceled == true }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(r.departureDate.hhmm(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    DelayTag(depDelay, tc.late, tc.onTime)
                    Text(" – ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.arrivalDate.hhmm(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    DelayTag(arrDelay, tc.late, tc.onTime)
                    if (fastest) {
                        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                        Icon(
                            Icons.Rounded.Star, "Schnellste Verbindung für diesen Anschluss",
                            Modifier.padding(start = 6.dp, bottom = 3.dp).size(18.dp),
                            tint = if (dark) androidx.compose.ui.graphics.Color(0xFFFFD166) else androidx.compose.ui.graphics.Color(0xFFE8A100),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    r.partialRoutes.forEach { leg ->
                        if (leg.isWalk) {
                            if (leg.walkMinutes >= 3) {
                                Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, "Fußweg", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else LineBadge(leg.shortName, leg.mode())
                    }
                    if (canceled) Text("  Fällt aus", color = tc.late, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(firstRide?.stations?.firstOrNull()?.name?.let { "ab $it" } ?: "Fußweg")
                        dev.padermodern.ui.components.shortPlatform(firstRide?.stations?.firstOrNull()?.platform)?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (saved) Icon(Icons.Rounded.Star, "Gespeichert", Modifier.size(16.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(durationLabel(r.realDeparture, r.realArrival), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (r.changes == 0) "Direkt" else "${r.changes} Umst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (r.ticketPriceAvailable && r.price != null && r.price > 0) {
                    Text(euro(r.price), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DelayTag(delay: Long?, late: androidx.compose.ui.graphics.Color, onTime: androidx.compose.ui.graphics.Color) {
    if (delay == null || delay == 0L) return
    Text(
        (if (delay > 0) "+$delay" else "$delay"),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = if (delay >= 3) late else onTime,
        modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(current: ZonedDateTime?, onDismiss: () -> Unit, onPick: (ZonedDateTime?) -> Unit) {
    val base = current ?: ZonedDateTime.now()
    var pickDate by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(base.toLocalDate()) }
    val time = rememberTimePickerState(base.hour, base.minute, is24Hour = true)
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )

    if (pickDate) {
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("OK") }
            },
        ) { DatePicker(dateState) }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reisezeit") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)))
                }
                Spacer(Modifier.height(16.dp))
                TimePicker(time)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(date.atTime(time.hour, time.minute).atZone(ZoneId.systemDefault()))
            }) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = { onPick(null) }) { Text("Jetzt") } },
    )
}
