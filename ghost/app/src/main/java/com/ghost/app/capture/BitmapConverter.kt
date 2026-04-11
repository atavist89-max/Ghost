package com.ghost.app.capture

import android.graphics.Bitmap
import android.media.Image
import android.util.Log

/**
 * Utilities for converting Image objects to Bitmap.
 * Handles the conversion from ImageReader format to usable Bitmap.
 */
object BitmapConverter {

    private const val TAG = "GhostBitmap"

    /**
     * Convert an Image from ImageReader to a Bitmap.
     * The Image is expected to be in RGBA_8888 format.
     *
     * @param image The Image to convert
     * @return Bitmap in ARGB_8888 format, or null if conversion fails
     */
    fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            if (planes.isEmpty()) {
                Log.e(TAG, "No planes in image")
                return null
            }

            val buffer = planes[0].buffer
            val width = image.width
            val height = image.height
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            // Create bitmap with ARGB_8888 config
            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )

            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            // If there's row padding, crop to actual dimensions
            return if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }

    /**
     * Scale a bitmap to target dimensions while maintaining aspect ratio.
     *
     * @param bitmap Source bitmap
     * @param targetWidth Desired width
     * @param targetHeight Desired height
     * @return Scaled bitmap
     */
    fun scaleBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        }
    }

    /**
     * Create a thumbnail from a full-size bitmap.
     *
     * @param bitmap Source bitmap
     * @param maxDimension Maximum width or height
     * @return Thumbnail bitmap
     */
    fun createThumbnail(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        return if (width <= maxDimension && height <= maxDimension) {
            bitmap
        } else {
            val ratio = width.toFloat() / height.toFloat()
            val (newWidth, newHeight) = if (width > height) {
                Pair(maxDimension, (maxDimension / ratio).toInt())
            } else {
                Pair((maxDimension * ratio).toInt(), maxDimension)
            }
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }
    }

    /**
     * Safely recycle a bitmap if not already recycled.
     *
     * @param bitmap Bitmap to recycle
     */
    fun safeRecycle(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recycling bitmap", e)
        }
    }
}
