package com.example.aiapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Longest side of a stored picture. Small enough for a phone to read quickly. */
private const val MAX_EDGE = 768

private fun imageFile(context: Context, name: String): File =
    File(LlamaBackend.imageDir(context), File(name).name)

/**
 * Copies a picked picture into the app's private storage as a small JPEG (right way up,
 * longest side 768 px) and returns its file name, or null if it couldn't be read.
 * Call this off the main thread.
 */
fun saveSelectedImage(context: Context, uri: Uri): String? {
    return try {
        val resolver = context.contentResolver

        // A picture whose EXIF block can't be read is still fine: assume it is the right way up.
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decode at a reduced size first so a 50-megapixel photo doesn't fill the phone's memory.
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val scale = MAX_EDGE / max(bmp.width, bmp.height).toFloat()
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).roundToInt().coerceAtLeast(1),
                (bmp.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)

        // JPEG has no see-through parts: paint on white so transparent PNGs don't turn black.
        val flat = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(AndroidColor.WHITE)
        canvas.drawBitmap(upright, 0f, 0f, null)

        val dir = LlamaBackend.imageDir(context)
        dir.mkdirs()
        val name = "img_${System.currentTimeMillis()}_${(0..9999).random()}.jpg"
        FileOutputStream(File(dir, name)).use { flat.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        name
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}

fun deleteImage(context: Context, name: String) {
    try { imageFile(context, name).delete() } catch (e: Exception) { /* nothing to do */ }
}

/**
 * Deletes stored pictures that no chat uses (for example one that was picked but never sent
 * before the app was closed). Pictures younger than an hour are left alone. [keep] holds the file
 * names that are still in use. Call this off the main thread.
 */
fun cleanUpImages(context: Context, keep: Set<String>) {
    try {
        val oldEnough = System.currentTimeMillis() - 60L * 60L * 1000L
        LlamaBackend.imageDir(context).listFiles()?.forEach { f ->
            if (f.name !in keep && f.lastModified() < oldEnough) f.delete()
        }
    } catch (e: Exception) {
        // best effort
    }
}

/** A small version of a stored picture for showing in the chat. */
fun loadThumbnail(context: Context, name: String, maxEdge: Int = 480): ImageBitmap? {
    return try {
        val f = imageFile(context, name)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
