package com.aieyes.detect

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv8 detector running on ONNX Runtime.
 *
 * The bundled model is yolov8s fine-tuned on Open Images V7 (601 classes), which
 * includes everyday objects relevant to a blind user (e.g. "Waste container").
 * Output tensor format: [1, 4 + numClasses, numAnchors] (transposed layouts are
 * handled too).
 */
class YoloDetector(private val context: Context) {

    companion object {
        private const val TAG = "YoloDetector"
        private const val MODEL_ASSET = "yolov8s-oiv7.onnx"
        private const val LABELS_ASSET = "labels.txt"
        const val INPUT_SIZE = 640

        // The OIV7 models yield much lower confidences than COCO models
        // (verified with the official ultralytics pipeline), so the decode cut
        // must stay low; guidance uses the same threshold so a visible box is
        // always actionable.
        private const val CONF_THRESHOLD = 0.15f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 10
        private const val MAX_AMBIENT_DETECTIONS = 15
        private const val AMBIENT_PER_CLASS = 2
        private const val PAD_VALUE = 114f / 255f
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    private val inputBuffer = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    private val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)

    private var inputName = ""
    private var outputName = ""

    lateinit var labels: List<String>
        private set

    val numClasses: Int get() = labels.size

    fun load() {
        labels = context.assets.open(LABELS_ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val sess = env.createSession(modelBytes, options)
        session = sess
        inputName = sess.inputNames.first()
        outputName = sess.outputNames.first()
        Log.i(TAG, "Model loaded: ${labels.size} classes, input=$inputName output=$outputName")
    }

    fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
    }

    /**
     * Runs one inference pass. Every class above the threshold is decoded so the
     * overlay always shows what the model sees. With a non-null [targetClassIndex]
     * that class is additionally decoded without the ambient caps, so guidance and
     * the overlay highlight never lose the target to crowded-frame filtering.
     */
    fun detect(bitmap: Bitmap, targetClassIndex: Int?): List<Detection> {
        val sess = session ?: return emptyList()
        if (!::labels.isInitialized || labels.isEmpty()) return emptyList()
        if (targetClassIndex != null &&
            (targetClassIndex < 0 || targetClassIndex >= labels.size)
        ) return emptyList()

        val imgW = bitmap.width
        val imgH = bitmap.height
        if (imgW == 0 || imgH == 0) return emptyList()

        val scale = min(INPUT_SIZE.toFloat() / imgW, INPUT_SIZE.toFloat() / imgH)
        val newW = (imgW * scale).roundToInt().coerceIn(1, INPUT_SIZE)
        val newH = (imgH * scale).roundToInt().coerceIn(1, INPUT_SIZE)
        val padX = (INPUT_SIZE - newW) / 2
        val padY = (INPUT_SIZE - newH) / 2

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        scaled.getPixels(pixelBuffer, 0, newW, 0, 0, newW, newH)
        if (scaled !== bitmap) scaled.recycle()

        Arrays.fill(inputBuffer, PAD_VALUE)
        val area = INPUT_SIZE * INPUT_SIZE
        for (y in 0 until newH) {
            val srcRow = y * newW
            val dstRow = (y + padY) * INPUT_SIZE + padX
            for (x in 0 until newW) {
                val p = pixelBuffer[srcRow + x]
                val off = dstRow + x
                inputBuffer[off] = ((p shr 16) and 0xFF) / 255f
                inputBuffer[area + off] = ((p shr 8) and 0xFF) / 255f
                inputBuffer[2 * area + off] = (p and 0xFF) / 255f
            }
        }

        val geom = Geom(imgW.toFloat(), imgH.toFloat(), scale, padX.toFloat(), padY.toFloat())
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(inputBuffer), shape).use { input ->
            sess.run(mapOf(inputName to input)).use { result ->
                val out = result[0] as OnnxTensor
                val buffer = out.floatBuffer
                buffer.rewind()
                return postProcess(buffer, out.info.shape, targetClassIndex, geom)
            }
        }
    }

    private class Geom(
        val imgW: Float,
        val imgH: Float,
        val scale: Float,
        val padX: Float,
        val padY: Float
    )

    private fun postProcess(
        buf: FloatBuffer,
        dims: LongArray,
        target: Int?,
        geom: Geom
    ): List<Detection> {
        if (dims.size < 3) return emptyList()
        val d1 = dims[1].toInt()
        val d2 = dims[2].toInt()
        // Expected layout: [1, 4 + numClasses, numAnchors]; handle [1, numAnchors, 4 + numClasses] too.
        val channelsFirst = d1 <= d2
        val channels = if (channelsFirst) d1 else d2
        val anchors = if (channelsFirst) d2 else d1
        if (channels < 5 || anchors <= 0) return emptyList()
        val classCount = min(labels.size, channels - 4)
        if (classCount <= 0) return emptyList()

        if (target != null) {
            if (target >= classCount) return emptyList()
            val kept = nms(collectClass(buf, channelsFirst, channels, anchors, target, geom), MAX_DETECTIONS)
            val result = ArrayList<Detection>(kept.size + MAX_AMBIENT_DETECTIONS)
            for (k in kept) {
                result.add(Detection(target, labels[target], k[4], k[0], k[1], k[2], k[3]))
            }
            result.addAll(ambientDetections(buf, channelsFirst, channels, anchors, classCount, geom, skip = target))
            return result
        }

        return ambientDetections(buf, channelsFirst, channels, anchors, classCount, geom)
    }

    private fun ambientDetections(
        buf: FloatBuffer,
        channelsFirst: Boolean,
        channels: Int,
        anchors: Int,
        classCount: Int,
        geom: Geom,
        skip: Int = -1
    ): List<Detection> {
        val all = ArrayList<Pair<Int, FloatArray>>(MAX_AMBIENT_DETECTIONS)
        for (c in 0 until classCount) {
            if (c == skip) continue
            val kept = nms(
                collectClass(buf, channelsFirst, channels, anchors, c, geom),
                AMBIENT_PER_CLASS
            )
            for (k in kept) all.add(c to k)
        }
        all.sortByDescending { it.second[4] }
        return all.take(MAX_AMBIENT_DETECTIONS).map { (c, k) ->
            Detection(c, labels.getOrElse(c) { "class_$c" }, k[4], k[0], k[1], k[2], k[3])
        }
    }

    private fun collectClass(
        buf: FloatBuffer,
        channelsFirst: Boolean,
        channels: Int,
        anchors: Int,
        cls: Int,
        geom: Geom
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>(16)
        if (channelsFirst) {
            val clsRow = (4 + cls) * anchors
            for (i in 0 until anchors) {
                val score = buf.get(clsRow + i)
                if (score < CONF_THRESHOLD) continue
                addCandidate(
                    out,
                    buf.get(i), buf.get(anchors + i),
                    buf.get(2 * anchors + i), buf.get(3 * anchors + i),
                    score, geom
                )
            }
        } else {
            for (i in 0 until anchors) {
                val base = i * channels
                val score = buf.get(base + 4 + cls)
                if (score < CONF_THRESHOLD) continue
                addCandidate(
                    out,
                    buf.get(base), buf.get(base + 1), buf.get(base + 2), buf.get(base + 3),
                    score, geom
                )
            }
        }
        out.sortByDescending { it[4] }
        return out
    }

    private fun nms(candidates: List<FloatArray>, keep: Int): List<FloatArray> {
        val kept = ArrayList<FloatArray>(keep)
        for (c in candidates) {
            var overlapped = false
            for (k in kept) {
                if (iou(c, k) > IOU_THRESHOLD) {
                    overlapped = true
                    break
                }
            }
            if (!overlapped) {
                kept.add(c)
                if (kept.size >= keep) break
            }
        }
        return kept
    }

    private fun addCandidate(
        out: MutableList<FloatArray>,
        cx: Float, cy: Float, bw: Float, bh: Float, score: Float,
        geom: Geom
    ) {
        val x1 = (cx - bw / 2f - geom.padX) / geom.scale / geom.imgW
        val y1 = (cy - bh / 2f - geom.padY) / geom.scale / geom.imgH
        val x2 = (cx + bw / 2f - geom.padX) / geom.scale / geom.imgW
        val y2 = (cy + bh / 2f - geom.padY) / geom.scale / geom.imgH
        if (x2 <= 0f || y2 <= 0f || x1 >= 1f || y1 >= 1f) return
        out.add(floatArrayOf(max(0f, x1), max(0f, y1), min(1f, x2), min(1f, y2), score))
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = max(a[0], b[0])
        val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2])
        val y2 = min(a[3], b[3])
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        if (inter <= 0f) return 0f
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (areaA + areaB - inter)
    }
}
