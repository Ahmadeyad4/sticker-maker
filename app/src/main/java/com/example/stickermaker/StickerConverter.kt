package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min

/** Implement this to support animated packs (see README: FFmpeg). */
interface AnimatedEncoder {
    /** Convert a GIF/video into animated WebP: 512x512, <= 10s, <= 500KB. */
    fun encode(context: Context, input: Uri, output: File)
}

object NotConfiguredEncoder : AnimatedEncoder {
    override fun encode(context: Context, input: Uri, output: File) {
        throw UnsupportedOperationException("Animated encoder not configured. See README.")
    }
}

object StickerConverter {
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96
    private const val MAX_STATIC_BYTES = 100 * 1024

    var animatedEncoder: AnimatedEncoder = NotConfiguredEncoder

    fun convertStatic(context: Context, input: Uri, output: File) {
        val src = context.contentResolver.openInputStream(input)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Could not read image")
        val bmp = fitOnTransparentSquare(src, STICKER_SIZE)
        for (quality in 90 downTo 10 step 10) {
            val bytes = ByteArrayOutputStream().also { bmp.compress(webpFormat(), quality, it) }.toByteArray()
            if (bytes.size <= MAX_STATIC_BYTES || quality == 10) {
                check(bytes.size <= MAX_STATIC_BYTES) { "Image is too complex to fit under 100KB" }
                output.writeBytes(bytes)
                return
            }
        }
    }

    fun convertAnimated(context: Context, input: Uri, output: File) =
        animatedEncoder.encode(context, input, output)

    fun makeTray(stickerFile: File, output: File) {
        val src = BitmapFactory.decodeFile(stickerFile.path) ?: error("Could not read sticker")
        val tray = fitOnTransparentSquare(src, TRAY_SIZE)
        output.outputStream().use { tray.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun fitOnTransparentSquare(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        Canvas(out).drawBitmap(src, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    @Suppress("DEPRECATION")
    private fun webpFormat() =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}
