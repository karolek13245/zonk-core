package com.example.aiapp

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Voice typing on top of the phone's own speech recognizer. One tap starts it, it stops by itself when you stop
 * talking (or when you tap again), and the words come back through the callbacks.
 * Zonk-Core never records or keeps your voice. Call everything here from the main thread: that is what Android wants.
 *
 * On Android 13 and newer, phones that support it recognize the words on the phone itself. On other phones the
 * speech service of the phone decides, and some of them send the voice to their provider (for example Google).
 */
class VoiceTyper(
    private val context: Context,
    /** Words heard so far, while you are still talking. */
    private val onPartial: (String) -> Unit,
    /** The final words. */
    private val onFinal: (String) -> Unit,
    /** Listening ended. [error] is a sentence for the person, or null when there is nothing to say. */
    private val onEnd: (error: String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    var listening = false
        private set

    /** True when the words are recognized on this phone itself. */
    fun onDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** True when the phone has any speech recognizer at all. */
    fun available(): Boolean = onDevice() || SpeechRecognizer.isRecognitionAvailable(context)

    /** Starts listening. Returns false when the phone has no speech recognizer. */
    @SuppressLint("NewApi")
    fun start(): Boolean {
        if (listening) return true
        if (!available()) return false
        val r = if (onDevice()) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(listener)
        recognizer = r
        listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
        return true
    }

    /** Stops listening. The words heard so far are still delivered. */
    fun stop() {
        if (listening) recognizer?.stopListening()
    }

    /** Throws the recognizer away. Safe to call at any time, also when nothing is running. */
    fun destroy() {
        listening = false
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) { }
            try { it.destroy() } catch (_: Exception) { }
        }
        recognizer = null
    }

    private fun finish(error: String?) {
        val r = recognizer
        recognizer = null
        listening = false
        // Destroyed a moment later: Android does not like a recognizer being destroyed from inside its own callback.
        if (r != null) Handler(Looper.getMainLooper()).post { try { r.destroy() } catch (_: Exception) { } }
        onEnd(error)
    }

    private fun firstWords(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { }
        override fun onBeginningOfSpeech() { }
        override fun onRmsChanged(rmsdB: Float) { }
        override fun onBufferReceived(buffer: ByteArray?) { }
        override fun onEndOfSpeech() { }
        override fun onEvent(eventType: Int, params: Bundle?) { }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!listening) return
            firstWords(partialResults)?.let { onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            firstWords(results)?.let { onFinal(it) }
            finish(null)
        }

        override fun onError(error: Int) {
            if (!listening) return
            finish(voiceErrorMessage(error))
        }
    }
}

/** Shown once per app start when the words may leave the phone, so nobody is surprised. */
const val VOICE_NOTICE = "Voice typing uses your phone's speech service. On this phone it may send your voice to that service."
