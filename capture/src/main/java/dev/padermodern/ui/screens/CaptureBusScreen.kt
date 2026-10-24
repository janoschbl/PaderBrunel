package dev.padermodern.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.padermodern.capture.BusProfile
import dev.padermodern.capture.BusAction
import dev.padermodern.capture.CaptureHub
import dev.padermodern.capture.RawAd
import dev.padermodern.capture.destinationLabel
import dev.padermodern.capture.resultLabel
import dev.padermodern.capture.stopLabel
import dev.padermodern.ui.components.Fact
import dev.padermodern.ui.components.InfoCard
import dev.padermodern.ui.components.LineBadge
import dev.padermodern.ui.components.MessageState
import dev.padermodern.ui.components.Mode
import dev.padermodern.ui.components.Mono
import dev.padermodern.ui.components.PageHeader
import dev.padermodern.ui.components.Pill
import dev.padermodern.ui.components.Segmented
import dev.padermodern.ui.components.SectionLabel
import dev.padermodern.ui.components.StatusBarScrim
import dev.padermodern.ui.components.clockTime
import dev.padermodern.ui.components.spacedHex

private enum class BusTab(val label: String) { Info("Info"), Packets("Pakete"), Requests("Anfragen"), Gatt("GATT"), Notes("Notizen") }
private enum class PacketFilter(val label: String) { All("Alle"), Ok("OK"), Failed("Fehler") }

private val ItemColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun CaptureBusScreen(profileId: String, onBack: () -> Unit, onBus: (String) -> Unit) {
    val snap by CaptureHub.snapshot.collectAsState()
    val st by CaptureHub.state.collectAsState()
    val now = rememberSecond()
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf(0) }
    var inspectDialog by remember { mutableStateOf(false) }
    var noteDialog by remember { mutableStateOf(false) }
    var authorized by remember(profileId, st.sessionId) { mutableStateOf(false) }
    var confirmAction by remember(profileId) { mutableStateOf<BusAction?>(null) }
    val bus = snap.profiles.firstOrNull { it.id == profileId }
    val current = BusTab.entries[tab]

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("capture_bus_list"), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { PageHeader(bus?.title ?: "Bus", bus?.shortAddress, onBack = onBack) }
            if (bus == null) {
                item { MessageState(Icons.Rounded.SearchOff, "Profil nicht gefunden", "Dieses Profil gehört zu einer anderen Sitzung.") }
                return@LazyColumn
            }
            item {
                Segmented(BusTab.entries, current, { it.label }, { tab = it.ordinal }, { "bus_tab_${it.name}" })
                Spacer(Modifier.size(8.dp))
            }
            when (current) {
                BusTab.Info -> {
                    item { Overview(bus, snap.profiles, onBus) }
                    item { BusActions(bus, now, authorized) { action ->
                        if (bus.test || st.demo || authorized) CaptureHub.requestBusAction(bus.id, action, authorized)
                        else confirmAction = action
                    } }
                    item { SectionLabel("Verlauf") }
                    items(bus.runs.asReversed(), key = { "${it.firstMs}-${it.lastMs}-${it.nextStopId}-${it.course}" }) { r ->
                        ListItem(
                            headlineContent = { Text("${destinationLabel(r.destinationId, r.destination)} · ${stopLabel(r.nextStopId, r.nextStop)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("Kurs ${r.course ?: "?"} · ${r.count}× · ${clockTime(r.firstMs)}–${clockTime(r.lastMs)}") },
                            leadingContent = { LineBadge(lineBadgeText(r), Mode.Bus) },
                            colors = ItemColors,
                        )
                    }
                    item {
                        Button(
                            onClick = { inspectDialog = true },
                            enabled = st.running && st.inspecting == null,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth().testTag("bus_inspect"),
                        ) {
                            Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                            Text(if (st.inspecting != null) "Untersuchung läuft …" else "Bus untersuchen (nur Lesen)")
                        }
                        if (!st.running) Text("Nur während einer Aufnahme möglich, damit das Ergebnis im Log landet.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                BusTab.Packets -> {
                    item {
                        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PacketFilter.entries.forEach { f ->
                                FilterChip(filter == f.ordinal, { filter = f.ordinal }, { Text(f.label) }, Modifier.testTag("packet_filter_${f.name}"))
                            }
                        }
                    }
                    val shown = bus.ads.filter { when (PacketFilter.entries[filter]) { PacketFilter.All -> true; PacketFilter.Ok -> it.decoded.ok; PacketFilter.Failed -> !it.decoded.ok } }
                    item {
                        Text(
                            "${shown.size} von ${bus.packets} Paketen" + if (bus.packets > bus.ads.size) " (nur die letzten ${bus.ads.size} im Speicher, das Log enthält alle)" else "",
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (shown.isEmpty()) item { MessageState(Icons.Rounded.SearchOff, "Keine Pakete", "Für diesen Filter gibt es nichts.") }
                    items(shown, key = { "${it.receivedMs}-${it.hash}-${it.rssi}-${it.scanTimestampNanos}" }) { PacketCard(it) }
                }
                BusTab.Requests -> {
                    item { BusActions(bus, now, authorized) { action ->
                        if (bus.test || st.demo || authorized) CaptureHub.requestBusAction(bus.id, action, authorized)
                        else confirmAction = action
                    } }
                    val ops = snap.ops.filter { it.profileId == bus.id }
                    if (ops.isEmpty()) item { MessageState(Icons.Rounded.Bluetooth, "Keine Anfragen", "HTTPS-Aktionen und Lesezugriffe erscheinen hier. Tippe einen Eintrag für die Details an.") }
                    items(ops, key = { it.id }) { OpRow(it, now, null, onBus) }
                }
                BusTab.Gatt -> {
                    if (bus.services.isEmpty() && bus.reads.isEmpty()) item { MessageState(Icons.Rounded.Bluetooth, "Nichts entdeckt", "Starte unter Info eine Untersuchung. Sie liest nur und schreibt nie.") }
                    items(bus.services, key = { it.uuid }) { s ->
                        InfoCard {
                            Mono(s.uuid, color = MaterialTheme.colorScheme.primary)
                            s.characteristics.forEach { Mono(it, Modifier.padding(top = 6.dp)) }
                        }
                    }
                    if (bus.reads.isNotEmpty()) item { SectionLabel("Lesezugriffe") }
                    items(bus.reads, key = { it.ms }) { r ->
                        InfoCard {
                            Row { Mono(r.characteristic.take(8), Modifier.weight(1f)); Pill(if (r.error == null) "Status ${r.status}" else "Fehler", if (r.error == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer, if (r.error == null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer) }
                            Mono(r.bytes?.let(::spacedHex) ?: r.error.orEmpty(), Modifier.padding(top = 6.dp))
                            Text(clockTime(r.ms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                BusTab.Notes -> {
                    item {
                        FilledTonalButton({ noteDialog = true }, enabled = st.running, modifier = Modifier.padding(horizontal = 16.dp).testTag("bus_note")) {
                            Icon(Icons.Rounded.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Notiz zu diesem Bus")
                        }
                    }
                    val notes = snap.notes.filter { it.profileId == bus.id }
                    if (notes.isEmpty()) item { MessageState(Icons.Rounded.NoteAdd, "Keine Notizen", "Notiere z. B. Linie und Ziel auf dem Display, damit du sie später mit den Funkdaten vergleichen kannst.") }
                    items(notes, key = { it.ms }) { n ->
                        ListItem(headlineContent = { Text(n.text) }, supportingContent = { Text(clockTime(n.ms)) }, colors = ItemColors)
                    }
                }
            }
        }
        StatusBarScrim()
    }

    if (inspectDialog && bus != null) AlertDialog(
        onDismissRequest = { inspectDialog = false },
        title = { Text("Bus untersuchen?") },
        text = {
            Text(
                "Die App verbindet sich mit ${bus.shortAddress}, listet Services auf, liest höchstens die Fahrzeugdaten (0x2020) und trennt wieder. " +
                    "Sie schreibt nichts und koppelt nicht. Auch Lesen belegt das Fahrzeug kurz. Nur an einem erlaubten Testfahrzeug ausführen.",
            )
        },
        confirmButton = { TextButton({ CaptureHub.inspect(bus.id, bus.shortAddress); inspectDialog = false }, modifier = Modifier.testTag("bus_inspect_confirm")) { Text("Untersuchen") } },
        dismissButton = { TextButton({ inspectDialog = false }) { Text("Abbrechen") } },
    )
    if (noteDialog && bus != null) NoteDialog("Notiz zu ${bus.title}", "z. B. Display zeigt Linie 4 nach Elsen", { noteDialog = false }) { CaptureHub.addNote(it, bus.id) }
    confirmAction?.let { action ->
        if (bus != null) AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text("${action.label} senden?") },
            text = { Text("An ${bus.title} · ${bus.shortAddress}\n\nPOST ${action.url}\n\nDas kann einen echten Fahrzeughinweis auslösen. Nur mit Erlaubnis am Testfahrzeug nutzen. Die Bluetooth-Adresse wird an ivanto übermittelt.\n\nMit der Bestätigung werden weitere Aktionen für diesen Bus in dieser Ansicht mit einem Tippen gesendet. Anfrage und Antwort landen im Aufnahmelog.") },
            confirmButton = { TextButton({
                authorized = true
                CaptureHub.requestBusAction(bus.id, action, authorized = true)
                confirmAction = null
            }, Modifier.testTag("bus_action_confirm")) { Text("Erlaubt · senden") } },
            dismissButton = { TextButton({ confirmAction = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun BusActions(bus: BusProfile, now: Long, authorized: Boolean, onAction: (BusAction) -> Unit) {
    val st by CaptureHub.state.collectAsState()
    val snap by CaptureHub.snapshot.collectAsState()
    val ad = bus.ads.firstOrNull()
    val demo = bus.test || st.demo
    val active = st.running && st.viewingId == st.sessionId
    val fresh = ad != null && now - ad.receivedMs in 0..30_000
    InfoCard {
        Text(if (demo) "Fahrzeugaktionen · Demo" else "Fahrzeugaktionen · HTTPS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            when {
                !active -> "Starte eine Aufnahme und öffne ein neu gefundenes Profil, damit die Anfrage protokolliert wird."
                !demo && !fresh -> "Bus seit über 30 Sekunden nicht gesehen. Warte auf ein neues Paket."
                demo -> "Simuliert: keine Daten werden gesendet. Die Ergebnisse stehen unter Anfragen."
                authorized -> "Ein Tippen sendet live an ${ad?.address}. Nach jeder Antwort oder jedem Fehler kannst du erneut senden. Jeder Versuch wird protokolliert."
                else -> "Beim ersten Senden: Erlaubnis bestätigen. Danach reicht ein Tippen für diesen Bus."
            }, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BusAction.entries.forEach { action ->
            val supported = action.supported(ad)
            FilledTonalButton(
                onClick = { onAction(action) },
                enabled = active && st.error == null && supported && (demo || (fresh && ad?.decoded?.simulator == false)) &&
                    st.requesting == null && !st.finishing,
                modifier = Modifier.fillMaxWidth().testTag("bus_action_${action.name}"),
            ) { Text(action.label + if (!supported) " · nicht angeboten" else "") }
        }
        if (st.requesting != null) Text("Anfrage läuft …", style = MaterialTheme.typography.bodySmall)
        snap.ops.firstOrNull { it.profileId == bus.id && it.kind == "network" }?.let { op ->
            Text("${op.title}: ${statusLabel(op.status)} · Details unter Anfragen",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = if (op.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Text("POST /busaccess/{Aktion} · btMac\nManuell beliebig oft erneut senden, immer eine Anfrage gleichzeitig. Bei einem Timeout ist die Zustellung unbekannt. Eine Serverbestätigung beweist kein Signal am Fahrzeug.",
            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Overview(b: BusProfile, all: List<BusProfile>, onBus: (String) -> Unit) {
    InfoCard {
        Fact("Profil-ID", b.id, mono = true)
        Fact(if (b.addresses.size > 1) "Adressen" else "Adresse", b.addresses.joinToString("\n"), mono = true)
        Fact("Hardware-ID", b.hardwareIds.joinToString().ifEmpty { "unbekannt" })
        Fact("Protokoll", b.protocols.joinToString().ifEmpty { "unbekannt" })
        Fact("Firma", b.companies.joinToString().ifEmpty { "unbekannt" })
        Fact("Pakete", "${b.packets}" + if (b.failures > 0) " · ${b.failures} nicht dekodierbar" else "")
        Fact("Erste Sichtung", clockTime(b.firstMs))
        Fact("Letzte Sichtung", clockTime(b.lastMs))
        Fact("Signal", "${b.rssiMin} bis ${b.rssiMax} dBm")
        if (b.test) Fact("Herkunft", "Demo-Daten, kein echter Bus")
    }
    if (b.related.isNotEmpty()) InfoCard {
        Text("Möglicherweise derselbe Bus", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Gleiche Hardware-ID, andere Adresse. Das bleibt eine Vermutung, die Profile sind nicht zusammengeführt.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp),
        )
        b.related.forEach { id ->
            val other = all.firstOrNull { it.id == id }
            TextButton({ onBus(id) }) { Text(other?.let { "${it.title} · ${it.shortAddress}" } ?: id) }
        }
    }
}

@Composable
private fun PacketCard(ad: RawAd) {
    var open by rememberSaveable("${ad.receivedMs}-${ad.hash}-${ad.rssi}") { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val d = ad.decoded
    Card(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize().testTag("packet_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono(clockTime(ad.receivedMs), Modifier.weight(1f))
                Text("${ad.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                if (d.ok) Pill(d.resultLabel, scheme.primaryContainer, scheme.onPrimaryContainer) else Pill(d.resultLabel, scheme.errorContainer, scheme.onErrorContainer)
            }
            Text(
                if (d.ok) "${lineLabelOf(ad)} → ${destinationLabel(d.destinationId, ad.destination)} · ${stopLabel(d.nextStopId, ad.nextStop)}" else d.error ?: d.resultLabel,
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            )
            if (open) {
                Spacer(Modifier.size(8.dp))
                Mono(spacedHex(ad.bytes), color = scheme.primary)
                Spacer(Modifier.size(8.dp))
                Fact("Länge", "${ad.length} Byte · Hash ${ad.hash}")
                Fact("Auswahl", ad.candidate)
                d.protocol?.let { Fact("Protokoll", "$it · Firma ${d.company ?: "?"}") }
                d.flags?.let { Fact("Service-Flags", "0x%02x".format(it)) }
                d.lineId?.let { Fact("IDs", "Linie $it · Ziel ${d.destinationId} · Halt ${d.nextStopId}") }
                d.hardwareId?.let { Fact("Hardware / Kurs", "$it / ${d.course}") }
                d.unknownHex?.let { Fact("Restbytes", spacedHex(it), mono = true) }
                if (ad.serviceUuids.isNotEmpty()) Fact("Service-UUIDs", ad.serviceUuids.joinToString("\n"), mono = true)
                if (ad.manufacturer.isNotEmpty()) Fact("Hersteller", ad.manufacturer.entries.joinToString("\n") { "${it.key}: ${spacedHex(it.value)}" }, mono = true)
                if (ad.serviceData.isNotEmpty()) Fact("Service-Daten", ad.serviceData.entries.joinToString("\n") { "${it.key.take(8)}: ${spacedHex(it.value)}" }, mono = true)
                Fact("Funk", listOfNotNull(ad.connectable?.let { if (it) "verbindbar" else "nicht verbindbar" }, ad.legacy?.let { if (it) "legacy" else "erweitert" }, ad.primaryPhy?.let { "PHY $it/${ad.secondaryPhy}" }, ad.txPower?.let { "Tx $it dBm" }).joinToString(" · ").ifEmpty { "–" })
                d.error?.let { Fact("Fehler", it) }
            }
        }
    }
}

private fun lineLabelOf(ad: RawAd) = ad.line ?: ad.decoded.lineId?.let { "Linie #$it" } ?: "Unbekannte Linie"
