package com.aieyes.util

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Converts an RGBA_8888 ImageProxy from CameraX into an upright (display-oriented) Bitmap.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val rowPaddingPixels = if (pixelStride > 0) (rowStride - pixelStride * width) / pixelStride else 0

    var bitmap = Bitmap.createBitmap(width + rowPaddingPixels, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(buffer)
    if (rowPaddingPixels != 0) {
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        bitmap = cropped
    }

    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return bitmap

    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bitmap.recycle()
    return rotated
}
