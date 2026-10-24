package dev.padermodern.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.padermodern.ui.theme.LocalTransitColors

import dev.padermodern.ui.theme.LocalTransitColors
import dev.padermodern.ui.theme.ModeColors

enum class Mode { Bus, RegionalBus, Train, SBahn }

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
fun StatusBarScrim() {
    Spacer(
        Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
    )
}

@Composable
internal fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, tag: (T) -> String) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    val pill = RoundedCornerShape(50)
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp)
            .clip(pill).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(
            segment * index,
            spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "segmentOffset",
        )
        Box(
            Modifier.offset(offset).width(segment).fillMaxHeight().padding(4.dp)
                .clip(pill).background(MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { m ->
                val active = selected == m
                val color by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "segmentText",
                )
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(pill).testTag(tag(m))
                        .clickable(role = Role.Tab, onClick = { onSelect(m) }),
                    contentAlignment = Alignment.Center,
                ) { Text(label(m), style = MaterialTheme.typography.labelLarge, color = color) }
            }
        }
    }
}
