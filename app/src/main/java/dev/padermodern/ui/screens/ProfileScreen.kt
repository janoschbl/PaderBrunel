package dev.padermodern.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.padermodern.data.RouteStyle
import dev.padermodern.data.ThemeMode
import dev.padermodern.data.hhmm
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.RouteText
import dev.padermodern.ui.components.StatusBarScrim
import dev.padermodern.data.Station

private val ItemColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun ProfileSection(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ProfileHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, tag: (T) -> String) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    val pill = RoundedCornerShape(50)
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
            .clip(pill).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(
            segment * index,
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "segmentOffset",
        )
        Box(
            Modifier.offset(offset).width(segment).fillMaxHeight().padding(4.dp)
                .clip(pill).background(MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { m ->
                val active = selected == m
                val color by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segmentText",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(pill).testTag(tag(m))
                        .clickable(role = Role.Tab, onClick = { onSelect(m) }),
                    contentAlignment = Alignment.Center,
                ) { Text(label(m), style = MaterialTheme.typography.labelLarge, color = color) }
            }
        }
    }
}

private enum class ProfileTab(val label: String) { Saved("Gemerkt"), Settings("Einstellungen") }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileScreen(vm: AppViewModel, onInfo: () -> Unit, onTrip: (String) -> Unit, onStation: (Station) -> Unit) {
    val store = vm.store
    val theme by store.themeMode.collectAsState()
    val bar by store.tripBar.collectAsState()
    val lead by store.leadMinutes.collectAsState()
    val prefs by store.tripPrefs.collectAsState()
    val trips by store.savedTrips.collectAsState()
    val favorites by store.favorites.collectAsState()
    val recents by store.recents.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val current = ProfileTab.entries[tab]

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("settings_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PageHeader("Profil", "Gemerktes und Einstellungen") }
            item {
                Segmented(ProfileTab.entries, current, { it.label }, { tab = it.ordinal }, { "profile_${it.name}" })
                Spacer(Modifier.height(4.dp))
            }

            if (current == ProfileTab.Saved) {
                item { ProfileSection("Fahrten") }
                if (trips.isEmpty()) item { ProfileHint("Noch nichts gemerkt. Tippe in den Verbindungsdetails auf den Stern.") }
                else items(trips, key = { it.id }) { t ->
                    val r = vm.routeFor(t)
                    ListItem(
                        headlineContent = { RouteText(t.fromName, t.toName) },
                        supportingContent = { Text("${r.departureDate.hhmm()} – ${r.arrivalDate.hhmm()}") },
                        leadingContent = { Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { IconButton(onClick = { store.removeTrip(t.id) }) { Icon(Icons.Rounded.Close, "Entfernen") } },
                        colors = ItemColors,
                        modifier = Modifier.clickable { onTrip(t.id) },
                    )
                }
                item { ProfileSection("Haltestellen") }
                if (favorites.isEmpty()) item { ProfileHint("Noch keine Favoriten. Tippe in den Abfahrten einer Haltestelle auf den Stern.") }
                else items(favorites, key = { it.key }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name.orEmpty(), maxLines = 1) },
                        supportingContent = s.locality?.let { { Text(it) } },
                        leadingContent = { Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { IconButton(onClick = { store.toggleFavorite(s) }) { Icon(Icons.Rounded.Close, "Entfernen") } },
                        colors = ItemColors,
                        modifier = Modifier.clickable(enabled = s.isRealStop) { onStation(s) },
                    )
                }
            } else {
                item { ProfileSection("Darstellung") }
                item { Segmented(ThemeMode.entries, theme, { it.label }, { store.setThemeMode(it) }, { "theme_${it.name}" }) }

                item { ProfileSection("Verbindungssuche") }
                item { Segmented(RouteStyle.entries, prefs.style, { it.label }, { store.setTripPrefs(prefs.copy(style = it)) }, { "style_${it.name}" }) }
                item { ProfileHint(prefs.style.hint) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Mindest-Umstiegszeit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    Segmented(listOf(0, 3, 5, 8, 10), prefs.minTransferMin, { "$it" }, { store.setTripPrefs(prefs.copy(minTransferMin = it)) }, { "transfer_$it" })
                }
                item { ProfileHint("Minuten zwischen Ankunft und Weiterfahrt. Knappere Verbindungen werden nicht angezeigt.") }
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Maximale Umstiege", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    Segmented(listOf(0, 1, 2), prefs.maxChanges, { if (it == 0) "Direkt" else "$it" }, { store.setTripPrefs(prefs.copy(maxChanges = it)) }, { "changes_$it" })
                }

                item { ProfileSection("Reisebegleiter") }
                item {
                    ListItem(
                        headlineContent = { Text("Leiste für gemerkte Fahrten") },
                        supportingContent = { Text("Schnellzugriff auf deine laufende Fahrt über der Navigation") },
                        trailingContent = { Switch(checked = bar, onCheckedChange = { store.setTripBar(it) }, modifier = Modifier.testTag("bar_switch")) },
                        colors = ItemColors,
                    )
                }
                if (bar) item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Anzeigen ab", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    Segmented(listOf(15, 30, 60), lead, { "$it min" }, { store.setLeadMinutes(it) }, { "lead_$it" })
                }

                item { ProfileSection("Netz") }
                item {
                    ListItem(
                        headlineContent = { Text("Störungen & Liniennetz") },
                        supportingContent = { Text("Meldungen, alle Linien und Netzpläne") },
                        leadingContent = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        colors = ItemColors,
                        modifier = Modifier.clickable(onClick = onInfo).testTag("open_info"),
                    )
                }

                item { ProfileSection("Daten") }
                item {
                    ListItem(
                        headlineContent = { Text("Suchverlauf löschen") },
                        supportingContent = { Text(if (recents.isEmpty()) "Kein Verlauf vorhanden" else "${recents.size} zuletzt gesuchte Orte") },
                        leadingContent = { Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ItemColors,
                        modifier = Modifier.clickable(enabled = recents.isNotEmpty()) { store.clearRecents() },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("PaderModern") },
                        supportingContent = { Text("Inoffizieller Client für die PaderSprinter-Fahrplandaten") },
                        leadingContent = { Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ItemColors,
                    )
                }
            }
        }
        StatusBarScrim()
    }
}
