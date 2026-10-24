package dev.padermodern.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.padermodern.data.Departure
import dev.padermodern.data.PartialRoute
import dev.padermodern.data.delayMinutes
import dev.padermodern.data.hhmm
import dev.padermodern.ui.theme.LocalTransitColors
import dev.padermodern.ui.theme.ModeColors
import dev.padermodern.ui.theme.onInverse

enum class Mode { Bus, RegionalBus, Train, SBahn }

fun modeOf(type: Int, lineName: String?, shortName: String?, rideType: String? = null): Mode {
    val n = lineName.orEmpty().lowercase()
    return when {
        // check first, regional buses also match the s-bahn pattern
        n.contains("regionalbus") -> Mode.RegionalBus
        type == 2 || n.contains("s-bahn") || shortName?.matches(Regex("S\\d+")) == true -> Mode.SBahn
        type == 6 || rideType == "TRAIN" || n.contains("zug") -> Mode.Train
        n.contains("regionalbus") || shortName?.matches(Regex("[RT]\\d+.*")) == true -> Mode.RegionalBus
        else -> Mode.Bus
    }
}

@Composable
fun legColor(leg: PartialRoute): Color = modeColors(leg.mode()).container

fun Departure.mode() = modeOf(type, lineName, shortName)
fun PartialRoute.mode() = modeOf(type, lineName ?: typeName, shortName, vehicleRideType)

@Composable
fun modeColors(mode: Mode): ModeColors {
    val c = LocalTransitColors.current
    return when (mode) {
        Mode.Bus -> c.bus
        Mode.RegionalBus -> c.regionalBus
        Mode.Train -> c.train
        Mode.SBahn -> c.sBahn
    }
}

fun modeIcon(mode: Mode): ImageVector = when (mode) {
    Mode.Train, Mode.SBahn -> Icons.Rounded.Train
    else -> Icons.Rounded.DirectionsBus
}

@Composable
fun LineBadge(name: String?, mode: Mode, modifier: Modifier = Modifier, large: Boolean = false) {
    val label = name?.trim().orEmpty().ifEmpty { "?" }
    val isReplacementBus = label == "SEV" || label.startsWith("SEV ")
    val colors = modeColors(mode)
    val shape = when (mode) {
        Mode.Train, Mode.SBahn -> RoundedCornerShape(6.dp)
        else -> RoundedCornerShape(50)
    }
    Box(
        modifier
            .wrapContentWidth()
            .defaultMinSize(minWidth = if (large) 56.dp else 44.dp)
            .clip(shape)
            .background(colors.container)
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 6.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (isReplacementBus) "SEV" else label,
            color = colors.content,
            fontWeight = FontWeight.ExtraBold,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
fun WalkChip(minutes: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Rounded.DirectionsWalk, null,
            Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (minutes != null) {
            Text("$minutes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TimeWithDelay(
    planned: String?,
    expected: String?,
    canceled: Boolean = false,
    big: Boolean = false,
    horizontal: Boolean = false,
    inverse: Boolean = false,
    small: Boolean = false,
) {
    val tc = LocalTransitColors.current.let { if (inverse) it.onInverse() else it }
    val delay = delayMinutes(planned, expected)
    val style = if (big) MaterialTheme.typography.titleLarge else if (small) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    val content: @Composable () -> Unit = {
        Text(
            planned.hhmm(),
            style = style,
            fontWeight = FontWeight.Bold,
            textDecoration = if (canceled) TextDecoration.LineThrough else null,
        )
        if (delay != null && !canceled) {
            Text(
                expected.hhmm(),
                style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (delay >= 3) tc.late else tc.onTime,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
    if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) { content() }
    else Column { content() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ContainedLoadingIndicator(Modifier.size(64.dp))
        if (label != null) {
            Spacer(Modifier.height(16.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(container),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(44.dp), tint = content) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) = MessageState(
    icon = Icons.Rounded.CloudOff,
    title = "Laden fehlgeschlagen",
    subtitle = message,
    actionLabel = "Erneut versuchen",
    onAction = onRetry,
    container = MaterialTheme.colorScheme.errorContainer,
    content = MaterialTheme.colorScheme.onErrorContainer,
)

fun countdown(minutes: Long?, time: String?): String = when {
    minutes == null -> time.hhmm()
    minutes <= 0 -> "Jetzt"
    minutes < 60 -> "$minutes min"
    else -> time.hhmm()
}

private val PageHeaderHeight = 120.dp

@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    subtitle: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(PageHeaderHeight).padding(start = if (onBack != null) 8.dp else 20.dp, end = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, Modifier.padding(top = 20.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") }
        }
        Column(Modifier.weight(1f).padding(top = 24.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            if (subtitle != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium, subtitle)
            }
        }
        Row(Modifier.padding(top = 20.dp), content = actions)
    }
}

@Composable
fun PageHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) =
    PageHeader(title, modifier, onBack, subtitle = subtitle?.let { { Text(it, maxLines = 1) } })

@Composable
fun RouteText(
    from: String,
    to: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
) {
    val arrow = with(LocalDensity.current) { (if (style.fontSize.isSpecified) style.fontSize else 16.sp).toDp() }
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "von $from nach $to" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(from, Modifier.weight(1f, fill = false), color = color, style = style, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 4.dp).size(arrow), tint = if (color != Color.Unspecified) color else LocalContentColor.current)
        Text(to, Modifier.weight(1f, fill = false), color = color, style = style, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun StatusBarScrim() {
    Spacer(
        Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    )
}

fun shortPlatform(p: String?): String? = p?.trim()?.takeIf { it.isNotEmpty() }?.replace("Gleis ", "Gl. ")
    ?.let { if (it.first().isDigit()) "Gl. $it" else it }

val LocalRailBg = androidx.compose.runtime.compositionLocalOf<Color?> { null }

@Composable
fun PlatformChip(platform: String?, modifier: Modifier = Modifier) {
    val label = shortPlatform(platform) ?: return
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (LocalRailBg.current != null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
    }
}

enum class RailLine { Solid, Dotted, None }

@Composable
fun TimelineRail(
    top: RailLine,
    bottom: RailLine,
    color: Color,
    modifier: Modifier = Modifier,
    dotY: Dp? = null,
    dotRadius: Dp = 6.dp,
    filledDot: Boolean = true,
    dotBackground: Color = MaterialTheme.colorScheme.surface,
) {
    val dotted = PathEffect.dashPathEffect(floatArrayOf(0.1f, 14f), 0f)
    Box(
        modifier.width(28.dp).fillMaxHeight().drawBehind {
            val cx = size.width / 2
            val cy = dotY?.toPx() ?: 0f
            val w = 5.dp.toPx()
            fun seg(kind: RailLine, y0: Float, y1: Float) {
                when (kind) {
                    RailLine.Solid -> drawLine(color, Offset(cx, y0), Offset(cx, y1), w)
                    RailLine.Dotted -> drawLine(
                        color.copy(alpha = 0.7f), Offset(cx, y0), Offset(cx, y1), 4.dp.toPx(), StrokeCap.Round, dotted,
                    )
                    RailLine.None -> Unit
                }
            }
            if (dotY == null) seg(top, 0f, size.height) else {
                seg(top, 0f, cy)
                seg(bottom, cy, size.height)
                val r = dotRadius.toPx()
                drawCircle(if (filledDot) color else dotBackground, r + 1.dp.toPx(), Offset(cx, cy))
                if (!filledDot) drawCircle(color, r - 1.5.dp.toPx(), Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            }
        },
    )
}
