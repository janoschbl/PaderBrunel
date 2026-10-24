package dev.padermodern.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.padermodern.data.Departure
import dev.padermodern.data.Station
import dev.padermodern.data.hhmm
import dev.padermodern.data.parseTime
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.ErrorState
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.LoadingState
import dev.padermodern.ui.components.MessageState
import dev.padermodern.ui.components.TimeWithDelay
import dev.padermodern.ui.components.mode
import dev.padermodern.ui.components.modeColors
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoardScreen(vm: AppViewModel, onBack: () -> Unit, onDeparture: (Departure) -> Unit) {
    val station = vm.boardStation ?: return
    val favorites by vm.store.favorites.collectAsState()
    val fav = favorites.any { it.key == station.key }
    val state = vm.board
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    rememberNow()
    LaunchedEffect(station.key) {
        while (true) {
            delay(30_000)
            vm.refreshBoard()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(station.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Black) },
                subtitle = { Text(station.locality ?: "Abfahrtstafel") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                actions = {
                    IconButton(onClick = { vm.store.toggleFavorite(station) }, modifier = Modifier.testTag("fav_toggle")) {
                        Icon(
                            if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            if (fav) "Favorit entfernen" else "Als Favorit speichern",
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.data != null,
            onRefresh = { vm.refreshBoard() },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                when {
                    state.data == null && state.error != null -> item { ErrorState(state.error) { vm.refreshBoard() } }
                    state.data == null -> item { LoadingState(label = "Lade Abfahrten …") }
                    state.data.isEmpty() -> item {
                        MessageState(Icons.Rounded.EventBusy, "Keine Abfahrten", "An dieser Haltestelle fährt in nächster Zeit nichts.")
                    }
                    else -> items(state.data, key = { it.key }) { d ->
                        DepartureRow(d, null, showStop = false, modifier = Modifier.animateItem()) { onDeparture(d) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DepartureDetailScreen(vm: AppViewModel, onBack: () -> Unit, onStation: (Station) -> Unit) {
    val d = vm.selectedDeparture ?: return
    val mode = d.mode()
    val color = modeColors(mode).container
    val ride = d.route?.partialRoutes?.firstOrNull { !it.isWalk }
    val stops: List<Station> = if (d.lineStations.isNotEmpty()) {
        d.lineStations.sortedBy { it.index }.map { Station(type = "stop", id = it.id, name = it.name, date = it.date) }
    } else ride?.stations.orEmpty()
    val realtime = ride?.stations.orEmpty().associateBy { it.id }
    val now = ZonedDateTime.now()
    val originIndex = stops.indexOfFirst { it.id == d.stopId }.coerceAtLeast(0)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(d.shortName, mode, large = true)
                        Spacer(Modifier.width(12.dp))
                        Text(d.destination.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Black)
                    }
                },
                subtitle = { Text("ab ${d.stopName} · ${d.departureDate.hhmm()}" + (d.platformName?.let { " · $it" } ?: "")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(stops, key = { i, s -> "$i${s.id}" }) { i, s ->
                val rt = realtime[s.id]
                val planned = rt?.date ?: s.date
                val passed = parseTime(rt?.expectedDate ?: planned)?.isBefore(now) == true
                val isOrigin = s.id == d.stopId
                StopTimelineRow(
                    name = s.name.orEmpty(),
                    planned = planned,
                    expected = rt?.expectedDate,
                    color = color,
                    first = i == 0,
                    last = i == stops.lastIndex,
                    dimmed = !isOrigin && (i < originIndex || passed),
                    emphasized = isOrigin,
                    onClick = { if (s.id != null) onStation(s) },
                )
            }
        }
    }
}

@Composable
fun StopTimelineRow(
    name: String,
    planned: String?,
    expected: String?,
    color: Color,
    first: Boolean,
    last: Boolean,
    dimmed: Boolean,
    emphasized: Boolean,
    subtitle: String? = null,
    canceled: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val alpha = if (dimmed) 0.45f else 1f
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(64.dp)) {
            Column(Modifier.alpha(alpha)) { TimeWithDelay(planned, expected, canceled) }
        }
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(6.dp).weight(1f).background(if (first) Color.Transparent else color.copy(alpha = alpha)))
                Box(Modifier.width(6.dp).weight(1f).background(if (last) Color.Transparent else color))
            }
            Box(
                Modifier.size(if (emphasized || first || last) 18.dp else 12.dp).clip(CircleShape)
                    .background(if (emphasized) color else MaterialTheme.colorScheme.surface)
                    .padding(3.dp).clip(CircleShape).background(color),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp).alpha(alpha)) {
            Text(
                name,
                style = if (emphasized || first || last) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasized || first || last) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (canceled) TextDecoration.LineThrough else null,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
