package com.aieyes.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * Text-to-speech wrapper with main-thread callbacks and speaking state tracking.
 */
class Speaker(context: Context) {

    var onReady: (() -> Unit)? = null
    var onSpeakStart: (() -> Unit)? = null
    var onSpeakEnd: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var currentId: String? = null

    @Volatile
    private var engineReady = false

    val isReady: Boolean get() = engineReady

    @Volatile
    var isSpeaking = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {                tts?.apply {
                    val langResult = setLanguage(Locale.SIMPLIFIED_CHINESE)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        setLanguage(Locale.getDefault())
                    }
                    setSpeechRate(1.0f)
                    setPitch(1.0f)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId == currentId) {
                                this@Speaker.isSpeaking = true
                                main.post { onSpeakStart?.invoke() }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == currentId) {
                                this@Speaker.isSpeaking = false
                                main.post { onSpeakEnd?.invoke() }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == currentId) {
                                this@Speaker.isSpeaking = false
                                main.post { onSpeakEnd?.invoke() }
                            }
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            if (utteranceId == currentId) this@Speaker.isSpeaking = false
                        }
                    })
                }
                engineReady = true
                main.post { onReady?.invoke() }
            } else {
                // Still notify so the app can continue in degraded (silent) mode.
                main.post { onReady?.invoke() }
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        val engine = tts ?: return
        if (!engineReady || text.isEmpty()) return
        val id = UUID.randomUUID().toString()
        currentId = id
        engine.speak(
            text,
            if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            Bundle(),
            id
        )
    }

    fun stop() {
        currentId = null
        isSpeaking = false
        tts?.stop()
    }

    fun shutdown() {
        currentId = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        engineReady = false
    }
}
