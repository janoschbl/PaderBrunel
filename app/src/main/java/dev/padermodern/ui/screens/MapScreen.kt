package dev.padermodern.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import dev.padermodern.data.Departure
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.mode
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.padermodern.data.LocationProvider
import dev.padermodern.data.Station
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.TransitMap

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MapScreen(
    vm: AppViewModel,
    onSearch: () -> Unit,
    onBoard: (Station) -> Unit,
    onRouteTo: (Station) -> Unit,
) {
    var selected by remember { mutableStateOf<Station?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var platforms by remember { mutableStateOf<List<Pair<String, List<Departure>>>?>(null) }
    LaunchedEffect(selected) {
        platforms = null
        selected?.let { platforms = vm.stopPlatforms(it) }
    }
    val shown = remember(vm.mapStops, filter) {
        when (filter) {
            1 -> vm.mapStops.filter { it.isRailStation }
            2 -> vm.mapStops.filter { !it.isRailStation }
            else -> vm.mapStops
        }
    }
    var recenter by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.ensureLocation() }
    val user = vm.userLat?.let { lat -> vm.userLon?.let { lat to it } }
    val center = user ?: (LocationProvider.FALLBACK_LAT to LocationProvider.FALLBACK_LON)

    Box(Modifier.fillMaxSize()) {
        TransitMap(
            modifier = Modifier.fillMaxSize().testTag("map"),
            stops = shown,
            user = user,
            center = center,
            zoom = 16.0,
            recenter = recenter,
            onCameraIdle = { lat, lon, _ -> vm.loadMapStops(lat, lon) },
            onStopClick = { selected = it },
        )
        Column(Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            onClick = onSearch,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text("Haltestelle suchen", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Alle", "Bahnhöfe", "Bushaltestellen").forEachIndexed { i, label ->
                    FilterChip(
                        selected = filter == i,
                        onClick = { filter = i },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
            }
        }
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End) {
            FloatingActionButton(
                onClick = { vm.ensureLocation(); recenter++ },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) { Icon(Icons.Rounded.MyLocation, "Mein Standort") }
            AnimatedVisibility(
                visible = selected != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                val s = selected ?: return@AnimatedVisibility
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.name.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                s.locality?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            IconButton(onClick = { selected = null }) { Icon(Icons.Rounded.Close, "Schließen") }
                        }
                        Spacer(Modifier.height(8.dp))
                        PlatformList(platforms)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onBoard(s) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Schedule, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Abfahrten")
                            }
                            OutlinedButton(onClick = { onRouteTo(s) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Directions, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Hierhin")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformList(platforms: List<Pair<String, List<Departure>>>?) {
    when {
        platforms == null -> Text("Lade Steige …", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        platforms.isEmpty() -> Text("Keine Abfahrten gefunden", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        else -> Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            platforms.forEach { (steig, deps) ->
                val dirs = deps.distinctBy { it.shortName to it.destination }.take(6)
                Column {
                    if (platforms.size > 1 && steig.isNotEmpty()) {
                        Text("Steig $steig", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    dirs.forEach { d ->
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(d.shortName, d.mode())
                            Spacer(Modifier.width(10.dp))
                            Text("nach ${d.destination.orEmpty()}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
