package com.example.depthwp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** Decodes a picked gallery image downsampled to [maxDimension] and corrects EXIF rotation. */
object BitmapUtils {

    /**
     * Decodes an image file that already lives in app storage, downsampled to roughly
     * [reqWidth] x [reqHeight]. Used by the wallpaper service, whose process typically has a
     * smaller heap than the editor: decoding a full-size photo there can silently OOM and return
     * null (which is exactly why images "disappeared" once set as wallpaper). [allowRgb565] halves
     * memory for opaque backgrounds; keep it false for the foreground PNG so transparency survives.
     */
    fun decodeFileForDisplay(path: String, reqWidth: Int, reqHeight: Int, allowRgb565: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(path, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val targetW = if (reqWidth > 0) reqWidth else bounds.outWidth
        val targetH = if (reqHeight > 0) reqHeight else bounds.outHeight
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetW && bounds.outHeight / (sampleSize * 2) >= targetH) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = if (allowRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, opts)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver

        // Read the bounds only. decodeStream returns null here by design (inJustDecodeBounds),
        // so the stream itself is the thing to null-check, NOT the decode result.
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        var sampleSize = 1
        val halfWidth = boundsOptions.outWidth / 2
        val halfHeight = boundsOptions.outHeight / 2
        while (halfWidth / sampleSize >= maxDimension || halfHeight / sampleSize >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decodeStream = resolver.openInputStream(uri) ?: return null
        val bitmap = decodeStream.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: return null

        return applyExifRotation(context, uri, bitmap)
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return bitmap

            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) {
            bitmap
        }
    }
}
