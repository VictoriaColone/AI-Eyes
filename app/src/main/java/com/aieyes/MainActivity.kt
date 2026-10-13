package com.aieyes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.aieyes.databinding.ActivityMainBinding
import com.aieyes.detect.Detection
import com.aieyes.detect.FrameAnalyzer
import com.aieyes.detect.YoloDetector
import com.aieyes.guide.Command
import com.aieyes.guide.CommandParser
import com.aieyes.guide.GuidanceEngine
import com.aieyes.speech.Speaker
import com.aieyes.speech.Tones
import com.aieyes.speech.VoiceListener
import java.util.concurrent.Executors

/**
 * Voice-first, hold-to-talk flow:
 *
 *  BOOT -> (model + camera ready, announcement) -> LISTEN
 *  Press & hold the screen anywhere: a beep plays, listening starts
 *  (detection keeps running so the overlay stays alive).
 *  Release: a lower beep plays, the recording finalizes and the utterance is
 *  parsed. "Find X" enters SEARCH; "stop" returns to LISTEN.
 *  SEARCH: YOLO tracks the target and guidance is spoken periodically; arrival
 *  is signalled with a vibration pattern.
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { BOOT, LISTEN, SEARCH }

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS =
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private const val HINT_GAP_MS = 7000L
        private const val RESULT_FALLBACK_MS = 2000L
        private val PATTERN_ACK = longArrayOf(0, 60)
        private val PATTERN_ARRIVED = longArrayOf(0, 180, 120, 180, 120, 450)
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceListener
    private lateinit var tones: Tones
    private val detector by lazy { YoloDetector(this) }
    private val guidance = GuidanceEngine()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var parser: CommandParser? = null

    @Volatile
    private var mode = Mode.BOOT

    @Volatile
    private var targetIndex: Int? = null

    @Volatile
    private var isPaused = false

    @Volatile
    private var holding = false

    private var targetName = ""
    private var modelReady = false
    private var cameraReady = false
    private var announced = false
    private var bootstrapped = false
    private var pipelineStarted = false
    private var lastHintAt = 0L
    private var sessionToken = 0
    private var sessionFailed = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startPipeline()
        } else {
            binding.statusText.text = "缺少权限：请允许使用摄像头和麦克风"
            speaker.speak("没有获得权限，无法使用。请在系统设置中，为AI之眼打开摄像头和麦克风权限。")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speaker = Speaker(this)
        speaker.onReady = { bootstrap() }

        voice = VoiceListener(
            context = this,
            onResult = { onVoiceResults(it) },
            onError = { err ->
                if (holding) sessionFailed = true
                Log.w(TAG, "voice error: $err")
            },
            onUnavailable = { onVoiceUnavailable() }
        )
        tones = Tones()

        binding.rootView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onPressStart()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPressEnd()
            }
            true
        }
        binding.statusText.text = getString(R.string.status_booting)
    }

    // ---------------------------------------------------------------- startup

    private fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        if (hasPermissions()) {
            startPipeline()
        } else {
            binding.statusText.text = "等待权限…"
            binding.subStatusText.text = "需要摄像头和麦克风权限"
            speaker.speak("您好，我是AI之眼。首次使用需要摄像头和麦克风权限，请在屏幕上点击「允许」。")
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPipeline() {
        if (pipelineStarted) return
        pipelineStarted = true
        binding.statusText.text = "正在加载AI识别模型…"
        speaker.speak("正在启动，请稍候。")
        startCamera()
        Thread({
            var error: Throwable? = null
            try {
                detector.load()
            } catch (t: Throwable) {
                error = t
            }
            val err = error
            main.post {
                if (err == null) {
                    val p = CommandParser(detector.labels)
                    parser = p
                    voice.preload(p.grammarPhrases)
                    modelReady = true
                    maybeAnnounceReady()
                } else {
                    Log.e(TAG, "Model load failed", err)
                    binding.statusText.text = "AI模型加载失败"
                    binding.subStatusText.text = err.message ?: ""
                    speaker.speak("AI模型加载失败，请检查应用中的模型文件。")
                }
            }
        }, "model-loader").start()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = binding.previewView.surfaceProvider
                }
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 960),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(
                    cameraExecutor,
                    FrameAnalyzer(
                        detector = detector,
                        activeProvider = { mode != Mode.BOOT && !isPaused },
                        targetProvider = { if (mode == Mode.SEARCH) targetIndex else null },
                        onResult = { detections, now -> onDetections(detections, now) },
                        onAnalyzerError = { Log.w(TAG, "analyzer error", it) }
                    )
                )
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                cameraReady = true
                maybeAnnounceReady()
            } catch (t: Throwable) {
                Log.e(TAG, "Camera start failed", t)
                binding.subStatusText.text = "摄像头启动失败：${t.message}"
                speaker.speak("摄像头启动失败，请重新打开应用。")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun maybeAnnounceReady() {
        if (announced || !modelReady || !cameraReady) return
        announced = true
        mode = Mode.LISTEN
        binding.statusText.text = "已就绪 · 按住屏幕说话"
        binding.subStatusText.text = "例如：垃圾桶、椅子、杯子"
        val text = "您好，很高兴帮助您。想找什么东西时，请按住屏幕说话，说完松开手指。" +
            "比如：垃圾桶、椅子、杯子。找到后我会一步一步引导您。"
        if (speaker.isReady) {
            speaker.speak(text)
        }
        // Without TTS the flow still works: detection and hold-to-talk ASR run,
        // only the spoken announcements are missing.
    }

    // ---------------------------------------------------------------- hold to talk

    private fun onPressStart() {
        if (isPaused || mode == Mode.BOOT) return
        if (!modelReady || !cameraReady) return
        if (holding) return
        holding = true
        sessionFailed = false
        sessionToken++
        speaker.stop()
        tones.listenStart()
        binding.statusText.text = "正在聆听…"
        binding.subStatusText.text =
            if (mode == Mode.SEARCH) "松开后切换目标（当前：$targetName）" else "请说出想找的东西，松开结束"
        voice.startSession()
    }

    private fun onPressEnd() {
        if (!holding) return
        holding = false
        tones.listenEnd()
        voice.finishSession()
        binding.statusText.text = "识别中…"
        if (sessionFailed) {
            // The recognizer already gave up during the hold; no results will come.
            sessionToken++
            restoreIdleStatus()
            maybeSpeakHint("", HINT_GAP_MS)
            return
        }
        val token = sessionToken
        main.postDelayed({
            if (token == sessionToken && !isPaused) {
                restoreIdleStatus()
                maybeSpeakHint("", HINT_GAP_MS)
            }
        }, RESULT_FALLBACK_MS)
    }

    private fun restoreIdleStatus() {
        if (mode == Mode.SEARCH && targetIndex != null) {
            binding.statusText.text = "正在寻找：$targetName"
            binding.subStatusText.text = guidance.lastPhrase
        } else if (mode == Mode.LISTEN) {
            binding.statusText.text = "等待指令"
            binding.subStatusText.text = "按住屏幕说话"
        }
    }

    private fun onVoiceUnavailable() {
        binding.subStatusText.text = "语音识别不可用"
        speaker.speak(
            "您的设备没有语音识别服务，内置的离线识别也没有准备好。" +
                "请保持应用在前台等待离线语音初始化，或重新安装应用。"
        )
    }

    // ---------------------------------------------------------------- commands

    private fun onVoiceResults(results: List<String>) {
        if (isPaused) return
        val p = parser ?: return
        var picked: Command = Command.Unknown
        for (r in results) {
            when (val c = p.parse(r)) {
                is Command.FindTarget, is Command.Stop, is Command.Confirm -> {
                    picked = c
                    break
                }
                is Command.Repeat -> if (picked is Command.Unknown) picked = c
                else -> Unit
            }
        }
        handleCommand(picked, results.firstOrNull().orEmpty())
    }

    private fun handleCommand(cmd: Command, heard: String) {
        sessionToken++
        when (cmd) {
            is Command.FindTarget -> {
                targetIndex = cmd.classIndex
                targetName = cmd.spokenName
                guidance.startTarget(cmd.spokenName)
                mode = Mode.SEARCH
                binding.statusText.text = "正在寻找：${cmd.spokenName}"
                binding.subStatusText.text = ""
                binding.overlayView.setDetections(emptyList())
                speaker.speak("好的，正在帮您寻找${cmd.spokenName}，请您开始移动。")
            }

            is Command.Stop -> {
                targetIndex = null
                mode = Mode.LISTEN
                binding.statusText.text = "等待指令"
                binding.subStatusText.text = "按住屏幕说话"
                binding.overlayView.setDetections(emptyList())
                speaker.speak("好的，已停止寻找。请按住屏幕，说出您想找的东西。")
            }

            is Command.Confirm -> {
                if (mode == Mode.SEARCH && targetIndex != null) {
                    val found = targetName
                    targetIndex = null
                    mode = Mode.LISTEN
                    vibrate(PATTERN_ACK)
                    binding.statusText.text = "已找到$found · 等待新指令"
                    binding.subStatusText.text = "按住屏幕说下一个物品"
                    speaker.speak("好的，已找到$found。请按住屏幕，说出下一个想找的物品。")
                } else {
                    speaker.speak("请按住屏幕，说出您想找的东西。")
                }
            }

            is Command.Repeat -> {
                val phrase = guidance.lastPhrase
                speaker.speak(
                    if (phrase.isNotEmpty()) phrase
                    else "请按住屏幕，说出您想找的东西，比如垃圾桶。"
                )
            }

            is Command.Unknown -> maybeSpeakHint(heard, HINT_GAP_MS)
        }
    }

    private fun maybeSpeakHint(heard: String, minGapMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHintAt < minGapMs) return
        lastHintAt = now
        val text = if (heard.isBlank()) {
            "我没有听清，请再按住屏幕说一遍。比如：垃圾桶、椅子、杯子。"
        } else {
            "抱歉，我还不会找「$heard」。您可以试试说：垃圾桶、椅子、杯子、瓶子、手机。"
        }
        speaker.speak(text)
    }

    // ---------------------------------------------------------------- search

    private fun onDetections(detections: List<Detection>, now: Long) {
        // Called on the camera executor thread; hop to the main thread for all
        // UI work and shared-state access.
        runOnUiThread { handleDetections(detections, now) }
    }

    private fun handleDetections(detections: List<Detection>, now: Long) {
        if (isPaused || mode == Mode.BOOT) return
        val t = if (mode == Mode.SEARCH) targetIndex else null
        binding.overlayView.setDetections(displayDetections(detections), t)
        if (holding) return // never talk over the user's own input
        if (mode != Mode.SEARCH || speaker.isSpeaking) return
        if (t == null) return
        val targetDetections = detections.filter { it.classIndex == t }
        when (val action = guidance.update(targetDetections, now)) {
            is GuidanceEngine.Action.Arrive -> {
                // Stay in SEARCH: guidance keeps prompting until the user says
                // "找到了" (or starts a new search / stops).
                vibrate(PATTERN_ARRIVED)
                binding.statusText.text = "已看到${targetName} · 请确认"
                binding.subStatusText.text = "请伸手触摸；找到后说「找到了」"
                speaker.speak("找到了！${targetName}就在您正前方，请伸手触摸。")
            }

            is GuidanceEngine.Action.Say -> {
                binding.subStatusText.text = action.text
                speaker.speak(action.text)
            }

            null -> Unit
        }
    }

    // ---------------------------------------------------------------- misc

    // Show Chinese names on the helper overlay when the class has one, so the
    // caretaker reads the same words the user speaks.
    private fun displayDetections(detections: List<Detection>): List<Detection> {
        val p = parser ?: return detections
        return detections.map { d ->
            p.chineseName(d.classIndex)?.let { if (it != d.label) d.copy(label = it) else d } ?: d
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        holding = false
        speaker.stop()
        voice.cancelSession()
    }

    override fun onResume() {
        super.onResume()
        if (!isPaused) return
        isPaused = false
        restoreIdleStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.release()
        tones.release()
        speaker.shutdown()
        cameraExecutor.shutdown()
        detector.close()
    }
}
