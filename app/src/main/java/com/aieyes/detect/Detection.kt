package com.aieyes.detect

/**
 * A single detection result. Coordinates are normalized to 0..1 relative to the
 * upright camera frame.
 */
data class Detection(
    val classIndex: Int,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2f
    val widthRatio: Float get() = right - left
    val heightRatio: Float get() = bottom - top
}
