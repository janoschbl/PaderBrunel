package dev.padermodern.companion

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class GermanSynthesisDeviceTest {
    @Test fun downloadableOfflineVoiceIsNotAnInstalledVoice() {
        val missing = Voice("downloadable", Locale.GERMANY, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false,
            setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val available = Voice("installed", Locale.GERMANY, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        assertEquals(available, installedGermanVoice(setOf(missing, available), missing))
        assertNull(installedGermanVoice(setOf(missing), missing))
    }

    @Test fun germanVoiceProducesAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialized = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicInteger(Int.MIN_VALUE)
        var engine: TextToSpeech? = null
        val output = File(instrumentation.targetContext.cacheDir, "german-synthesis-test.wav")
        try {
            instrumentation.runOnMainSync {
                engine = TextToSpeech(instrumentation.targetContext) { result.set(it); initialized.countDown() }
            }
            assertTrue("TTS initialization timeout", initialized.await(20, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, result.get())
            instrumentation.runOnMainSync {
                val tts = engine!!
                assertTrue("German language unavailable", tts.setLanguage(Locale.GERMANY) >= 0)
                Log.i("GermanSynthesisTest", "Default German voice: ${tts.voice}")
                val voice = installedGermanVoice(tts.voices, tts.voice)
                assertNotNull("Installed German voice required", voice)
                assertFalse(voice!!.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                Log.i("GermanSynthesisTest", "Selected installed German voice: $voice")
                assertEquals(TextToSpeech.SUCCESS, tts.setVoice(voice))
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { result.set(TextToSpeech.SUCCESS); completed.countDown() }
                    @Deprecated("Legacy callback")
                    override fun onError(id: String?) { result.set(TextToSpeech.ERROR); completed.countDown() }
                    override fun onError(id: String?, errorCode: Int) { result.set(errorCode); completed.countDown() }
                })
                assertEquals(TextToSpeech.SUCCESS, tts.synthesizeToFile("Nächster Halt: Detmolder Tor, Steig eins.", null, output, "german-test"))
            }
            assertTrue("German synthesis timeout", completed.await(25, TimeUnit.SECONDS))
            assertEquals("German synthesis error code", TextToSpeech.SUCCESS, result.get())
            assertTrue("No PCM audio generated", output.length() > 44)
        } finally {
            instrumentation.runOnMainSync { engine?.shutdown() }
            output.delete()
        }
    }
}
