package dev.padermodern.ui.screens

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.padermodern.data.SrsApi
import dev.padermodern.data.TrafficReport
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.components.ErrorState
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.LoadingState
import dev.padermodern.ui.components.MessageState
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.StatusBarScrim
import androidx.compose.foundation.layout.Box
import dev.padermodern.ui.components.modeOf

private fun parseIncidents(html: String?): List<Pair<String, String>> {
    if (html.isNullOrBlank()) return emptyList()
    val body = html.substringAfter("<body>", html).substringBefore("</body>")
    return Regex("<h2>(.*?)</h2>(.*?)(?=<h2>|$)", RegexOption.DOT_MATCHES_ALL).findAll(body).map { m ->
        val title = AnnotatedString.fromHtml(m.groupValues[1]).text.trim()
        val text = m.groupValues[2].replace(Regex("<hr[^>]*/?>"), "")
        title to text
    }.toList()
}

private data class Incident(val title: String, val body: String, val lines: List<TrafficReport>)

private fun groupIncidents(reports: List<TrafficReport>): List<Incident> {
    val byTitle = LinkedHashMap<String, Pair<String, MutableList<TrafficReport>>>()
    reports.forEach { r ->
        parseIncidents(r.allWarnings).forEach { (title, body) ->
            byTitle.getOrPut(title) { body to mutableListOf() }.second += r
        }
    }
    return byTitle.map { (title, v) -> Incident(title, v.first, v.second.distinctBy { it.lineName }) }
        .sortedByDescending { it.lines.size }
}

private fun TrafficReport.short() = lineName?.substringAfterLast(' ')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InfoScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadInfo() }
    val reports = vm.reports
    val affected = reports.data.orEmpty().filter { it.warningsCount > 0 }
    val affectedNames = affected.map { it.lineName }.toSet()
    val incidents = remember(reports.data) { groupIncidents(affected) }
    val subtitle = when {
        reports.data == null -> "Lade aktuelle Meldungen …"
        incidents.isEmpty() -> "Alles läuft nach Plan"
        incidents.size == 1 -> "1 Meldung · ${affected.size} Linien betroffen"
        else -> "${incidents.size} Meldungen · ${affected.size} Linien betroffen"
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = reports.loading && reports.data != null,
            onRefresh = { vm.loadInfo(force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item { PageHeader("Störungen & Netz", subtitle, onBack = onBack) }
                when {
                    reports.data == null && reports.error != null -> item { ErrorState(reports.error) { vm.loadInfo(true) } }
                    reports.data == null -> item { LoadingState() }
                    incidents.isEmpty() -> item { MessageState(Icons.Rounded.CheckCircle, "Keine Störungen", "Für keine Linie liegen Meldungen vor.") }
                    else -> items(incidents, key = { it.title }) { IncidentCard(it) }
                }

                vm.lines.data?.takeIf { it.isNotEmpty() }?.let { lines ->
                    item { SectionLabel("Alle Linien") }
                    item {
                        FlowRow(
                            Modifier.padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            lines.sortedWith(compareBy({ it.name?.toIntOrNull() ?: Int.MAX_VALUE }, { it.name })).forEach { l ->
                                val hasIssue = affectedNames.any { n -> n?.endsWith(" ${l.name}") == true }
                                BadgedBox(badge = { if (hasIssue) Badge(containerColor = MaterialTheme.colorScheme.error) }) {
                                    LineBadge(l.name, modeOf(l.transportationType, l.type, l.name), large = true)
                                }
                            }
                        }
                    }
                }

                vm.linePlans.data?.takeIf { it.isNotEmpty() }?.let { plans ->
                    item { SectionLabel("Liniennetzpläne") }
                    items(plans, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(p.title ?: p.id) },
                            leadingContent = { Icon(Icons.Rounded.Map, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Rounded.OpenInNew, "Öffnen") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, SrsApi.linePlanUrl(p.id).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                        )
                    }
                }
            }
        }
        StatusBarScrim()
    }
}

@Composable
private fun IncidentCard(i: Incident) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).animateContentSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                i.lines.sortedWith(compareBy({ it.short()?.toIntOrNull() ?: Int.MAX_VALUE }, { it.short() })).forEach { r ->
                    LineBadge(r.short(), modeOf(r.type, r.lineName, r.short()))
                }
            }
            Spacer(Modifier.padding(top = 12.dp))
            Text(i.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expanded) {
                Text(
                    AnnotatedString.fromHtml(i.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    "Tippen für Details",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
