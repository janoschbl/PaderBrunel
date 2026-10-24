package dev.padermodern.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.padermodern.data.Departure
import dev.padermodern.data.LocationProvider
import dev.padermodern.data.Station
import dev.padermodern.data.delayMinutes
import dev.padermodern.data.minutesUntil
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.ErrorState
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.LoadingState
import dev.padermodern.ui.components.MessageState
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.StatusBarScrim
import androidx.compose.foundation.layout.Box
import dev.padermodern.ui.components.WalkChip
import dev.padermodern.ui.components.countdown
import dev.padermodern.ui.components.mode
import dev.padermodern.ui.theme.LocalTransitColors
import kotlinx.coroutines.delay

@Composable
fun rememberNow(): Long {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now.longValue = System.currentTimeMillis()
        }
    }
    return now.longValue
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeparturesScreen(
    vm: AppViewModel,
    onSearch: () -> Unit,
    onStation: (Station) -> Unit,
    onDeparture: (Departure) -> Unit,
) {
    val ctx = LocalContext.current
    val favorites by vm.store.favorites.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshNearby() }
    LaunchedEffect(Unit) {
        if (!LocationProvider.hasPermission(ctx)) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (vm.nearby.data == null) {
            vm.refreshNearby()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            if (!vm.nearby.loading) vm.refreshNearby()
        }
    }
    val state = vm.nearby
    rememberNow()

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = state.loading && state.data != null,
            onRefresh = { vm.refreshNearby() },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize().testTag("nearby_list"), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    PageHeader(
                        "Abfahrten",
                        subtitle = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = LocalTransitColors.current.onTime,
                                    modifier = Modifier.size(8.dp),
                                ) {}
                                Spacer(Modifier.width(6.dp))
                                Text(if (vm.usingFallbackLocation) "Live · Paderborn Zentrum" else "Live · in deiner Nähe", maxLines = 1)
                            }
                        },
                    )
                }
                item {
                    Surface(
                        onClick = onSearch,
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp)
                            .testTag("board_search"),
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text("Haltestelle suchen", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                val stopChips = favorites.filter { it.isStop } + vm.nearbyStops.filter { s -> favorites.none { it.key == s.key } }
                if (stopChips.isNotEmpty()) item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        items(stopChips, key = { it.key }) { s ->
                            val fav = favorites.any { it.key == s.key }
                            AssistChip(
                                onClick = { onStation(s) },
                                label = { Text(s.name.orEmpty()) },
                                leadingIcon = {
                                    Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.Train, null, Modifier.size(AssistChipDefaults.IconSize))
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                }
                when {
                    state.data == null && state.loading -> item { LoadingState(label = "Suche Haltestellen in der Nähe …") }
                    state.data == null && state.error != null -> item { ErrorState(state.error) { vm.refreshNearby() } }
                    state.data != null && state.data.isEmpty() -> item {
                        MessageState(Icons.Rounded.LocationOff, "Keine Abfahrten in der Nähe", "Im Umkreis von 1 km fährt gerade nichts. Suche eine Haltestelle.")
                    }
                    state.data != null -> {
                        item { ColumnHeader() }
                        items(state.data, key = { it.departure.key }) { nd ->
                            DepartureRow(nd.departure, nd.walkMinutes, showStop = true, modifier = Modifier.animateItem()) { onDeparture(nd.departure) }
                        }
                    }
                }
            }
        }
        StatusBarScrim()
    }
}

@Composable
private fun ColumnHeader() {
    Column {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("Linie", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Richtung", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Abfahrt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun DepartureRow(
    d: Departure,
    walkMinutes: Int?,
    showStop: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tc = LocalTransitColors.current
    val mins = minutesUntil(d.realDeparture)
    val delay = delayMinutes(d.departureDate, d.expectedDepartureDate)
    val reachable = walkMinutes == null || mins == null || mins >= walkMinutes
    Column(modifier.clickable(onClick = onClick).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(72.dp)) { LineBadge(d.shortName, d.mode()) }
            Column(Modifier.weight(1f)) {
                Text(
                    d.destination.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildList {
                    if (showStop) add(d.stopName.orEmpty())
                    d.platformName?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
                if (sub.isNotEmpty() || walkMinutes != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (walkMinutes != null) {
                        Spacer(Modifier.width(8.dp))
                        WalkChip(walkMinutes)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val label = if (d.canceled) "Fällt aus" else countdown(mins, d.realDeparture)
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        d.canceled -> tc.late
                        label == "Jetzt" -> tc.onTime
                        !reachable -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (delay != null && delay > 0 && !d.canceled) {
                    Text("+$delay", style = MaterialTheme.typography.labelMedium, color = if (delay >= 3) tc.late else tc.onTime, fontWeight = FontWeight.Bold)
                } else if (d.expectedDepartureDate == null && !d.canceled) {
                    Text("Plan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
