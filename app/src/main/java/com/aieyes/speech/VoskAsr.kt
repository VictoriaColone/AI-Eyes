package com.aieyes.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Offline Chinese ASR fallback built on Vosk (vosk-model-small-cn), for devices
 * that ship no SpeechRecognizer service (most mainland-China ROMs).
 *
 * The bundled model is unpacked from assets on first launch; recognition is
 * restricted to a fixed command grammar, which makes the small model reliable
 * for this app's fixed command set.
 */
class VoskAsr(private val appContext: Context) {

    companion object {
        private const val TAG = "VoskAsr"
        private const val MODEL_ASSET_DIR = "vosk-model-small-cn-0.22"
        private const val SAMPLE_RATE = 16000
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var ready = false
        private set

    @Volatile
    var failed = false
        private set

    @Volatile
    private var loading = false

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audio: AudioRecord? = null

    @Volatile
    private var recording = false

    // Monotonic session id: a new start() invalidates the loop of a session that
    // is still being recorded by the worker, so a quick re-press can never
    // swallow the new recording into the old loop.
    private val generation = java.util.concurrent.atomic.AtomicLong(0)

    fun preload(grammar: List<String>) {
        if (ready || failed || loading) return
        loading = true
        worker.execute {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val dir = unpackModel()
                model = Model(dir.absolutePath)
                val grammarJson = JSONArray(grammar).toString()
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat(), grammarJson)
                audio = createAudioRecord()
                ready = true
                Log.i(TAG, "Vosk ready (${dir.name})")
            } catch (t: Throwable) {
                Log.e(TAG, "Vosk init failed", t)
                failed = true
            }
        }
    }

    fun start(onText: (String) -> Unit, onError: () -> Unit) {
        val rec = recognizer
        val mic = audio
        if (!ready || rec == null || mic == null) {
            main.post { onError() }
            return
        }
        // Flip on the caller thread so a release that arrives before the worker
        // starts still terminates the loop instead of latching it on.
        recording = true
        val gen = generation.incrementAndGet()
        worker.execute {
            try {
                rec.reset()
                mic.startRecording()
                val buffer = ByteArray(4096)
                while (recording && generation.get() == gen) {
                    val n = mic.read(buffer, 0, buffer.size)
                    if (n > 0) rec.acceptWaveForm(buffer, n)
                }
                mic.stop()
                val text = if (generation.get() == gen) {
                    JSONObject(rec.getFinalResult()).optString("text").trim()
                } else ""
                main.post { onText(text) }
            } catch (t: Throwable) {
                Log.w(TAG, "session failed", t)
                runCatching { mic.stop() }
                main.post { onError() }
            }
        }
    }

    fun finish() {
        recording = false
    }

    fun cancel() {
        recording = false
        generation.incrementAndGet()
    }

    fun release() {
        recording = false
        generation.incrementAndGet()
        worker.execute {
            audio?.release()
            audio = null
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
        }
        worker.shutdown()
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested before use
    private fun createAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, 8192)
        )
    }

    private fun unpackModel(): File {
        val outDir = File(appContext.filesDir, MODEL_ASSET_DIR)
        val marker = File(outDir, "conf/model.conf")
        if (marker.isFile) return outDir
        outDir.deleteRecursively()
        outDir.mkdirs()
        copyAssetDir(MODEL_ASSET_DIR, outDir)
        return outDir
    }

    private fun copyAssetDir(assetPath: String, outDir: File) {
        val assets = appContext.assets
        val children = assets.list(assetPath) ?: return
        for (child in children) {
            val src = "$assetPath/$child"
            val dst = File(outDir, child)
            val nested = assets.list(src)
            if (!nested.isNullOrEmpty()) {
                dst.mkdirs()
                copyAssetDir(src, dst)
            } else {
                assets.open(src).use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
