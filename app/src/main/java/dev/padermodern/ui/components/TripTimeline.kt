package dev.padermodern.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.padermodern.data.PartialRoute
import dev.padermodern.data.Route
import dev.padermodern.data.Station
import dev.padermodern.data.TripStatus
import dev.padermodern.data.durationLabel
import dev.padermodern.data.effective
import dev.padermodern.data.parseTime
import dev.padermodern.ui.theme.LocalTransitColors
import dev.padermodern.ui.theme.onInverse
import java.time.Duration

private val DOT_Y = 20.dp

@Composable
fun TripTimeline(
    route: Route,
    onStation: (Station) -> Unit,
    modifier: Modifier = Modifier,
    status: TripStatus? = null,
) {
    val parts = route.partialRoutes
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        parts.forEachIndexed { i, leg ->
            val prev = parts.getOrNull(i - 1)
            val next = parts.getOrNull(i + 1)
            val current = status != null && status.phase != dev.padermodern.data.Phase.Arrived && status.legIndex == i
            val past = status != null && (status.phase == dev.padermodern.data.Phase.Arrived || i < status.legIndex) && !current
            val block: @Composable () -> Unit = {
                if (leg.isWalk) {
                    WalkBlock(leg, first = i == 0, last = i == parts.lastIndex, inverse = false, onStation = onStation)
                } else {
                    RideBlock(
                        leg,
                        topLine = if (prev == null) RailLine.None else RailLine.Dotted,
                        bottomLine = if (next == null) RailLine.None else RailLine.Dotted,
                        inverse = false,
                        onStation = onStation,
                    )
                }
            }
            if (current) {
                val bg = MaterialTheme.colorScheme.secondaryContainer
                androidx.compose.runtime.CompositionLocalProvider(LocalRailBg provides bg) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = bg,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) { Column(Modifier.padding(vertical = 8.dp)) { block() } }
                }
            } else {
                Column(Modifier.alpha(if (past) 0.5f else 1f)) { block() }
            }
            if (!leg.isWalk && next != null && !next.isWalk) {
                TransferRow(leg, next, status != null && status.broken && status.brokenText?.contains(next.shortName.orEmpty()) == true)
            }
        }
    }
}

private fun gapMinutes(a: PartialRoute, b: PartialRoute): Long? {
    val arr = parseTime(a.expectedArrivalDate ?: a.stations.lastOrNull()?.expectedDate ?: a.arrivalDate) ?: return null
    val dep = parseTime(b.expectedDepartureDate ?: b.stations.firstOrNull()?.expectedDate ?: b.departureDate) ?: return null
    return Duration.between(arr, dep).toMinutes()
}

@Composable
private fun TransferRow(a: PartialRoute, b: PartialRoute, broken: Boolean) {
    val tc = LocalTransitColors.current
    val gap = gapMinutes(a, b)
    val tight = gap != null && gap < 3
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cell(Modifier.width(60.dp)) {
            Text(
                gap?.let { "$it min" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (broken) tc.late else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TimelineRail(RailLine.Dotted, RailLine.Dotted, MaterialTheme.colorScheme.outline)
        Row(Modifier.padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(20.dp), tint = if (broken || tight) tc.late else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                if (broken) "Umstieg nicht mehr möglich" else "Umstieg" + if (tight) " · knapp" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (broken || tight) tc.late else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (broken) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun Cell(modifier: Modifier, content: @Composable () -> Unit) =
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.CenterStart) { content() }

@Composable
private fun WalkBlock(leg: PartialRoute, first: Boolean, last: Boolean, inverse: Boolean, onStation: (Station) -> Unit) {
    val minutes = leg.transferTime ?: 0
    val line = MaterialTheme.colorScheme.outline
    Column {
        if (first) {
            leg.stations.firstOrNull()?.let { StopRow(it, RailLine.None, RailLine.Dotted, line, bold = true, inverse = inverse, hollow = true, onStation = onStation) }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cell(Modifier.width(60.dp)) {
                if (minutes > 0) Text("$minutes min", style = MaterialTheme.typography.bodyMedium, color = LocalContentColorOrVariant(inverse))
            }
            TimelineRail(RailLine.Dotted, RailLine.Dotted, line)
            Row(Modifier.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(20.dp), tint = LocalContentColorOrVariant(inverse))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Fußweg" + (leg.stations.lastOrNull()?.name?.takeIf { last }?.let { " zu $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColorOrVariant(inverse),
                )
            }
        }
        if (last) {
            leg.stations.lastOrNull()?.let { StopRow(it, RailLine.Dotted, RailLine.None, line, bold = true, inverse = inverse, pin = true, onStation = onStation) }
        }
    }
}

@Composable
private fun LocalContentColorOrVariant(inverse: Boolean): Color =
    if (inverse) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun RideBlock(leg: PartialRoute, topLine: RailLine, bottomLine: RailLine, inverse: Boolean, onStation: (Station) -> Unit) {
    var expanded by remember(leg.departureDate, leg.shortName) { mutableStateOf(false) }
    val stops = leg.stations
    val base = legColor(leg)
    val color = if (inverse) MaterialTheme.colorScheme.inversePrimary else base
    val warnings = leg.warningsNew.filter { !it.title.isNullOrBlank() }.distinctBy { it.title }
    val between = stops.drop(1).dropLast(1)
    val warnColor = LocalTransitColors.current.let { if (inverse) it.onInverse().late else it.late }
    Column(Modifier.animateContentSize()) {
        stops.firstOrNull()?.let { StopRow(it, topLine, RailLine.Solid, color, bold = true, inverse = inverse, onStation = onStation) }

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp)) {
            Cell(Modifier.width(60.dp).padding(top = 4.dp)) {
                Text(
                    durationLabel(leg.departureDate, leg.arrivalDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColorOrVariant(inverse),
                )
            }
            TimelineRail(RailLine.Solid, RailLine.Solid, color)
            Column(Modifier.weight(1f).padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(leg.shortName, leg.mode(), large = true)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        leg.lineDirection?.let { "nach $it" } ?: leg.lineName.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
                warnings.forEach { n ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = warnColor)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            n.title.orEmpty().removePrefix("Linie ${leg.shortName}: "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = warnColor,
                        )
                    }
                }
                if (between.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 6.dp).clickable { expanded = !expanded }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = if (inverse) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (expanded) "Zwischenhalte ausblenden" else if (between.size == 1) "1 Zwischenhalt" else "${between.size} Zwischenhalte",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (inverse) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (expanded) between.forEach { StopRow(it, RailLine.Solid, RailLine.Solid, color, bold = false, small = true, inverse = inverse, onStation = onStation) }

        stops.lastOrNull()?.takeIf { stops.size > 1 }?.let { StopRow(it, RailLine.Solid, bottomLine, color, bold = true, inverse = inverse, onStation = onStation) }
    }
}

@Composable
private fun StopRow(
    station: Station,
    top: RailLine,
    bottom: RailLine,
    color: Color,
    bold: Boolean,
    inverse: Boolean,
    small: Boolean = false,
    hollow: Boolean = false,
    pin: Boolean = false,
    onStation: (Station) -> Unit,
) {
    val vPad = if (small) 5.dp else 8.dp
    val dotY = if (small) 14.dp else DOT_Y
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 8.dp)
            .then(if (station.isRealStop) Modifier.clickable { onStation(station) } else Modifier),
    ) {
        Cell(Modifier.width(60.dp).padding(top = vPad)) {
            androidx.compose.foundation.layout.Column {
                TimeWithDelay(station.date, station.expectedDate, station.canceled, inverse = inverse, small = small)
            }
        }
        TimelineRail(
            top, bottom, color, dotY = dotY,
            dotRadius = if (small) 4.dp else if (bold) 7.dp else 6.dp,
            filledDot = !(small || hollow),
            dotBackground = LocalRailBg.current ?: MaterialTheme.colorScheme.surface,
        )
        Row(Modifier.weight(1f).padding(start = 8.dp, top = vPad, bottom = vPad), verticalAlignment = Alignment.Top) {
            Text(
                station.name.orEmpty(),
                style = if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (station.canceled) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            if (!small) PlatformChip(station.platform)
        }
    }
}
