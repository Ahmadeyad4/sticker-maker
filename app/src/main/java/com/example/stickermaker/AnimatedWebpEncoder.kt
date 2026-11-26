package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

private const val SIZE = 512
private const val MAX_BYTES = 500 * 1024 - 2048
private const val MAX_DURATION_MS = 9_500L
private const val MIN_DURATION_MS = 1_000L

/**
 * Animated WebP encoder with no native libraries.
 * Accepts GIF, video, or even a plain image, and adjusts it to WhatsApp's rules:
 * 512x512, under 500KB, under 10s, at least 2 frames.
 */
class AnimatedWebpEncoder : AnimatedEncoder {

    private data class Attempt(val fps: Int, val quality: Int, val scale: Float)

    // Tried in order until the result fits under 500KB.
    private val attempts = listOf(
        Attempt(15, 70, 1f), Attempt(12, 60, 1f), Attempt(10, 50, 1f), Attempt(10, 35, 1f),
        Attempt(8, 30, 0.9f), Attempt(8, 20, 0.8f), Attempt(6, 15, 0.7f), Attempt(5, 10, 0.6f),
    )

    override fun encode(context: Context, input: Uri, output: File, startMs: Long, lengthMs: Long, caption: String) {
        val base = openSource(context, input)
        val source = if (startMs > 0 || lengthMs > 0) TrimmedSource(base, startMs, lengthMs) else base
        encodeSource(source, output, caption)
    }

    /** Encodes frames that were already decoded to a raw RGBA file (512x512 pixels, [fps] frames per second). */
    fun encodeRaw(rawFile: File, fps: Int, output: File) {
        encodeSource(RawSource(rawFile, fps), output, "")
    }

    /** Renders a Telegram TGS (Lottie) animation, with transparency, into an animated WebP. */
    fun encodeLottie(composition: LottieComposition, output: File) {
        encodeSource(LottieSource(composition), output, "")
    }

    private fun encodeSource(source: FrameSource, output: File, caption: String) {
        try {
            val durationMs = if (source.isStill) MIN_DURATION_MS
            else min(max(source.durationMs, MIN_DURATION_MS), MAX_DURATION_MS)

            // Still image of the first frame (used for the pack's tray icon).
            source.frameAt(0, 1f)?.let { first ->
                val still = File(output.parentFile, output.nameWithoutExtension + "_still.png")
                still.outputStream().use { first.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            for (a in attempts) {
                val webp = tryEncode(source, durationMs, a, caption) ?: continue
                output.writeBytes(webp)
                StickerValidator.check(output, animated = true)?.let { error(it) }
                return
            }
            error("Could not fit the animation under 500KB, even at low quality. Try a shorter clip.")
        } finally {
            source.close()
        }
    }

    private fun compress(bmp: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(StickerConverter.webpFormat(), quality, it) }.toByteArray()

    private fun tryEncode(source: FrameSource, durationMs: Long, a: Attempt, caption: String): ByteArray? {
        if (source.isStill) {
            // A plain image becomes a 2-frame animation (WhatsApp needs more than one frame).
            val bmp = source.frameAt(0, a.scale) ?: return null
            Caption.draw(bmp, caption)
            val bytes = compress(bmp, a.quality)
            bmp.recycle()
            val out = WebpMuxer.mux(listOf(bytes, bytes), SIZE, 500)
            return if (out.size <= MAX_BYTES) out else null
        }

        val frameMs = 1000 / a.fps
        val count = max(2, (durationMs / frameMs).toInt())
        val frames = ArrayList<ByteArray>(count)
        var total = 0
        for (i in 0 until count) {
            val bmp = source.frameAt(i.toLong() * frameMs, a.scale) ?: continue
            Caption.draw(bmp, caption)
            val bytes = compress(bmp, a.quality)
            bmp.recycle()
            total += bytes.size
            if (total > MAX_BYTES) return null
            frames.add(bytes)
        }
        if (frames.size < 2) return null
        val out = WebpMuxer.mux(frames, SIZE, frameMs)
        return if (out.size <= MAX_BYTES) out else null
    }

    // ---------- Frame sources ----------

    private interface FrameSource {
        val durationMs: Long
        val isStill: Boolean get() = false
        fun frameAt(ms: Long, scale: Float): Bitmap?
        fun close() {}
    }

    private fun openSource(context: Context, uri: Uri): FrameSource {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type == "image/gif" -> try {
                GifSource(context, uri)
            } catch (e: Exception) {
                StillSource(context, uri)   // not really animated: treat as a normal image
            }
            type.startsWith("video/") -> VideoSource(context, uri)
            type.startsWith("image/") -> StillSource(context, uri)
            else -> try {
                VideoSource(context, uri)
            } catch (e: Exception) {
                StillSource(context, uri)
            }
        }
    }

    @Suppress("DEPRECATION")
    private class GifSource(context: Context, uri: Uri) : FrameSource {
        private val movie: Movie

        init {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read GIF")
            movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid GIF")
            if (movie.width() <= 0 || movie.height() <= 0 || movie.duration() <= 0) error("GIF is not animated")
        }

        override val durationMs: Long = movie.duration().toLong()

        override fun frameAt(ms: Long, scale: Float): Bitmap? {
            movie.setTime((ms % durationMs).toInt())
            val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val s = min(SIZE.toFloat() / movie.width(), SIZE.toFloat() / movie.height()) * scale
            canvas.translate((SIZE - movie.width() * s) / 2f, (SIZE - movie.height() * s) / 2f)
            canvas.scale(s, s)
            movie.draw(canvas, 0f, 0f)
            return out
        }
    }

    private class VideoSource(context: Context, uri: Uri) : FrameSource {
        private val retriever = MediaMetadataRetriever()
        override val durationMs: Long

        init {
            try {
                retriever.setDataSource(context, uri)
            } catch (e: Exception) {
                retriever.release()
                throw IllegalStateException("Could not read this file as a video")
            }
            val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (d == null) {
                retriever.release()
                error("Could not read this file as a video")
            }
            durationMs = max(d, 1L)
        }

        override fun frameAt(ms: Long, scale: Float): Bitmap? {
            val t = ms % durationMs   // short clips loop
            val raw = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val out = StickerConverter.fitOnTransparentSquare(raw, SIZE, scale)
            raw.recycle()
            return out
        }

        override fun close() {
            retriever.release()
        }
    }

    private class StillSource(context: Context, uri: Uri) : FrameSource {
        private val bitmap: Bitmap = StickerConverter.decode(context, uri)
        override val durationMs: Long = MIN_DURATION_MS
        override val isStill: Boolean = true

        override fun frameAt(ms: Long, scale: Float): Bitmap? =
            StickerConverter.fitOnTransparentSquare(bitmap, SIZE, scale)
    }

    /** Only the chosen part of another source (it loops inside that part if the sticker is longer). */
    private class TrimmedSource(private val inner: FrameSource, private val startMs: Long, lengthMs: Long) : FrameSource {
        override val durationMs: Long

        init {
            val available = (inner.durationMs - startMs).coerceAtLeast(1L)
            durationMs = if (lengthMs > 0) min(lengthMs, available) else available
        }

        override val isStill: Boolean get() = inner.isStill

        override fun frameAt(ms: Long, scale: Float): Bitmap? = inner.frameAt(startMs + ms % durationMs, scale)

        override fun close() = inner.close()
    }

    /** A Lottie animation drawn frame by frame onto a transparent picture. */
    private class LottieSource(composition: LottieComposition) : FrameSource {
        private val drawable = LottieDrawable()
        override val durationMs: Long = composition.duration.toLong().coerceAtLeast(1L)

        init {
            drawable.setComposition(composition)
            drawable.setBounds(0, 0, SIZE, SIZE)
        }

        override fun frameAt(ms: Long, scale: Float): Bitmap? {
            drawable.setProgress((ms % durationMs).toFloat() / durationMs)
            val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            if (scale < 0.999f) {
                val offset = SIZE * (1f - scale) / 2f
                canvas.translate(offset, offset)
                canvas.scale(scale, scale)
            }
            drawable.draw(canvas)
            return bmp
        }
    }

    /** Frames stored back to back as raw RGBA (with transparency) in one file. */
    private class RawSource(file: File, private val fps: Int) : FrameSource {
        private val frameBytes = SIZE * SIZE * 4
        private val count = (file.length() / frameBytes).toInt()

        init {
            if (count < 2) error("Not enough frames")
        }

        private val access = RandomAccessFile(file, "r")
        override val durationMs: Long = count * 1000L / fps

        override fun frameAt(ms: Long, scale: Float): Bitmap? {
            val index = ((ms * fps / 1000) % count).toInt()
            val bytes = ByteArray(frameBytes)
            access.seek(index.toLong() * frameBytes)
            access.readFully(bytes)
            val px = IntArray(SIZE * SIZE)
            var o = 0
            for (i in px.indices) {
                val r = bytes[o].toInt() and 0xFF
                val g = bytes[o + 1].toInt() and 0xFF
                val b = bytes[o + 2].toInt() and 0xFF
                val a = bytes[o + 3].toInt() and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                o += 4
            }
            val frame = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            frame.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            if (scale >= 0.999f) return frame
            val scaled = StickerConverter.fitOnTransparentSquare(frame, SIZE, scale)
            frame.recycle()
            return scaled
        }

        override fun close() {
            access.close()
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
