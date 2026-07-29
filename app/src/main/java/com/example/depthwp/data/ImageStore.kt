package com.example.depthwp.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ImageStore {

    private const val BACKGROUND_FILENAME = "background_image.jpg"
    private const val FOREGROUND_FILENAME = "foreground_image.png"
    private const val BACKGROUND_MAX_DIMENSION = 2048
    private const val FOREGROUND_MAX_DIMENSION = 1536

    private fun imageDir(context: Context, subDir: String?): File {
        val base = context.filesDir
        if (subDir == null) return base
        val dir = File(base, subDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveBackgroundImage(context: Context, uri: Uri, subDir: String? = null): String? {
        val bitmap = BitmapUtils.decodeSampledBitmap(context, uri, BACKGROUND_MAX_DIMENSION) ?: return null
        val file = File(imageDir(context, subDir), BACKGROUND_FILENAME)
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
            file.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun saveForegroundImage(context: Context, uri: Uri, subDir: String? = null): String? {
        val bitmap = BitmapUtils.decodeSampledBitmap(context, uri, FOREGROUND_MAX_DIMENSION) ?: return null
        val file = File(imageDir(context, subDir), FOREGROUND_FILENAME)
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            file.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun copyFile(src: String, destDir: File, destName: String): String? {
        val srcFile = File(src)
        if (!srcFile.exists()) return null
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, destName)
        return try {
            srcFile.copyTo(destFile, overwrite = true)
            destFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun deletePresetImages(context: Context, presetId: String) {
        val dir = File(context.filesDir, "presets/$presetId")
        if (dir.exists()) dir.deleteRecursively()
    }
}
