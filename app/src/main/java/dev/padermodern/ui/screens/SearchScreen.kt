package dev.padermodern.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.padermodern.data.SrsApi
import dev.padermodern.data.Station
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.MessageState
import kotlinx.coroutines.delay

private fun iconFor(s: Station): ImageVector = when (s.type) {
    "stop" -> Icons.Rounded.DirectionsBus
    "street", "singlehouse" -> Icons.Rounded.Home
    "coord" -> Icons.Rounded.MyLocation
    else -> Icons.Rounded.Place
}

private fun subtitleFor(s: Station): String? = when (s.type) {
    "stop" -> s.locality?.takeIf { !s.name.orEmpty().startsWith(it) }?.let { "Haltestelle · $it" } ?: "Haltestelle"
    "street" -> "Straße"
    "singlehouse" -> "Adresse"
    "poi" -> "Ort von Interesse"
    else -> s.locality
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchScreen(
    vm: AppViewModel,
    title: String,
    onlyStops: Boolean,
    offerMyLocation: Boolean,
    onPick: (Station) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Station>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val favorites by vm.store.favorites.collectAsState()
    val recents by vm.store.recents.collectAsState()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        error = null
        if (query.trim().length < 2) {
            results = emptyList(); loading = false; return@LaunchedEffect
        }
        delay(300)
        loading = true
        runCatching { SrsApi.searchStations(query.trim(), onlyStops) }
            .onSuccess { results = it }
            .onFailure { error = "Suche fehlgeschlagen: ${it.message}" }
        loading = false
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .statusBarsPadding().padding(vertical = 8.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag("search_field"),
                    placeholder = { Text(title) },
                    singleLine = true,
                    leadingIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Leeren") }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    textStyle = MaterialTheme.typography.titleMedium,
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (loading) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (query.trim().length < 2) {
                    if (offerMyLocation) item {
                        PlaceRow(
                            icon = Icons.Rounded.MyLocation, title = "Mein Standort", subtitle = "Aktuelle Position verwenden",
                            highlight = true,
                        ) {
                            vm.locateForTrip { s -> if (s != null) onPick(s) }
                        }
                    }
                    val favs = favorites.filter { !onlyStops || it.isStop }
                    if (favs.isNotEmpty()) {
                        item { SectionLabel("Favoriten") }
                        items(favs, key = { "f" + it.key }) { s ->
                            PlaceRow(Icons.Rounded.Star, s.name.orEmpty(), subtitleFor(s)) { onPick(s) }
                        }
                    }
                    val rec = recents.filter { (!onlyStops || it.isStop) && favs.none { f -> f.key == it.key } }
                    if (rec.isNotEmpty()) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("Zuletzt gesucht", Modifier.weight(1f))
                                TextButton(onClick = { vm.store.clearRecents() }, modifier = Modifier.padding(end = 8.dp)) { Text("Löschen") }
                            }
                        }
                        items(rec, key = { "r" + it.key }) { s ->
                            PlaceRow(Icons.Rounded.History, s.name.orEmpty(), subtitleFor(s)) { onPick(s) }
                        }
                    }
                    if (favs.isEmpty() && rec.isEmpty() && !offerMyLocation) item {
                        MessageState(Icons.Rounded.Place, "Haltestelle suchen", "Tippe mindestens zwei Buchstaben, zum Beispiel „Kamp“ oder „Hbf“.")
                    }
                } else {
                    error?.let { e -> item { Text(e, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) } }
                    if (!loading && error == null && results.isEmpty()) item {
                        MessageState(Icons.Rounded.SearchOff, "Nichts gefunden", "Prüfe die Schreibweise oder versuche einen anderen Namen.")
                    }
                    items(results, key = { "s" + it.key }) { s ->
                        val fav = favorites.any { it.key == s.key }
                        PlaceRow(
                            iconFor(s), s.name.orEmpty(), subtitleFor(s),
                            trailing = {
                                IconButton(onClick = { vm.store.toggleFavorite(s) }) {
                                    Icon(
                                        if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                        if (fav) "Favorit entfernen" else "Als Favorit speichern",
                                        tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                        ) { onPick(s) }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun PlaceRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    highlight: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 2) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            Box(
                Modifier.size(40.dp)
                    .clip(if (highlight) MaterialShapes.Cookie4Sided.toShape() else RoundedCornerShape(12.dp))
                    .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null, Modifier.size(22.dp),
                    tint = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
