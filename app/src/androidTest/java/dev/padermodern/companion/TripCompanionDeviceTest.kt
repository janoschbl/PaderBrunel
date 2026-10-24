package dev.padermodern.companion

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.padermodern.MainActivity
import dev.padermodern.data.PartialRoute
import dev.padermodern.data.Route
import dev.padermodern.data.Station
import dev.padermodern.data.iso
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue

class TripCompanionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun speechCompletesOnSelectedHeadphones() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var speech: JourneySpeech? = null
        val ready = AtomicBoolean(false)
        val status = AtomicReference("")
        try {
            instrumentation.runOnMainSync {
                speech = JourneySpeech(instrumentation.targetContext) { _, initialized, text -> ready.set(initialized); status.set(text) }
            }
            waitUntil { ready.get() }
            var headphones = false
            instrumentation.runOnMainSync { headphones = speech!!.hasHeadphones() }
            if (Build.VERSION.SDK_INT >= 33) {
                val audio = instrumentation.targetContext.getSystemService(AudioManager::class.java)
                val media = audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()).map { it.type }
                val navigation = audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).map { it.type }
                Log.i("CompanionDeviceTest", "Selected output types: media=$media, navigation=$navigation")
            }
            assumeTrue("Connect headphones to verify actual routed playback", headphones)
            instrumentation.runOnMainSync { assertTrue(speech!!.speak("Dies ist eine Testansage der Fahrtbegleitung.")) }
            waitUntil { status.get() == "Ansage abgespielt" || status.get().contains("fehlgeschlagen") || status.get().contains("fehlt") }
            assertEquals("Ansage abgespielt", status.get())
        } finally {
            instrumentation.runOnMainSync { speech?.close() }
            scenario.close()
        }
    }

    @Test fun speechCannotStartOnPhoneSpeaker() {
        var speech: JourneySpeech? = null
        val ready = AtomicBoolean(false)
        try {
            instrumentation.runOnMainSync {
                speech = JourneySpeech(instrumentation.targetContext) { _, initialized, _ -> ready.set(initialized) }
            }
            waitUntil { ready.get() }
            var hasHeadphones = false
            var accepted = false
            instrumentation.runOnMainSync {
                hasHeadphones = speech!!.hasHeadphones()
                if (!hasHeadphones) accepted = speech!!.speak("Diese Ansage darf nicht auf dem Lautsprecher laufen.")
            }
            assumeTrue("Disconnect headphones to verify the speaker guard", !hasHeadphones)
            assertFalse("No TTS request may be queued on the phone speaker", accepted)
        } finally {
            instrumentation.runOnMainSync { speech?.close() }
        }
    }

    @Test fun foregroundSessionAndHeadphonePolicy() {
        val context = instrumentation.targetContext
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val now = ZonedDateTime.now()
            val route = Route(partialRoutes = listOf(PartialRoute(shortName = "Testfahrt", departureDate = now.iso(),
                arrivalDate = now.plusMinutes(10).iso(), stations = listOf(
                    Station(name = "Teststart", latitude = 51.717, longitude = 8.75, date = now.iso()),
                    Station(name = "Testziel", latitude = 51.725, longitude = 8.75, date = now.plusMinutes(10).iso()),
                ))), departureDate = now.iso(), arrivalDate = now.plusMinutes(10).iso())
            scenario.onActivity { TripCompanion.start(it, "device-smoke-test", route, 0) }
            waitUntil { TripCompanion.state.value.running }
            waitUntil { TripCompanion.state.value.speechReady || TripCompanion.state.value.speechStatus.contains("fehlt") }
            assertTrue("German TTS must be installed on this test phone", TripCompanion.state.value.speechReady)
            assertEquals("device-smoke-test", TripCompanion.state.value.tripId)
            scenario.onActivity { TripCompanion.command(it, TripCompanion.TEST) }
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(2_000)
            assertTrue("Tracking should survive backgrounding", TripCompanion.state.value.running)
            instrumentation.runOnMainSync { TripCompanion.command(context, TripCompanion.STOP) }
            waitUntil { !TripCompanion.state.value.running }
            assertNull(TripCompanion.state.value.tripId)
        } finally {
            instrumentation.runOnMainSync { TripCompanion.command(context, TripCompanion.STOP) }
            scenario.close()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("Timed out waiting for companion state", condition())
    }
}
