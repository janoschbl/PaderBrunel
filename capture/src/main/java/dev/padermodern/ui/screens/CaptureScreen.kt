package dev.padermodern.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.padermodern.capture.BusProfile
import dev.padermodern.capture.CaptureHub
import dev.padermodern.capture.CaptureState
import dev.padermodern.capture.Op
import dev.padermodern.capture.Run
import dev.padermodern.capture.SessionMeta
import dev.padermodern.capture.destinationLabel
import dev.padermodern.capture.lineLabel
import dev.padermodern.capture.stopLabel
import dev.padermodern.ui.components.FolderCard
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.Mode
import dev.padermodern.ui.components.MessageState
import dev.padermodern.ui.components.Mono
import dev.padermodern.ui.components.InfoCard
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.Pill
import dev.padermodern.ui.components.Segmented
import dev.padermodern.ui.components.PulsingDot
import dev.padermodern.ui.components.SectionLabel
import dev.padermodern.ui.components.StatusBarScrim
import dev.padermodern.ui.components.ago
import dev.padermodern.ui.components.clockTime
import dev.padermodern.ui.components.duration
import dev.padermodern.ui.components.fileSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CaptureTab(val label: String) { Lines("Linien"), Stops("Halte"), Buses("Busse"), Requests("Anfragen"), Logs("Logs") }

private val ItemColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
internal fun rememberSecond(): Long {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now.longValue = System.currentTimeMillis() } }
    return now.longValue
}

internal fun lineBadgeText(r: Run) = r.line ?: r.lineId?.let { "#$it" } ?: "?"

@Composable
fun CaptureScreen(onBus: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by CaptureHub.state.collectAsState()
    val snap by CaptureHub.snapshot.collectAsState()
    val sessions by CaptureHub.sessions.collectAsState()
    val now = rememberSecond()
    var tab by rememberSaveable { mutableStateOf(0) }
    var consent by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val current = CaptureTab.entries[tab]

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStart) { pendingStart = false; CaptureHub.start() }
    }
    fun requestStart() {
        if (!CaptureHub.hasConsent()) { consent = true; return }
        if (CaptureHub.missingPermissions().isEmpty()) CaptureHub.start()
        else { pendingStart = true; permissions.launch(CaptureHub.requiredPermissions) }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("capture_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                PageHeader(
                    "Bus-Capture", Modifier, null,
                    actions = { IconButton({ settings = true }, modifier = Modifier.testTag("capture_settings")) { Icon(Icons.Rounded.Settings, "Einstellungen") } },
                    subtitle = { Text("Bluetooth-Funk der Busse", maxLines = 1) },
                )
            }
            item { StatusCard(st, now) }
            item {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (st.running) Button(
                        onClick = { CaptureHub.stop() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.testTag("capture_stop"),
                    ) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Stopp") }
                    else Button(onClick = ::requestStart, modifier = Modifier.testTag("capture_start")) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Aufnahme starten")
                    }
                    FilledTonalButton(onClick = { noteDialog = true }, enabled = st.running, modifier = Modifier.testTag("capture_note")) {
                        Icon(Icons.Rounded.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Notiz")
                    }
                    FilledTonalButton(onClick = { exportDialog = st.viewingId }, enabled = st.viewingId != null, modifier = Modifier.testTag("capture_export")) {
                        Icon(Icons.Rounded.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Export")
                    }
                }
            }
            item {
                FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(onClick = { CaptureHub.start(demo = true) }, enabled = !st.running, modifier = Modifier.testTag("capture_demo")) {
                        Icon(Icons.Rounded.Science, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Demo-Daten")
                    }
                    TextButton(onClick = { CaptureHub.loadNames() }, enabled = !st.namesBusy, modifier = Modifier.testTag("capture_names")) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text(if (st.namesBusy) "Lade …" else "Namen laden")
                    }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Auch unbekannte Kandidaten") },
                    supportingContent = { Text("Ungefilterter Scan. Fremde Geräte werden nur gezählt (${st.ignored}).") },
                    trailingContent = { Switch(st.unfiltered, { CaptureHub.setUnfiltered(it) }, enabled = !st.running, modifier = Modifier.testTag("capture_unfiltered")) },
                    colors = ItemColors,
                    modifier = Modifier.clickable(enabled = !st.running) { CaptureHub.setUnfiltered(!st.unfiltered) }.testTag("capture_unfiltered_row"),
                )
            }
            item { Text(st.namesInfo, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Spacer(Modifier.height(16.dp))
                Segmented(CaptureTab.entries, current, { it.label }, { tab = it.ordinal }, { "capture_${it.name}" })
                Spacer(Modifier.height(8.dp))
            }
            st.viewNotice?.let { n -> item { Text(n, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

            when (current) {
                CaptureTab.Lines -> lineFolders(snap.profiles, now, onBus, byStop = false)
                CaptureTab.Stops -> lineFolders(snap.profiles, now, onBus, byStop = true)
                CaptureTab.Buses -> if (snap.profiles.isEmpty()) item { EmptyHint(st) }
                    else items(snap.profiles, key = { it.id }) { BusRow(it, now, onBus) }
                CaptureTab.Requests -> if (snap.ops.isEmpty()) item {
                    MessageState(Icons.Rounded.Cloud, "Noch keine Anfragen", "Verbindungen, Lesezugriffe und Namens-Downloads erscheinen hier.")
                } else items(snap.ops, key = { it.id }) { op -> OpRow(op, now, snap.profiles.firstOrNull { it.id == op.profileId }?.title, onBus) }
                CaptureTab.Logs -> if (sessions.isEmpty()) item {
                    MessageState(Icons.Rounded.History, "Keine Sitzungen", "Jede Aufnahme liegt als events.jsonl im App-Speicher.")
                } else items(sessions, key = { it.sessionId }) { SessionCard(it, st, onExport = { exportDialog = it.sessionId }) }
            }
        }
        StatusBarScrim()
    }

    if (settings) CaptureSettings(st, sessions.size, onDismiss = { settings = false }, onDone = { toast = it })

    if (consent) AlertDialog(
        onDismissRequest = { consent = false },
        title = { Text("Bluetooth-Adressen speichern?") },
        text = { Text("Die Aufnahme speichert Bluetooth-Adressen, Rohdaten und deine Notizen lokal im App-Speicher. Nichts wird automatisch hochgeladen. Prüfe Exporte, bevor du sie teilst.") },
        confirmButton = { TextButton({ CaptureHub.grantConsent(); consent = false; requestStart() }) { Text("Einverstanden") } },
        dismissButton = { TextButton({ consent = false }) { Text("Abbrechen") } },
    )
    if (noteDialog) NoteDialog("Notiz zur Sitzung", "z. B. Haltestelle Rathaus, Bstg. A3", onDismiss = { noteDialog = false }) { CaptureHub.addNote(it) }
    exportDialog?.let { id ->
        AlertDialog(
            onDismissRequest = { exportDialog = null },
            title = { Text("Export") },
            text = {
                Column {
                    if (st.running && st.sessionId == id) Text("Läuft noch: Export bis zur letzten vollständigen Zeile.", style = MaterialTheme.typography.bodySmall)
                    ExportOption("Log teilen (events.jsonl)", Icons.Rounded.IosShare) {
                        exportDialog = null
                        scope.launch { CaptureHub.export(id, zip = false)?.let { share(ctx, it) } ?: run { toast = "Export fehlgeschlagen" } }
                    }
                    ExportOption("Ganze Sitzung teilen (ZIP)", Icons.Rounded.IosShare) {
                        exportDialog = null
                        scope.launch { CaptureHub.export(id, zip = true)?.let { share(ctx, it) } ?: run { toast = "Export fehlgeschlagen" } }
                    }
                    ExportOption("ZIP in Downloads/Brunel speichern", Icons.Rounded.Download) {
                        exportDialog = null
                        scope.launch {
                            val f = CaptureHub.export(id, zip = true)
                            toast = if (f != null && CaptureHub.saveToDownloads(f)) "Gespeichert: Download/Brunel/${f.name}" else "Speichern fehlgeschlagen"
                        }
                    }
                }
            },
            confirmButton = { TextButton({ exportDialog = null }) { Text("Schließen") } },
        )
    }
    toast?.let { t -> AlertDialog(onDismissRequest = { toast = null }, text = { Text(t) }, confirmButton = { TextButton({ toast = null }) { Text("OK") } }) }
}

private fun share(ctx: android.content.Context, file: java.io.File) {
    val send = Intent(Intent.ACTION_SEND).setType(if (file.extension == "zip") "application/zip" else "text/plain")
        .putExtra(Intent.EXTRA_STREAM, CaptureHub.shareUri(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Capture teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ExportOption(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ItemColors, modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun NoteDialog(title: String, hint: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().testTag("note_field"), placeholder = { Text(hint) }, minLines = 2) },
        confirmButton = { TextButton({ onSave(text); onDismiss() }, enabled = text.isNotBlank(), modifier = Modifier.testTag("note_save")) { Text("Speichern") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun EmptyHint(st: CaptureState) = MessageState(
    Icons.Rounded.DirectionsBus,
    if (st.running) "Noch kein Bus empfangen" else "Noch keine Daten",
    if (st.running) "Der Scan läuft. Busse erscheinen, sobald ihre Funkpakete ankommen." else "Starte eine Aufnahme oder lade Demo-Daten, um die Ansicht zu testen.",
)

@Composable
private fun StatusCard(st: CaptureState, now: Long) {
    val scheme = MaterialTheme.colorScheme
    val missing = remember(st.running, now / 3000) { CaptureHub.missingPermissions() }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(st.running, if (st.error != null) scheme.error else if (st.running) scheme.primary else scheme.outline)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when { st.error != null -> "Aufnahme gestört"; st.running -> "Aufnahme läuft"; else -> "Bereit" },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Text(if (!st.running && st.scanStatus == "Bereit") "Keine Aufnahme aktiv" else st.scanStatus, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Mono(if (st.running) duration(android.os.SystemClock.elapsedRealtime() - st.startedElapsedMs + (now - now)) else "00:00:00", color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Empfangen", st.received.toString(), Modifier.weight(1f))
            Stat("Auf Platte", st.flushed.toString(), Modifier.weight(1f))
            Stat("Verworfen", st.dropped.toString(), Modifier.weight(1f), bad = st.dropped > 0)
            Stat("Größe", fileSize(st.storageBytes), Modifier.weight(1f))
        }
        val warnings = buildList {
            st.error?.let { add(it) }
            if (st.dropped > 0) add("${st.dropped} Pakete verworfen (Warteschlange voll). Die Aufnahme ist unvollständig.")
            if (missing.isNotEmpty()) add("Berechtigungen fehlen: ${missing.joinToString { it.substringAfterLast('.') }}")
            if (!st.bluetoothOn) add("Bluetooth ist aus.")
            if (!CaptureHub.locationServiceOn()) add("Standortdienst ist aus. Android liefert dann oft keine Scan-Ergebnisse.")
            if (st.demo && st.running) add("Demo-Sitzung: erfundene Pakete, kein Beweis für echte Busse.")
        }
        warnings.forEach { Warning(it, error = !it.startsWith("Demo")) }
        if (st.running && st.mirror != null) Text(st.mirror!!, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = if (st.mirror!!.contains("gestört") || st.mirror!!.contains("nicht")) scheme.error else scheme.onSurfaceVariant)
        if (st.running) Text("Bildschirm anlassen: Hintergrundempfang ist nicht getestet.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun Warning(text: String, error: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (error) scheme.errorContainer else scheme.tertiaryContainer).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp), tint = if (error) scheme.onErrorContainer else scheme.onTertiaryContainer)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (error) scheme.onErrorContainer else scheme.onTertiaryContainer)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, bad: Boolean = false) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, maxLines = 1, color = if (bad) MaterialTheme.colorScheme.error else Color.Unspecified)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lineFolders(profiles: List<BusProfile>, now: Long, onBus: (String) -> Unit, byStop: Boolean) {
    if (profiles.isEmpty()) { item { MessageState(Icons.Rounded.DirectionsBus, "Noch keine Busse", "Starte eine Aufnahme oder lade Demo-Daten.") }; return }
    data class Entry(val bus: BusProfile, val run: Run, val count: Int)
    val groups = linkedMapOf<String, MutableList<Entry>>()
    val labels = mutableMapOf<String, Pair<String, Run>>()
    profiles.forEach { p ->
        val perKey = p.runs.groupBy { r -> if (byStop) "s${r.nextStopId}|${r.nextStop}" else "l${r.lineId}|${r.line}" }
        perKey.forEach { (k, runs) ->
            val last = runs.last()
            groups.getOrPut(k) { mutableListOf() } += Entry(p, last, runs.sumOf { it.count })
            if (k !in labels) labels[k] = (if (byStop) stopLabel(last.nextStopId, last.nextStop) else lineLabel(last.lineId, last.line)) to last
        }
    }
    val order = groups.keys.sortedWith(compareBy({ labels[it]!!.first.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { labels[it]!!.first }))
    items(order, key = { (if (byStop) "stop:" else "line:") + it }) { k ->
        val (label, run) = labels[k]!!
        val entries = groups[k]!!
        FolderCard(
            key = (if (byStop) "stop:" else "line:") + k,
            leading = { if (!byStop) LineBadge(lineBadgeText(run), Mode.Bus) },
            title = if (!byStop && run.lineId != null && run.line != null) "Linie $label" else label,
            subtitle = "${entries.size} ${if (entries.size == 1) "Bus" else "Busse"} · ${entries.sumOf { it.count }} Pakete",
        ) {
            entries.sortedByDescending { it.bus.lastMs }.forEach { e ->
                BusRow(e.bus, now, onBus, run = e.run, packets = e.count, showLine = byStop)
            }
        }
    }
}

@Composable
internal fun BusRow(p: BusProfile, now: Long, onBus: (String) -> Unit, run: Run? = null, packets: Int = p.packets, showLine: Boolean = true) {
    val r = run ?: p.current
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p.test) Pill("Demo", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                if (p.failures > 0) Pill("${p.failures}× Fehler", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        supportingContent = {
            Column {
                Mono(p.shortAddress, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (r != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (showLine) LineBadge(lineBadgeText(r), Mode.Bus)
                    Text("${destinationLabel(r.destinationId, r.destination)} · ${stopLabel(r.nextStopId, r.nextStop)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${ago(now, p.lastMs)} · $packets Pakete · ${p.rssiMax} dBm", style = MaterialTheme.typography.labelSmall)
            }
        },
        leadingContent = { Icon(Icons.Rounded.DirectionsBus, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ItemColors,
        modifier = Modifier.clickable { onBus(p.id) }.testTag("bus_${p.id}"),
    )
}

@Composable
internal fun OpRow(op: Op, now: Long, bus: String?, onBus: (String) -> Unit) {
    var open by rememberSaveable(op.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (op.status) {
        "ok" -> Icons.Rounded.CheckCircle to scheme.primary
        "running" -> Icons.Rounded.Schedule to scheme.onSurfaceVariant
        "blocked" -> Icons.Rounded.Lock to scheme.tertiary
        else -> Icons.Rounded.Error to scheme.error
    }
    ListItem(
        headlineContent = { Text(op.title, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(Modifier.animateContentSize()) {
                Text(
                    "${statusLabel(op.status)} · ${clockTime(op.startedMs)}" + (op.finishedMs?.let { " · ${it - op.startedMs} ms" } ?: " · ${ago(now, op.startedMs)}"),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (open) {
                    if (op.detail.isNotBlank()) Text(op.detail, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                    Mono("Operation ${op.id} · ${op.kind}", Modifier.padding(top = 4.dp), color = scheme.onSurfaceVariant)
                    if (op.profileId != null && bus != null) TextButton({ onBus(op.profileId) }) { Text("Zu $bus") }
                }
            }
        },
        leadingContent = { Icon(if (op.kind == "network") Icons.Rounded.Cloud else Icons.Rounded.Bluetooth, null, tint = scheme.primary) },
        trailingContent = { Icon(icon, statusLabel(op.status), tint = tint) },
        colors = ItemColors,
        modifier = Modifier.clickable { open = !open },
    )
}

internal fun statusLabel(s: String) = when (s) {
    "ok" -> "Erfolgreich"; "running" -> "Läuft"; "blocked" -> "Blockiert"; "timeout" -> "Zeitüberschreitung"
    "cancelled" -> "Abgebrochen"; else -> "Fehler"
}

@Composable
private fun SessionCard(m: SessionMeta, st: CaptureState, onExport: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val live = st.running && st.sessionId == m.sessionId
    var confirmDelete by remember { mutableStateOf(false) }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mono(m.sessionId, Modifier.weight(1f), maxLines = 1)
            if (m.demo) Pill("Demo", scheme.tertiaryContainer, scheme.onTertiaryContainer)
            when (if (live) "running" else m.state) {
                "completed" -> Pill("Vollständig", scheme.primaryContainer, scheme.onPrimaryContainer)
                "running" -> Pill("Läuft", scheme.primary, scheme.onPrimary)
                else -> Pill(if (m.state == "interrupted") "Unterbrochen" else "Unvollständig", scheme.errorContainer, scheme.onErrorContainer)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${m.advertisements} Pakete · ${m.events} Ereignisse · ${fileSize(m.bytes)}" + if (m.dropped > 0) " · ${m.dropped} verworfen" else "",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
        Text("${m.device} · Android ${m.androidSdk}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        m.resumedFrom?.let { Text("Folgt auf unterbrochene Sitzung $it", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ CaptureHub.loadSession(m.sessionId) }, enabled = !st.running && st.viewingId != m.sessionId) {
                Text(if (st.viewingId == m.sessionId) "Angezeigt" else "Ansehen")
            }
            TextButton(onExport) { Text("Export") }
            TextButton({ confirmDelete = true }, enabled = !live) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.size(4.dp)); Text("Löschen") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Sitzung löschen?") },
        text = { Text("events.jsonl und alle Profile dieser Sitzung werden unwiderruflich gelöscht. Exportiere vorher, wenn du sie behalten willst.") },
        confirmButton = { TextButton({ CaptureHub.deleteSession(m.sessionId); confirmDelete = false }) { Text("Löschen") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Abbrechen") } },
    )
}

@Composable
private fun CaptureSettings(st: CaptureState, sessionCount: Int, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var confirm by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    val size = remember(sessionCount, st.running) { CaptureHub.storageBytes() }
    val idle = !st.running
    @Composable
    fun Row(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, enabled: Boolean = idle, danger: Boolean = false, onClick: () -> Unit) = ListItem(
        headlineContent = { Text(title, color = if (danger && enabled) MaterialTheme.colorScheme.error else Color.Unspecified) },
        supportingContent = { Text(sub) },
        leadingContent = { Icon(icon, null, tint = if (danger && enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        colors = ItemColors,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Einstellungen", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "$sessionCount Sitzungen · ${fileSize(size)} belegt" + if (st.running) " · Während der Aufnahme gesperrt" else "",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionLabel("Ansicht")
            Row(Icons.Rounded.VisibilityOff, "Ansicht leeren", "Zeigt nichts an, löscht aber keine Sitzung") { CaptureHub.clearView(); onDismiss() }
            SectionLabel("Sitzungen")
            Row(Icons.Rounded.Science, "Demo-Sitzungen löschen", "Nur erfundene Testdaten", enabled = idle && sessionCount > 0) {
                confirm = Triple("Demo-Sitzungen löschen?", "Echte Aufnahmen bleiben erhalten.") { CaptureHub.clearSessions(demoOnly = true); onDone("Demo-Sitzungen gelöscht") }
            }
            Row(Icons.Rounded.Delete, "Alle Sitzungen löschen", "Logs und Profile unwiderruflich entfernen", enabled = idle && sessionCount > 0, danger = true) {
                confirm = Triple("Alle Sitzungen löschen?", "Alle events.jsonl und Profile gehen verloren. Exportiere vorher, was du behalten willst.") { CaptureHub.clearSessions(demoOnly = false); onDone("Alle Sitzungen gelöscht") }
            }
            SectionLabel("Daten & Einwilligung")
            var mirror by remember { mutableStateOf(CaptureHub.mirrorEnabled()) }
            ListItem(
                headlineContent = { Text("Auto-Kopie in Downloads") },
                supportingContent = { Text("Schreibt jedes Ereignis sofort auch nach Download/Brunel und legt beim Stopp ein ZIP ab") },
                leadingContent = { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Switch(mirror, { mirror = it; CaptureHub.setMirror(it) }, enabled = idle) },
                colors = ItemColors,
            )
            Row(Icons.Rounded.Download, "Exporte löschen", "Geteilte Dateien und Download/Brunel", enabled = true) { CaptureHub.clearExports(); onDone("Exporte gelöscht") }
            Row(Icons.Rounded.Sync, "Namenstabelle löschen", "Linien und Halte werden bis zum nächsten Laden nicht aufgelöst", enabled = idle) { CaptureHub.clearNames(); onDone("Namenstabelle gelöscht") }
            Row(Icons.Rounded.Lock, "Einwilligung zurücksetzen", "Die Frage zu Bluetooth-Adressen kommt beim nächsten Start wieder", enabled = true) { CaptureHub.resetConsent(); onDone("Einwilligung zurückgesetzt") }
        }
    }
    confirm?.let { (title, text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton({ action(); confirm = null; onDismiss() }, modifier = Modifier.testTag("settings_confirm")) { Text("Löschen") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Abbrechen") } },
        )
    }
}
