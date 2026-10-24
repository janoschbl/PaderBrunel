package dev.padermodern.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.padermodern.data.Phase
import dev.padermodern.data.Route
import dev.padermodern.data.TripStatus
import dev.padermodern.ui.theme.LocalTransitColors

@Composable
fun TripBar(route: Route, status: TripStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tc = LocalTransitColors.current
    val leg = route.partialRoutes.getOrNull(status.legIndex)
    val broken = status.broken
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = if (broken) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (broken) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag("trip_bar"),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leg != null && !leg.isWalk) {
                LineBadge(leg.shortName, leg.mode())
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (broken) "Verbindung nicht mehr fahrbar" else status.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = if (broken) status.brokenText else status.detail
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Fahrt öffnen", Modifier.size(24.dp))
        }
    }
}

fun TripStatus.worthShowing() = phase != Phase.Arrived || broken
