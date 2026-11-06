package dev.padermodern.data

import android.util.Log

/** Debug-only timing log, read with `adb logcat -s PMTIME`. Off in release builds and unit tests. */
object Pm {
    @Volatile var enabled = false

    inline fun <T> time(label: String, block: () -> T): T {
        if (!enabled) return block()
        val t0 = System.nanoTime()
        try { return block() } finally { Log.d("PMTIME", "$label ${(System.nanoTime() - t0) / 1_000_000} ms") }
    }

    fun note(message: String) { if (enabled) Log.d("PMTIME", message) }
}
