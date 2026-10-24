package dev.padermodern.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.padermodern.data.Station
import dev.padermodern.ui.screens.BoardScreen
import dev.padermodern.ui.screens.DepartureDetailScreen
import dev.padermodern.ui.screens.DeparturesScreen
import dev.padermodern.ui.screens.InfoScreen
import dev.padermodern.ui.screens.MapScreen
import dev.padermodern.ui.screens.PickTarget
import dev.padermodern.ui.screens.SearchScreen
import dev.padermodern.ui.screens.TripDetailScreen
import dev.padermodern.ui.screens.TripsScreen
import dev.padermodern.ui.screens.ProfileScreen
import dev.padermodern.ui.screens.TripLiveScreen
import dev.padermodern.ui.screens.rememberNow
import dev.padermodern.ui.components.TripBar
import dev.padermodern.ui.components.worthShowing
import dev.padermodern.data.LiveTrip
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("departures", "Abfahrten", Icons.Outlined.Schedule, Icons.Rounded.Schedule),
    Tab("trips", "Verbindungen", Icons.Outlined.Route, Icons.Rounded.Route),
    Tab("map", "Karte", Icons.Outlined.Map, Icons.Rounded.Map),
    Tab("settings", "Profil", Icons.Outlined.Person, Icons.Rounded.Person),
)

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaderModernApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    fun openBoard(s: Station) {
        vm.openBoard(s)
        nav.navigate("board")
    }

    fun openLive(id: String) {
        vm.liveTripId = id
        nav.navigate("live")
    }

    val savedTrips by vm.store.savedTrips.collectAsState()
    val barOn by vm.store.tripBar.collectAsState()
    val lead by vm.store.leadMinutes.collectAsState()
    val tick = rememberNow()
    val active = remember(savedTrips, vm.liveRoutes, lead, tick) {
        savedTrips.map { it to vm.routeFor(it) }
            .firstOrNull { (_, r) -> LiveTrip.isActive(r, lead.toLong()) }
            ?.let { (t, r) -> Triple(t, r, LiveTrip.status(r)) }
            ?.takeIf { it.third.worthShowing() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshActiveTrips()
            delay(45_000)
        }
    }

    Scaffold(
        // testtags as resource ids so adb can drive the ui
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = showBar && barOn && active != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    active?.let { (t, r, st) -> TripBar(r, st, onClick = { openLive(t.id) }) }
                }
                AnimatedVisibility(showBar, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    ShortNavigationBar {
                        tabs.forEach { t ->
                            val selected = route == t.route
                            ShortNavigationBarItem(
                                selected = selected,
                                onClick = { nav.switchTab(t.route) },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, null) },
                                label = { Text(t.label) },
                                modifier = Modifier.testTag("tab_${t.route}"),
                            )
                        }
                    }
                }
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        NavHost(
            nav,
            startDestination = "departures",
            // bar corners are transparent, page runs 20 dp under it
            modifier = Modifier.fillMaxSize().padding(
                bottom = if (showBar && barOn && active != null) (pad.calculateBottomPadding() - 20.dp).coerceAtLeast(0.dp) else pad.calculateBottomPadding(),
            ),
            enterTransition = { fadeIn() + slideInHorizontally { it / 8 } },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() + slideOutHorizontally { it / 8 } },
        ) {
            composable("departures") {
                DeparturesScreen(
                    vm,
                    onSearch = { nav.navigate("search/board") },
                    onStation = ::openBoard,
                    onDeparture = { vm.selectedDeparture = it; nav.navigate("departure") },
                )
            }
            composable("trips") {
                TripsScreen(
                    vm,
                    onPick = { nav.navigate(if (it == PickTarget.From) "search/from" else "search/to") },
                    onRoute = { vm.selectedRoute = it; nav.navigate("trip") },
                    onSaved = { openLive(it.id) },
                )
            }
            composable("map") {
                MapScreen(
                    vm,
                    onSearch = { nav.navigate("search/board") },
                    onBoard = ::openBoard,
                    onRouteTo = { s ->
                        vm.to = s
                        if (vm.from == null) vm.from = vm.myLocationStation()
                        nav.switchTab("trips")
                        vm.searchTrips()
                    },
                )
            }
            composable("settings") { ProfileScreen(vm, onInfo = { nav.navigate("info") }, onTrip = ::openLive, onStation = ::openBoard) }
            composable("info") { InfoScreen(vm, onBack = { nav.popBackStack() }) }
            composable("live") {
                TripLiveScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onStation = ::openBoard,
                    onAlternatives = {
                        val trip = savedTrips.firstOrNull { it.id == vm.liveTripId } ?: return@TripLiveScreen
                        val r = vm.routeFor(trip)
                        val status = LiveTrip.status(r)
                        val parts = r.partialRoutes
                        vm.from = parts.getOrNull(status.brokenLeg ?: status.legIndex)?.stations?.firstOrNull()
                        vm.to = parts.lastOrNull()?.stations?.lastOrNull()
                        vm.tripTime = null
                        vm.arrival = false
                        vm.searchTrips()
                        nav.switchTab("trips")
                    },
                )
            }
            composable("search/{target}") { e ->
                val target = e.arguments?.getString("target") ?: "board"
                SearchScreen(
                    vm,
                    title = when (target) {
                        "from" -> "Start suchen"
                        "to" -> "Ziel suchen"
                        else -> "Haltestelle suchen"
                    },
                    onlyStops = target == "board",
                    offerMyLocation = target != "board",
                    onPick = { s ->
                        when (target) {
                            "from" -> { vm.from = s; nav.popBackStack(); if (vm.to != null) vm.searchTrips() }
                            "to" -> { vm.to = s; nav.popBackStack(); if (vm.from != null) vm.searchTrips() }
                            else -> {
                                nav.popBackStack()
                                openBoard(s)
                            }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("board") {
                BoardScreen(vm, onBack = { nav.popBackStack() }, onDeparture = { vm.selectedDeparture = it; nav.navigate("departure") })
            }
            composable("departure") {
                DepartureDetailScreen(vm, onBack = { nav.popBackStack() }, onStation = ::openBoard)
            }
            composable("trip") {
                TripDetailScreen(vm, onBack = { nav.popBackStack() }, onStation = ::openBoard)
            }
        }
    }
}
