package com.aieyes.detect

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aieyes.util.toUprightBitmap

/**
 * CameraX analyzer that runs YOLO inference on camera frames.
 *
 * All classes are decoded on every pass so the overlay always shows what the
 * model sees; with an active search target that class is decoded without the
 * ambient caps (see [YoloDetector.detect]). Frames are dropped while a previous
 * inference is running or the pipeline is idle.
 */
class FrameAnalyzer(
    private val detector: YoloDetector,
    private val activeProvider: () -> Boolean,
    private val targetProvider: () -> Int?,
    private val onResult: (List<Detection>, Long) -> Unit,
    private val onAnalyzerError: (Throwable) -> Unit = {}
) : ImageAnalysis.Analyzer {

    companion object {
        private const val SEARCH_INTERVAL_MS = 220L
        private const val AMBIENT_INTERVAL_MS = 800L
    }

    private var lastRunAt = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!activeProvider()) {
            image.close()
            return
        }
        val target = targetProvider()
        val minInterval = if (target != null) SEARCH_INTERVAL_MS else AMBIENT_INTERVAL_MS
        if (now - lastRunAt < minInterval) {
            image.close()
            return
        }
        lastRunAt = now

        var frame: Bitmap? = null
        try {
            frame = image.toUprightBitmap()
        } catch (t: Throwable) {
            onAnalyzerError(t)
        } finally {
            image.close()
        }
        val bmp = frame ?: return

        try {
            val detections = detector.detect(bmp, target)
            onResult(detections, now)
        } catch (t: Throwable) {
            onAnalyzerError(t)
        } finally {
            bmp.recycle()
        }
    }
}
