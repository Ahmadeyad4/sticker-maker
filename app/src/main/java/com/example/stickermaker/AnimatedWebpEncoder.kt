package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val SIZE = 512
private const val MAX_BYTES = 500 * 1024 - 2048
private const val MAX_DURATION_MS = 9_500L

/**
 * Animated WebP encoder with no native libraries.
 * Each frame is compressed as lossy WebP by Android, then the frames are
 * muxed into one animated WebP container (what WhatsApp expects).
 * Input: GIF or video (mp4, webm...).
 */
class AnimatedWebpEncoder : AnimatedEncoder {

    private data class Attempt(val fps: Int, val quality: Int)

    // Tried in order until the result fits under 500KB.
    private val attempts = listOf(
        Attempt(15, 70), Attempt(12, 60), Attempt(10, 50),
        Attempt(10, 35), Attempt(8, 30), Attempt(8, 20), Attempt(6, 15),
    )

    override fun encode(context: Context, input: Uri, output: File) {
        val source = openSource(context, input)
        try {
            val durationMs = min(source.durationMs, MAX_DURATION_MS).coerceAtLeast(100L)

            // Still image of the first frame (used for the pack's tray icon).
            source.frameAt(0)?.let { first ->
                val still = File(output.parentFile, output.nameWithoutExtension + "_still.png")
                still.outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            for (a in attempts) {
                val webp = tryEncode(source, durationMs, a) ?: continue
                output.writeBytes(webp)
                return
            }
            error("Could not fit the animation under 500KB. Try a shorter or simpler clip.")
        } finally {
            source.close()
        }
    }

    private fun tryEncode(source: FrameSource, durationMs: Long, a: Attempt): ByteArray? {
        val frameMs = 1000 / a.fps
        val count = max(1, (durationMs / frameMs).toInt())
        val frames = ArrayList<ByteArray>(count)
        var total = 0
        for (i in 0 until count) {
            val bmp = source.frameAt(i.toLong() * frameMs) ?: continue
            val bytes = ByteArrayOutputStream().also { bmp.compress(webpFormat(), a.quality, it) }.toByteArray()
            bmp.recycle()
            total += bytes.size
            if (total > MAX_BYTES) return null
            frames.add(bytes)
        }
        if (frames.isEmpty()) return null
        val out = WebpMuxer.mux(frames, SIZE, frameMs)
        return if (out.size <= MAX_BYTES) out else null
    }

    @Suppress("DEPRECATION")
    private fun webpFormat() =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    // ---------- Frame sources ----------

    private interface FrameSource {
        val durationMs: Long
        fun frameAt(ms: Long): Bitmap?
        fun close()
    }

    private fun openSource(context: Context, uri: Uri): FrameSource {
        val type = context.contentResolver.getType(uri) ?: ""
        return if (type == "image/gif") GifSource(context, uri) else VideoSource(context, uri)
    }

    @Suppress("DEPRECATION")
    private class GifSource(context: Context, uri: Uri) : FrameSource {
        private val movie: Movie

        init {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read GIF")
            movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid GIF")
            if (movie.width() <= 0 || movie.height() <= 0) error("Invalid GIF")
        }

        override val durationMs: Long = movie.duration().toLong().let { if (it <= 0) 100L else it }

        override fun frameAt(ms: Long): Bitmap? {
            movie.setTime((ms % durationMs).toInt())
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val scale = min(SIZE.toFloat() / movie.width(), SIZE.toFloat() / movie.height())
            canvas.translate((SIZE - movie.width() * scale) / 2f, (SIZE - movie.height() * scale) / 2f)
            canvas.scale(scale, scale)
            movie.draw(canvas, 0f, 0f)
            return out
        }

        override fun close() {}
    }

    private class VideoSource(context: Context, uri: Uri) : FrameSource {
        private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

        override val durationMs: Long =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

        override fun frameAt(ms: Long): Bitmap? {
            val raw = retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val scale = min(SIZE.toFloat() / raw.width, SIZE.toFloat() / raw.height)
            val w = raw.width * scale
            val h = raw.height * scale
            val left = (SIZE - w) / 2f
            val top = (SIZE - h) / 2f
            Canvas(out).drawBitmap(raw, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            raw.recycle()
            return out
        }

        override fun close() {
            retriever.release()
        }
    }
}

/** Builds an animated WebP file from single-frame WebP files. */
private object WebpMuxer {

    fun mux(frames: List<ByteArray>, size: Int, frameMs: Int): ByteArray {
        var hasAlpha = false
        val anmf = ByteArrayOutputStream()
        for (frame in frames) {
            val (data, alpha) = parseFrame(frame)
            hasAlpha = hasAlpha || alpha
            val payload = ByteArrayOutputStream().apply {
                le(0, 3); le(0, 3)                 // frame x, y (divided by 2)
                le(size - 1, 3); le(size - 1, 3)   // frame width-1, height-1
                le(frameMs, 3)                     // duration in ms
                write(0x02)                        // do not blend, do not dispose
                write(data)
            }.toByteArray()
            chunk(anmf, "ANMF", payload)
        }

        val body = ByteArrayOutputStream()
        val vp8x = ByteArrayOutputStream().apply {
            write(0x02 or (if (hasAlpha) 0x10 else 0)) // animation (+ alpha) flags
            le(0, 3)
            le(size - 1, 3); le(size - 1, 3)           // canvas width-1, height-1
        }.toByteArray()
        chunk(body, "VP8X", vp8x)
        val anim = ByteArrayOutputStream().apply {
            le(0, 4)   // background color (transparent)
            le(0, 2)   // loop count: 0 = forever
        }.toByteArray()
        chunk(body, "ANIM", anim)
        body.write(anmf.toByteArray())

        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            le(4 + body.size(), 4)
            write("WEBP".toByteArray(Charsets.US_ASCII))
            write(body.toByteArray())
        }.toByteArray()
    }

    /** Returns the ALPH/VP8/VP8L chunks of a single-frame WebP, and whether it has alpha. */
    private fun parseFrame(f: ByteArray): Pair<ByteArray, Boolean> {
        require(f.size > 12 && String(f, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(f, 8, 4, Charsets.US_ASCII) == "WEBP") { "Bad WebP frame" }
        val out = ByteArrayOutputStream()
        var alpha = false
        var pos = 12
        while (pos + 8 <= f.size) {
            val id = String(f, pos, 4, Charsets.US_ASCII)
            val len = (f[pos + 4].toInt() and 0xFF) or
                ((f[pos + 5].toInt() and 0xFF) shl 8) or
                ((f[pos + 6].toInt() and 0xFF) shl 16) or
                ((f[pos + 7].toInt() and 0xFF) shl 24)
            val padded = len + (len and 1)
            val end = min(pos + 8 + padded, f.size)
            if (id == "ALPH" || id == "VP8 " || id == "VP8L") {
                out.write(f, pos, end - pos)
                if (id == "ALPH" || id == "VP8L") alpha = true
            }
            pos += 8 + padded
        }
        return Pair(out.toByteArray(), alpha)
    }

    private fun chunk(out: ByteArrayOutputStream, id: String, payload: ByteArray) {
        out.write(id.toByteArray(Charsets.US_ASCII))
        out.le(payload.size, 4)
        out.write(payload)
        if ((payload.size and 1) == 1) out.write(0)
    }

    private fun ByteArrayOutputStream.le(value: Int, bytes: Int) {
        for (i in 0 until bytes) write((value shr (8 * i)) and 0xFF)
    }
}
