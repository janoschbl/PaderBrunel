package dev.padermodern

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.padermodern.data.ThemeMode
import dev.padermodern.ui.AppViewModel
import dev.padermodern.ui.PaderModernApp
import dev.padermodern.ui.theme.PaderTheme

class MainActivity : ComponentActivity() {
    private var bench: AppViewModel? = null
    private var lastSeq: String? = null

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleBench(intent)
    }

    /** Debug builds only: `am start ... --es pm_from NAME --es pm_to NAME [--es pm_at HH:MM] --es pm_seq N` runs one timed search. */
    private fun handleBench(intent: android.content.Intent?) {
        if (!dev.padermodern.data.Pm.enabled) return
        val seq = intent?.getStringExtra("pm_seq") ?: return
        if (seq == lastSeq) return
        lastSeq = seq
        val mode = intent.getStringExtra("pm_nobackend")
        dev.padermodern.data.BackendApi.forceOff = mode == "1" || mode == "2"
        dev.padermodern.data.BackendApi.providerOnly = mode == "2"
        bench?.benchSearch(intent.getStringExtra("pm_from").orEmpty(), intent.getStringExtra("pm_to").orEmpty(), intent.getStringExtra("pm_at"), seq, intent.getStringExtra("pm_day")?.toLongOrNull() ?: 0L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            androidx.compose.runtime.LaunchedEffect(Unit) { bench = vm; handleBench(intent) }
            val mode by vm.store.themeMode.collectAsState()
            val dark = when (mode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // bar icons follow the in-app theme, not the system one
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            PaderTheme(mode) { PaderModernApp(vm) }
        }
    }
}
