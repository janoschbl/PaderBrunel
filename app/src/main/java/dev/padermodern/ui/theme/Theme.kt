package dev.padermodern.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.padermodern.data.ThemeMode

@Immutable
data class ModeColors(val container: Color, val content: Color)

@Immutable
data class TransitColors(
    val bus: ModeColors,
    val regionalBus: ModeColors,
    val train: ModeColors,
    val sBahn: ModeColors,
    val walk: Color,
    val onTime: Color,
    val late: Color,
    val dark: Boolean,
)

private val LightTransit = TransitColors(
    bus = ModeColors(Color(0xFF0B6E8A), Color.White),
    regionalBus = ModeColors(Color(0xFF7B3F99), Color.White),
    train = ModeColors(Color(0xFF2E7D32), Color.White),
    sBahn = ModeColors(Color(0xFF00785A), Color.White),
    walk = Color(0xFF8A8F87),
    onTime = Color(0xFF2E7D32),
    late = Color(0xFFC62828),
    dark = false,
)

private val DarkTransit = TransitColors(
    bus = ModeColors(Color(0xFF7FD3EE), Color(0xFF003544)),
    regionalBus = ModeColors(Color(0xFFE2B6F5), Color(0xFF3F0D58)),
    train = ModeColors(Color(0xFF9EDC9F), Color(0xFF00390B)),
    sBahn = ModeColors(Color(0xFF7FDDBC), Color(0xFF00382A)),
    walk = Color(0xFF8E948C),
    onTime = Color(0xFF8FD893),
    late = Color(0xFFFF8A80),
    dark = true,
)

val LocalTransitColors = staticCompositionLocalOf { LightTransit }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaderTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val ctx = LocalContext.current
    // minsdk is 31, dynamic color is always available
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    androidx.compose.runtime.CompositionLocalProvider(LocalTransitColors provides if (dark) DarkTransit else LightTransit) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

fun TransitColors.onInverse(): TransitColors = if (dark) LightTransit else DarkTransit
