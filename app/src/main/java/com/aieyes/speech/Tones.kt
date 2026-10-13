package com.aieyes.speech

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short prompt tones for the hold-to-talk interaction: a higher beep marks the
 * start of listening, a lower one marks the end.
 */
class Tones {

    private val generator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME)
    } catch (_: Throwable) {
        null
    }

    fun listenStart() = play(ToneGenerator.TONE_PROP_BEEP)

    fun listenEnd() = play(ToneGenerator.TONE_PROP_BEEP2)

    private fun play(tone: Int) {
        try {
            generator?.startTone(tone, VOLUME)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            generator?.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val VOLUME = 85
    }
}
