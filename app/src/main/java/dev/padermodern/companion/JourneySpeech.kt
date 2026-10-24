package dev.padermodern.companion

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

internal class JourneySpeech(context: Context, private val changed: (Boolean, Boolean, String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var closed = false
    private var utteranceId: String? = null
    private var error = "Sprachausgabe wird vorbereitet"
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) stop()
        }.build()
    private val tts = TextToSpeech(context) { status ->
        handler.post {
            if (!closed) initialize(status)
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = report()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            // cancel queued speech so an unplug does not continue on the speaker
            stop()
            report()
        }
    }

    init {
        audio.registerAudioDeviceCallback(devices, handler)
        report()
    }

    fun hasHeadphones(): Boolean {
        // connected is not selected, media output can be on the speaker
        if (Build.VERSION.SDK_INT >= 33) {
            val outputs = audio.getAudioDevicesForAttributes(attributes)
            return outputs.isNotEmpty() && outputs.all { isHeadphone(it) }
        }
        // android 12 has no anticipated-route query, gate on connected headsets
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadphone(it) }
    }

    private fun isHeadphone(device: AudioDeviceInfo) = device.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)

    private fun initialize(status: Int) {
        val language = if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.GERMANY) else TextToSpeech.LANG_NOT_SUPPORTED
        val voice = if (language >= TextToSpeech.LANG_AVAILABLE) installedGermanVoice(tts.voices, tts.voice) else null
        ready = voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
        error = if (ready) "Bereit · nur über Kopfhörer" else "Deutsche TTS-Stimme fehlt. Bitte in den Android-Einstellungen installieren."
        if (ready) {
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { handler.post {
                    if (id != null && id == utteranceId) {
                        error = "Ansage läuft"
                        report()
                        watchRouting(id)
                    }
                } }
                override fun onDone(id: String?) { handler.post {
                    if (id == utteranceId) {
                        utteranceId = null
                        audio.abandonAudioFocusRequest(focus)
                        error = "Ansage abgespielt"
                        report()
                    }
                } }
                @Deprecated("Legacy TTS callback")
                override fun onError(id: String?) = onError(id, TextToSpeech.ERROR)
                override fun onError(id: String?, errorCode: Int) {
                    handler.post {
                        if (id != utteranceId) return@post
                        utteranceId = null
                        audio.abandonAudioFocusRequest(focus)
                        Log.w("JourneySpeech", "TTS failed: code=$errorCode, voice=${tts.voice?.name}")
                        error = when (errorCode) {
                            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Deutsche TTS-Stimme fehlt oder wird noch geladen. Bitte in den Spracheinstellungen prüfen."
                            TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Die Stimme braucht eine Internetverbindung. Bitte Verbindung oder Offline-Stimme prüfen."
                            TextToSpeech.ERROR_OUTPUT -> "Audioausgabe fehlgeschlagen. Bitte Kopfhörer erneut verbinden."
                            else -> "Ansage fehlgeschlagen (Fehler $errorCode). Bitte erneut testen."
                        }
                        if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET) ready = false
                        report()
                    }
                }
            })
        }
        report()
    }

    fun speak(text: String): Boolean {
        if (!ready) { report(); return false }
        if (!hasHeadphones()) { error = "Kopfhörer verbinden und als Audioausgabe wählen"; report(); return false }
        stop()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error = "Audio gerade belegt, zum Beispiel durch einen Anruf"
            report()
            return false
        }
        utteranceId = System.nanoTime().toString()
        val accepted = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
        if (!accepted) { utteranceId = null; audio.abandonAudioFocusRequest(focus) }
        error = if (accepted) "Ansage wird vorbereitet" else "Ansage konnte nicht gestartet werden. Bitte erneut testen."
        report()
        return accepted
    }

    private fun report() {
        if (!closed) changed(hasHeadphones(), ready, error)
    }

    fun refreshRoute() {
        if (!hasHeadphones()) stop()
        report()
    }

    fun cancel() = stop()

    private fun watchRouting(id: String) {
        if (closed || utteranceId != id) return
        if (!hasHeadphones()) { stop(); report(); return }
        handler.postDelayed({ watchRouting(id) }, 150)
    }

    private fun stop() {
        if (utteranceId == null) return
        utteranceId = null
        tts.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    fun close() {
        closed = true
        audio.unregisterAudioDeviceCallback(devices)
        stop()
        tts.shutdown()
    }
}

internal fun installedGermanVoice(voices: Set<Voice>?, current: Voice?): Voice? {
    fun available(voice: Voice) = voice.locale.language == "de" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
    return current?.takeIf(::available) ?: voices.orEmpty().filter(::available)
        .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.locale.country != "DE" }.thenBy { it.name })
        .firstOrNull()
}
