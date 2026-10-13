package com.aieyes.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Hold-to-talk ASR facade with two engines:
 *
 *  - Google [SpeechRecognizer], used when the device provides a recognition
 *    service (GMS devices).
 *  - Offline [VoskAsr] fallback for devices without one (most mainland-China
 *    ROMs), restricted to this app's command grammar.
 *
 * A session starts with [startSession] (screen press) and ends with
 * [finishSession] (screen release); results are delivered once via [onResult].
 * All calls happen on the main thread.
 */
class VoiceListener(
    context: Context,
    private val onResult: (List<String>) -> Unit,
    private val onError: (Int) -> Unit,
    private val onUnavailable: () -> Unit
) : RecognitionListener {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val vosk = VoskAsr(appContext)

    private val googleAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)

    private var recognizer: SpeechRecognizer? = null
    private var sessionActive = false
    private var sessionSeq = 0
    private var unavailableNotified = false
    private var googleBroken = false
    private var grammar: List<String> = emptyList()

    /** Kicks off offline-engine warm-up; call once the command grammar is known. */
    fun preload(grammar: List<String>) {
        this.grammar = grammar
        if (!usesGoogle()) {
            vosk.preload(grammar)
        }
    }

    fun startSession() {
        main.post {
            if (sessionActive) return@post
            sessionSeq++
            sessionActive = true
            if (usesGoogle()) {
                startGoogleInternal()
            } else {
                startVoskInternal()
            }
        }
    }

    fun finishSession() {
        main.post {
            if (!sessionActive) return@post
            sessionActive = false
            if (usesGoogle()) {
                try {
                    recognizer?.stopListening()
                } catch (_: Throwable) {
                }
            } else {
                vosk.finish()
            }
        }
    }

    fun cancelSession() {
        main.post {
            sessionActive = false
            sessionSeq++ // drop any pending results
            main.removeCallbacksAndMessages(null)
            if (usesGoogle()) {
                try {
                    recognizer?.cancel()
                } catch (_: Throwable) {
                }
            } else {
                vosk.cancel()
            }
        }
    }

    fun release() {
        main.post {
            sessionActive = false
            sessionSeq++
            main.removeCallbacksAndMessages(null)
            recognizer?.destroy()
            recognizer = null
            vosk.release()
        }
    }

    private fun usesGoogle() = googleAvailable && !googleBroken

    // ---------------------------------------------------------------- google

    private fun startGoogleInternal() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            notifyUnavailable()
            return
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(this)
            }
        }
        try {
            recognizer?.startListening(buildIntent())
        } catch (t: Throwable) {
            // No usable recognition service after all (common on CN ROMs that
            // report one but fail to bind) — switch to the offline engine.
            googleBroken = true
            sessionActive = false
            vosk.preload(grammar)
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun notifyUnavailable() {
        if (!unavailableNotified) {
            unavailableNotified = true
            onUnavailable()
        }
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
    }

    override fun onResults(results: Bundle?) {
        sessionActive = false
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        onResult(list)
    }

    override fun onError(error: Int) {
        sessionActive = false
        if (error == SpeechRecognizer.ERROR_CLIENT) {
            // Service missing or failed to bind: fall back to offline engine
            // for subsequent sessions.
            googleBroken = true
            vosk.preload(grammar)
        }
        onError(error)
    }

    // ---------------------------------------------------------------- vosk

    private fun startVoskInternal() {
        val seq = sessionSeq
        if (!vosk.ready) {
            // Still unpacking/loading, or init failed permanently.
            if (vosk.failed) {
                notifyUnavailable()
            } else {
                vosk.preload(grammar)
            }
            sessionActive = false
            onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        vosk.start(
            onText = { text ->
                if (seq == sessionSeq) {
                    sessionActive = false
                    onResult(if (text.isBlank()) emptyList() else listOf(text))
                }
            },
            onError = {
                if (seq == sessionSeq) {
                    sessionActive = false
                    onError(SpeechRecognizer.ERROR_CLIENT)
                }
            }
        )
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
