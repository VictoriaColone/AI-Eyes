package com.aieyes.guide

import com.aieyes.detect.Detection

/**
 * Turns per-frame detections of the current target object into spoken guidance.
 *
 * Zone = horizontal placement of the box (turn left / right / go straight).
 * Level = apparent size of the box, a rough distance proxy (walk closer / reach out).
 */
class GuidanceEngine {

    sealed class Action {
        data class Say(val text: String) : Action()
        data object Arrive : Action()
    }

    companion object {
        // Must match YoloDetector.CONF_THRESHOLD: a target box shown on the
        // overlay must always be eligible for guidance, otherwise the user sees
        // the object and hears nothing.
        private const val SCORE_THRESHOLD = 0.15f
        private const val LEFT_ZONE = 0.38f
        private const val RIGHT_ZONE = 0.62f
        private const val MID_HEIGHT = 0.18f
        private const val NEAR_HEIGHT = 0.35f
        private const val ARRIVE_HEIGHT = 0.55f

        // Direction/arrival prompts repeat on this cadence until the user
        // confirms they have found the target.
        private const val REPEAT_INTERVAL_MS = 2000L
        private const val MIN_GAP_MS = 1300L
        private const val LOST_AFTER_MS = 2600L
        private const val LOST_REPEAT_MS = 4500L
    }

    var targetName: String = ""
        private set

    var lastPhrase: String = ""
        private set

    private var lastSpokenAt = 0L
    private var lastStateKey = ""
    private var lastSeenAt = 0L
    private var arrived = false
    private var findAnnounced = false
    private var everFound = false

    fun startTarget(name: String) {
        targetName = name
        lastSpokenAt = 0L
        lastStateKey = ""
        lastSeenAt = 0L
        arrived = false
        findAnnounced = false
        everFound = false
        lastPhrase = ""
    }

    fun update(detections: List<Detection>, nowMs: Long): Action? {
        val best = pickBest(detections)
        if (best == null) {
            if (arrived) return null
            if (lastSeenAt == 0L) lastSeenAt = nowMs
            if (nowMs - lastSeenAt >= LOST_AFTER_MS &&
                nowMs - lastSpokenAt >= LOST_REPEAT_MS
            ) {
                lastSpokenAt = nowMs
                return say(
                    if (everFound) "暂时看不到${targetName}了，请慢慢转动身体，或者稍等片刻。"
                    else "还没有找到${targetName}，请慢慢转动身体，扫描周围环境。"
                )
            }
            return null
        }
        everFound = true
        lastSeenAt = nowMs

        val zone = when {
            best.centerX < LEFT_ZONE -> Zone.LEFT
            best.centerX > RIGHT_ZONE -> Zone.RIGHT
            else -> Zone.CENTER
        }
        val height = best.heightRatio

        if (!arrived) {
            if (zone == Zone.CENTER && height >= ARRIVE_HEIGHT) {
                arrived = true
                lastSpokenAt = nowMs
                return Action.Arrive
            }
        } else {
            if (zone != Zone.CENTER || height < ARRIVE_HEIGHT * 0.55f) {
                arrived = false
                findAnnounced = true
            } else {
                // Stay in the arrived state and keep prompting until the user
                // confirms they have found and touched the target.
                if (nowMs - lastSpokenAt < REPEAT_INTERVAL_MS) return null
                lastSpokenAt = nowMs
                return say(arrivalPhrase())
            }
        }

        val level = when {
            height >= NEAR_HEIGHT -> Level.NEAR
            height >= MID_HEIGHT -> Level.MID
            else -> Level.FAR
        }
        val stateKey = "$zone$level"
        val stateChanged = stateKey != lastStateKey
        if (stateChanged && nowMs - lastSpokenAt < MIN_GAP_MS) return null
        if (!stateChanged && nowMs - lastSpokenAt < REPEAT_INTERVAL_MS) return null
        lastStateKey = stateKey
        lastSpokenAt = nowMs

        val prefix = if (!findAnnounced) {
            findAnnounced = true
            "找到了！"
        } else ""
        return say(prefix + phraseFor(zone, level))
    }

    private fun phraseFor(zone: Zone, level: Level): String = when (zone) {
        Zone.LEFT -> when (level) {
            Level.FAR -> "${targetName}在左边，请向左转动"
            Level.MID -> "${targetName}在您的左前方，请向左转，慢慢向前走"
            Level.NEAR -> "${targetName}就在左边很近，请向左转并伸手触摸"
        }
        Zone.RIGHT -> when (level) {
            Level.FAR -> "${targetName}在右边，请向右转动"
            Level.MID -> "${targetName}在您的右前方，请向右转，慢慢向前走"
            Level.NEAR -> "${targetName}就在右边很近，请向右转并伸手触摸"
        }
        Zone.CENTER -> when (level) {
            Level.FAR -> "${targetName}在正前方，请慢慢向前走"
            Level.MID -> "${targetName}在正前方，请继续向前走"
            Level.NEAR -> "${targetName}就在正前方很近，请放慢脚步，准备伸手"
        }
    }

    private fun arrivalPhrase(): String =
        "${targetName}就在您正前方很近，请伸手触摸。已找到请说「找到了」。"

    private fun say(text: String): Action.Say {
        lastPhrase = text
        return Action.Say(text)
    }

    private fun pickBest(detections: List<Detection>): Detection? =
        detections.filter { it.score >= SCORE_THRESHOLD }
            .maxByOrNull { it.heightRatio * 0.7f + it.score * 0.3f }

    private enum class Zone { LEFT, CENTER, RIGHT }
    private enum class Level { FAR, MID, NEAR }
}
