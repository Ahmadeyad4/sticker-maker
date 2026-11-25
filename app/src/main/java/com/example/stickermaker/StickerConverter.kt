package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Converts a GIF / video / image into an animated WebP sticker. */
interface AnimatedEncoder {
    /** [startMs] / [lengthMs] choose the part of the clip to use (lengthMs 0 = from the start, as long as allowed). */
    fun encode(context: Context, input: Uri, output: File, startMs: Long, lengthMs: Long)
}

object NotConfiguredEncoder : AnimatedEncoder {
    override fun encode(context: Context, input: Uri, output: File, startMs: Long, lengthMs: Long) {
        throw UnsupportedOperationException("Animated encoder not configured.")
    }
}

object StickerConverter {
    const val STICKER_SIZE = 512
    const val TRAY_SIZE = 96
    private const val MAX_STATIC_BYTES = 100 * 1024 - 1024

    var animatedEncoder: AnimatedEncoder = NotConfiguredEncoder

    /**
     * Any readable image -> 512x512 WebP under 100KB.
     * Too big? Lowers quality first, then shrinks the picture inside the canvas.
     */
    fun convertStatic(context: Context, input: Uri, output: File) {
        val src = decode(context, input)
        for (scale in floatArrayOf(1f, 0.85f, 0.7f, 0.55f)) {
            val bmp = fitOnTransparentSquare(src, STICKER_SIZE, scale)
            for (quality in 90 downTo 10 step 10) {
                val bytes = ByteArrayOutputStream().also { bmp.compress(webpFormat(), quality, it) }.toByteArray()
                if (bytes.size <= MAX_STATIC_BYTES) {
                    output.writeBytes(bytes)
                    return
                }
            }
        }
        error("Image is too complex to fit under 100KB")
    }

    fun convertAnimated(context: Context, input: Uri, output: File, startMs: Long = 0L, lengthMs: Long = 0L) =
        animatedEncoder.encode(context, input, output, startMs, lengthMs)

    fun makeTray(stickerFile: File, output: File) {
        val src = BitmapFactory.decodeFile(stickerFile.path) ?: error("Could not read the tray source image")
        val tray = fitOnTransparentSquare(src, TRAY_SIZE)
        output.outputStream().use { tray.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Reads any image (big photos are downsampled first). ImageDecoder on Android 9+, BitmapFactory otherwise. */
    fun decode(context: Context, uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val big = max(info.size.width, info.size.height)
                    if (big > 2048) decoder.setTargetSampleSize(big / 1024)
                }
            } catch (e: Exception) {
                // fall through to BitmapFactory
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        return bmp ?: error("Could not read this file as an image (type: ${context.contentResolver.getType(uri)})")
    }

    /** Fits [src] inside a transparent size x size square, optionally shrunk by [contentScale]. */
    fun fitOnTransparentSquare(src: Bitmap, size: Int, contentScale: Float = 1f): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val fit = min(size.toFloat() / src.width, size.toFloat() / src.height) * contentScale
        val w = src.width * fit
        val h = src.height * fit
        val left = (size - w) / 2f
        val top = (size - h) / 2f
        Canvas(out).drawBitmap(src, null, RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    @Suppress("DEPRECATION")
    fun webpFormat() =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
}

data class StickerInfo(
    val width: Int,
    val height: Int,
    val frames: Int,
    val durationMs: Long,
    val minFrameMs: Int,
    val bytes: Long,
)

/** Checks a sticker file against WhatsApp's rules, reading the WebP structure directly. */
object StickerValidator {
    private const val MAX_STATIC = 100L * 1024
    private const val MAX_ANIMATED = 500L * 1024
    private const val MAX_DURATION_MS = 10_000L
    private const val MIN_FRAME_MS = 8

    fun inspect(file: File): StickerInfo {
        val f = file.readBytes()
        require(f.size > 20 && String(f, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(f, 8, 4, Charsets.US_ASCII) == "WEBP") { "not a WebP file" }
        var w = 0
        var h = 0
        var frames = 0
        var total = 0L
        var minMs = Int.MAX_VALUE
        var pos = 12
        while (pos + 8 <= f.size) {
            val id = String(f, pos, 4, Charsets.US_ASCII)
            val len = u32(f, pos + 4)
            if (len < 0) break
            val p = pos + 8
            when (id) {
                "VP8X" -> { w = 1 + u24(f, p + 4); h = 1 + u24(f, p + 7) }
                "ANMF" -> {
                    frames++
                    val d = u24(f, p + 12)
                    total += d
                    minMs = min(minMs, d)
                }
                "VP8 " -> if (w == 0) { w = u16(f, p + 6) and 0x3FFF; h = u16(f, p + 8) and 0x3FFF }
                "VP8L" -> if (w == 0) {
                    val bits = u32(f, p + 1)
                    w = (bits and 0x3FFF) + 1
                    h = ((bits shr 14) and 0x3FFF) + 1
                }
            }
            pos = p + len + (len and 1)
        }
        return StickerInfo(w, h, max(frames, 1), total, minMs, f.size.toLong())
    }

    /** Returns a description of the problem, or null if the file is fine. */
    fun check(file: File, animated: Boolean): String? {
        val info = try {
            inspect(file)
        } catch (e: Exception) {
            return "${file.name} could not be read (${e.message})"
        }
        val limit = if (animated) MAX_ANIMATED else MAX_STATIC
        return when {
            info.bytes > limit -> "${file.name} is too big (${info.bytes / 1024}KB)"
            info.width != 512 || info.height != 512 -> "${file.name} has the wrong size (${info.width}x${info.height})"
            animated && info.frames < 2 -> "${file.name} has only ${info.frames} frame, animated stickers need 2 or more"
            animated && info.minFrameMs < MIN_FRAME_MS -> "${file.name} has a frame shorter than 8ms"
            animated && info.durationMs > MAX_DURATION_MS -> "${file.name} is longer than 10 seconds"
            !animated && info.frames > 1 -> "${file.name} is animated but the pack is static"
            else -> null
        }
    }

    fun describe(file: File): String {
        val i = inspect(file)
        return "${file.name}: ${i.width}x${i.height}, ${i.frames} frame(s), ${i.durationMs / 100 / 10.0}s, ${i.bytes / 1024}KB"
    }

    private fun u16(f: ByteArray, i: Int) = (f[i].toInt() and 0xFF) or ((f[i + 1].toInt() and 0xFF) shl 8)
    private fun u24(f: ByteArray, i: Int) = u16(f, i) or ((f[i + 2].toInt() and 0xFF) shl 16)
    private fun u32(f: ByteArray, i: Int) = u24(f, i) or ((f[i + 3].toInt() and 0xFF) shl 24)
}
