package dev.padermodern.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.padermodern.data.LocationProvider
import dev.padermodern.data.Route
import dev.padermodern.data.Station
import dev.padermodern.data.decodePolyline
import dev.padermodern.data.durationLabel
import dev.padermodern.data.euro
import dev.padermodern.data.hhmm
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.MapLine
import dev.padermodern.ui.components.StatusBarScrim
import dev.padermodern.ui.components.TransitMap
import dev.padermodern.ui.components.TripTimeline
import dev.padermodern.ui.components.legColor
import dev.padermodern.ui.theme.LocalTransitColors

@Composable
fun rememberTripLines(r: Route): List<MapLine> {
    val walkColor = LocalTransitColors.current.walk
    val colors = r.partialRoutes.map { if (it.isWalk) walkColor else legColor(it) }
    return remember(r.key, colors) {
        r.partialRoutes.mapIndexed { i, leg ->
            val pts = leg.encodedPath?.let { decodePolyline(it) } ?: leg.stations.map { it.latitude to it.longitude }
            MapLine(pts, colors[i])
        }
    }
}

@Composable
fun TripSummary(r: Route) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            "${r.departureDate.hhmm()} – ${r.arrivalDate.hhmm()}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
        )
        Text(
            buildString {
                append(durationLabel(r.departureDate, r.arrivalDate))
                append(" · ")
                append(if (r.changes == 0) "ohne Umstieg" else if (r.changes == 1) "1 Umstieg" else "${r.changes} Umstiege")
                if (r.ticketPriceAvailable && r.price != null && r.price > 0) append(" · ${euro(r.price)}")
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.fares.firstOrNull()?.let { f ->
            Text(
                listOfNotNull(f.name, f.net, f.priceLevel?.let { "Preisstufe $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TripDetailScreen(vm: AppViewModel, onBack: () -> Unit, onStation: (Station) -> Unit) {
    val r = vm.selectedRoute ?: return
    val saved by vm.store.savedTrips.collectAsState()
    val isSaved = saved.any { it.id == r.key }
    val lines = rememberTripLines(r)
    val endpoints = listOfNotNull(
        r.partialRoutes.firstOrNull()?.stations?.firstOrNull(),
        r.partialRoutes.lastOrNull()?.stations?.lastOrNull(),
    )

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    TransitMap(
                        modifier = Modifier.fillMaxSize(),
                        lines = lines,
                        stops = endpoints,
                        center = endpoints.firstOrNull()?.let { it.latitude to it.longitude }
                            ?: (LocationProvider.FALLBACK_LAT to LocationProvider.FALLBACK_LON),
                        fitToContent = true,
                    )
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                        FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        FilledTonalIconButton(onClick = { vm.toggleSaved(r) }, modifier = Modifier.testTag("save_trip")) {
                            Icon(
                                if (isSaved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                if (isSaved) "Fahrt nicht mehr merken" else "Fahrt merken",
                                tint = if (isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item { TripSummary(r) }
            item { TripTimeline(r, onStation) }
        }
        StatusBarScrim()
    }
}
