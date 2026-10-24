package dev.padermodern.capture

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.padermodern.ui.screens.CaptureBusScreen
import dev.padermodern.ui.screens.CaptureScreen
import dev.padermodern.ui.theme.PaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            PaderTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "capture") {
                        composable("capture") { CaptureScreen(onBus = { nav.navigate("bus/$it") }) }
                        composable("bus/{id}") { e ->
                            CaptureBusScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onBus = { nav.navigate("bus/$it") })
                        }
                    }
                }
            }
        }
    }
}
