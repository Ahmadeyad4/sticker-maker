package com.example.stickermaker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

private const val TG_SIZE = 512
private const val TG_FPS = 30
private const val TG_MAX_MS = 2_900L
private const val TG_MAX_VIDEO_BYTES = 250_000L

/** Draws [src] on a black background (video has no transparency) and recycles [src]. */
private fun tgFlatten(src: Bitmap): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    canvas.drawBitmap(src, 0f, 0f, null)
    src.recycle()
    return out
}

// =====================================================================
//  Telegram Bot API client
// =====================================================================

/** Thrown when Telegram cannot be reached at all (blocked, no internet, VPN not covering the app...). */
class TelegramNetworkException(message: String) : Exception(message)

private fun tgErrorText(e: Throwable): String =
    if (e is TelegramNetworkException) Lang.t("tg_blocked")
    else Lang.t("error_with", e.message ?: e.javaClass.simpleName)

object TelegramApi {
    /** Calls a Bot API method and returns the JSON answer. Throws with Telegram's own message on errors. */
    fun call(
        token: String,
        method: String,
        fields: Map<String, String> = emptyMap(),
        files: Map<String, File> = emptyMap(),
    ): JSONObject {
        val conn = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000

            // Plain form for simple calls; multipart (built in memory, with a fixed length) only when sending files.
            val payload: ByteArray
            if (files.isEmpty()) {
                payload = fields.entries.joinToString("&") {
                    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
                }.toByteArray(Charsets.UTF_8)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            } else {
                val boundary = "----stickermaker" + UUID.randomUUID().toString().replace("-", "")
                val buf = ByteArrayOutputStream()
                fun text(s: String) = buf.write(s.toByteArray(Charsets.UTF_8))
                for ((k, v) in fields) {
                    text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                }
                for ((k, f) in files) {
                    text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"; filename=\"${f.name}\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n")
                    buf.write(f.readBytes())
                    text("\r\n")
                }
                text("--$boundary--\r\n")
                payload = buf.toByteArray()
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val answer = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(answer)
            } catch (e: Exception) {
                throw IllegalStateException("Unexpected answer from Telegram (HTTP $code): ${answer.take(150)}")
            }
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("description", "Telegram error (HTTP $code)"))
            }
            return json
        } catch (e: java.io.IOException) {
            throw TelegramNetworkException(e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }
}

// =====================================================================
//  Building a pack
// =====================================================================

data class TelegramResult(val link: String, val note: String)

object TelegramPackBuilder {
    fun create(
        context: Context,
        token: String,
        userId: Long,
        botUsername: String,
        title: String,
        animated: Boolean,
        emoji: String,
        uris: List<Uri>,
        trims: Map<Uri, Trim>,
        progress: (String) -> Unit,
    ): TelegramResult {
        val dir = File(context.cacheDir, "tg_" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val files = ArrayList<File>()
            val skipped = ArrayList<String>()
            var codecUsed = ""
            uris.forEachIndexed { i, uri ->
                progress(Lang.t("converting", i + 1, uris.size))
                val out = File(dir, "s${files.size}." + (if (animated) "webm" else "webp"))
                try {
                    if (animated) codecUsed = TelegramVideoEncoder.encode(context, uri, out, trims[uri]?.startMs ?: 0L, trims[uri]?.lengthMs ?: 0L)
                    else TelegramStatic.convert(context, uri, out)
                    files.add(out)
                } catch (e: Exception) {
                    out.delete()
                    skipped.add(Lang.t("file_err", i + 1, e.message ?: e.javaClass.simpleName))
                }
            }
            if (files.isEmpty()) error(Lang.t("tg_no_files", skipped.joinToString("; ")))

            val name = "pack" + UUID.randomUUID().toString().replace("-", "").take(10) +
                "_by_" + botUsername.lowercase()
            val format = if (animated) "video" else "static"
            val stickers = JSONArray()
            val fileMap = LinkedHashMap<String, File>()
            files.forEachIndexed { i, f ->
                stickers.put(
                    JSONObject()
                        .put("sticker", "attach://file$i")
                        .put("format", format)
                        .put("emoji_list", JSONArray().put(emoji)),
                )
                fileMap["file$i"] = f
            }

            progress(Lang.t("uploading", files.size))
            try {
                TelegramApi.call(
                    token, "createNewStickerSet",
                    mapOf(
                        "user_id" to userId.toString(),
                        "name" to name,
                        "title" to title.take(64),
                        "stickers" to stickers.toString(),
                    ),
                    fileMap,
                )
            } catch (e: Exception) {
                if (codecUsed == "VP8" && (e.message ?: "").contains("STICKER_VIDEO")) {
                    throw IllegalStateException("${e.message} (this phone can only encode VP8, and Telegram asks for VP9)")
                }
                throw e
            }
            val note = if (skipped.isEmpty()) "" else Lang.t("tg_skipped", skipped.size, skipped.joinToString("; "))
            return TelegramResult("https://t.me/addstickers/$name", note)
        } finally {
            dir.deleteRecursively()
        }
    }
}

// =====================================================================
//  Static stickers: WebP 512x512, up to 512KB
// =====================================================================

object TelegramStatic {
    fun convert(context: Context, input: Uri, output: File) {
        val src = StickerConverter.decode(context, input)
        val bmp = StickerConverter.fitOnTransparentSquare(src, TG_SIZE)
        for (quality in intArrayOf(95, 85, 70, 55, 40, 25)) {
            val bytes = ByteArrayOutputStream().also { bmp.compress(StickerConverter.webpFormat(), quality, it) }.toByteArray()
            if (bytes.size <= 500_000) {
                output.writeBytes(bytes)
                return
            }
        }
        error("Image is too complex to fit under 512KB")
    }
}

// =====================================================================
//  Animated stickers (Telegram "video stickers"): WebM VP9, 512x512,
//  up to 3 seconds, up to 256KB, 30 fps, no audio.
//  Encoded with the bundled FFmpeg (libvpx VP9, keeps transparency);
//  falls back to the phone's own encoder (MediaCodec) if FFmpeg cannot load.
// =====================================================================

object TelegramVideoEncoder {
    private val bitrates = intArrayOf(600_000, 450_000, 330_000, 240_000, 170_000, 110_000)

    private const val CF_FLEX = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    private const val CF_PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    private const val CF_SEMI = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    private class Enc(val name: String, val mime: String, val colorFormat: Int)

    private fun videoEncoders() =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }

    /** Prefers VP9 (what Telegram asks for), then VP8. Needs an encoder that accepts plain YUV frames. */
    private fun findEncoder(): Enc? {
        val encoders = videoEncoders()
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_VP9, MediaFormat.MIMETYPE_VIDEO_VP8)) {
            for (info in encoders) {
                if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val colors = try {
                    info.getCapabilitiesForType(mime).colorFormats
                } catch (e: Exception) {
                    continue
                }
                val cf = when {
                    colors.contains(CF_FLEX) -> CF_FLEX
                    colors.contains(CF_PLANAR) -> CF_PLANAR
                    colors.contains(CF_SEMI) -> CF_SEMI
                    else -> continue
                }
                return Enc(info.name, mime, cf)
            }
        }
        return null
    }

    private fun encoderReport(): String {
        val types = videoEncoders().flatMap { it.supportedTypes.toList() }
            .filter { it.startsWith("video/") }.distinct()
        return if (types.isEmpty()) "none" else types.joinToString(", ")
    }

    fun isSupported(): Boolean = findEncoder() != null

    /** True when the bundled FFmpeg (with libvpx) loads on this phone. */
    private val ffmpegOk: Boolean by lazy {
        try {
            ReturnCode.isSuccess(FFmpegKit.execute("-version").returnCode)
        } catch (t: Throwable) {
            false
        }
    }

    private var rawProbe: Boolean? = null

    /** The free FFmpeg build has no PNG decoder, so test whether it can read raw RGBA frames instead. */
    private fun rawInputWorks(context: Context): Boolean {
        rawProbe?.let { return it }
        val result = ffmpegOk && (try {
            val dir = File(context.cacheDir, "tgprobe").apply { mkdirs() }
            val raw = File(dir, "probe.rgba")
            val out = File(dir, "probe.webm")
            raw.writeBytes(ByteArray(64 * 64 * 4 * 2))
            out.delete()
            val session = FFmpegKit.execute(
                "-y -f rawvideo -pixel_format rgba -video_size 64x64 -framerate 30 -i ${raw.path} " +
                    "-c:v libvpx-vp9 -pix_fmt yuva420p -auto-alt-ref 0 -b:v 100k ${out.path}",
            )
            val ok = ReturnCode.isSuccess(session.returnCode) && out.length() > 0
            dir.deleteRecursively()
            ok
        } catch (t: Throwable) {
            false
        })
        rawProbe = result
        return result
    }

    private fun writeRawFrame(bmp: Bitmap, out: java.io.OutputStream) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val bytes = ByteArray(w * h * 4)
        var o = 0
        for (c in px) {
            bytes[o++] = (c shr 16).toByte()
            bytes[o++] = (c shr 8).toByte()
            bytes[o++] = c.toByte()
            bytes[o++] = (c ushr 24).toByte()
        }
        out.write(bytes)
    }

    /**
     * Makes a Telegram video sticker (WebM VP9, 512x512, up to 3s, under 256KB).
     * 1) Best: frames go to the bundled FFmpeg/libvpx as raw RGBA (keeps transparency).
     * 2) Else: the phone's own encoder makes a temporary video and FFmpeg turns it into VP9.
     * 3) Else: the phone's own encoder only (VP9 if it has one, otherwise VP8 which Telegram rejects).
     * Returns "VP9", or "VP8" in the last case.
     */
    fun encode(context: Context, input: Uri, output: File, startMs: Long = 0L, lengthMs: Long = 0L): String {
        val ffOk = ffmpegOk
        val rawMode = ffOk && rawInputWorks(context)
        val enc = if (rawMode) null else findEncoder()
        if (!rawMode && enc == null) {
            throw IllegalStateException(
                "This phone can't make WebM video. FFmpeg loaded: $ffOk. Video encoders found: ${encoderReport()}",
            )
        }

        val workDir = File(context.cacheDir, "tgv_" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val rawFile = File(workDir, "frames.rgba")
            val rawOut = if (rawMode) rawFile.outputStream().buffered() else null
            val yuv = ArrayList<ByteArray>()
            var count = 0
            val base = openSource(context, input)
            val source = if (startMs > 0 || lengthMs > 0) TgTrimmedSource(base, startMs, lengthMs) else base
            try {
                val durationMs = if (source.isStill) 1000L else min(max(source.durationMs, 1000L), TG_MAX_MS)
                val total = max(2, (durationMs * TG_FPS / 1000).toInt())
                for (i in 0 until total) {
                    if (!rawMode && source.isStill && yuv.isNotEmpty()) {
                        yuv.add(yuv[0])
                        count++
                        continue
                    }
                    val bmp = source.frameAt(i * 1000L / TG_FPS) ?: continue
                    if (rawOut != null) {
                        writeRawFrame(bmp, rawOut)
                        bmp.recycle()
                    } else {
                        val flat = tgFlatten(bmp)
                        yuv.add(toI420(flat))
                        flat.recycle()
                    }
                    count++
                }
            } finally {
                source.close()
                rawOut?.close()
            }
            if (count < 2) error("Could not read enough frames from this file")

            if (rawMode) {
                return encodeWithFfmpeg(
                    "-f rawvideo -pixel_format rgba -video_size ${TG_SIZE}x$TG_SIZE -framerate $TG_FPS -i ${rawFile.path}",
                    "yuva420p", output,
                )
            }

            val e = enc ?: error("No video encoder available")
            if (e.mime != MediaFormat.MIMETYPE_VIDEO_VP9 && ffOk) {
                // The phone can only make VP8: use it for a temporary video, then let FFmpeg re-encode to VP9.
                val mid = File(workDir, "mid.webm")
                encodeYuv(e, yuv, 3_000_000, mid)
                if (mid.length() == 0L) error("The phone's encoder produced no video")
                return encodeWithFfmpeg("-i ${mid.path}", "yuv420p", output)
            }

            for (bitrate in bitrates) {
                output.delete()
                encodeYuv(e, yuv, bitrate, output)
                if (output.length() in 1..TG_MAX_VIDEO_BYTES) {
                    return if (e.mime == MediaFormat.MIMETYPE_VIDEO_VP9) "VP9" else "VP8"
                }
            }
            output.delete()
            error("Could not fit the animation under 256KB. Try a shorter or simpler clip.")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun encodeWithFfmpeg(inputArgs: String, pixFmt: String, output: File): String {
        for (kbps in intArrayOf(500, 380, 280, 200, 140, 90)) {
            output.delete()
            val command = "-y $inputArgs -an -c:v libvpx-vp9 -pix_fmt $pixFmt -auto-alt-ref 0 " +
                "-b:v ${kbps}k -crf 34 -deadline good -cpu-used 4 -row-mt 1 ${output.path}"
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                throw IllegalStateException("FFmpeg failed: " + (session.output ?: "").takeLast(300))
            }
            if (output.length() in 1..TG_MAX_VIDEO_BYTES) return "VP9"
        }
        output.delete()
        error("Could not fit the animation under 256KB. Try a shorter or simpler clip.")
    }

    private fun encodeYuv(enc: Enc, frames: List<ByteArray>, bitrate: Int, out: File) {
        val format = MediaFormat.createVideoFormat(enc.mime, TG_SIZE, TG_SIZE).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, enc.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, TG_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val c = MediaCodec.createByCodecName(enc.name)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            muxer = m

            val info = MediaCodec.BufferInfo()
            var track = -1
            var nextFrame = 0
            var eosSent = false
            var finished = false
            val deadline = System.currentTimeMillis() + 90_000

            while (!finished) {
                if (System.currentTimeMillis() > deadline) throw IllegalStateException("Video encoding timed out")

                if (!eosSent) {
                    val inIndex = c.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val pts = nextFrame * 1_000_000L / TG_FPS
                        if (nextFrame < frames.size) {
                            val yuv = frames[nextFrame]
                            fillInput(c, inIndex, yuv, enc.colorFormat)
                            c.queueInputBuffer(inIndex, 0, yuv.size, pts, 0)
                            nextFrame++
                        } else {
                            c.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                }

                val outIndex = c.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val buf = c.getOutputBuffer(outIndex)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        m.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) finished = true
                }
            }
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
        }
    }

    /** Copies one planar I420 frame into the encoder's input buffer, in whatever layout it wants. */
    private fun fillInput(codec: MediaCodec, index: Int, yuv: ByteArray, colorFormat: Int) {
        val w = TG_SIZE
        val h = TG_SIZE
        if (colorFormat == CF_FLEX) {
            val image = codec.getInputImage(index)
            if (image != null) {
                val planes = image.planes
                copyPlane(planes[0], yuv, 0, w, h)
                copyPlane(planes[1], yuv, w * h, w / 2, h / 2)
                copyPlane(planes[2], yuv, w * h + w * h / 4, w / 2, h / 2)
                return
            }
        }
        val buf = codec.getInputBuffer(index) ?: throw IllegalStateException("The encoder did not give an input buffer")
        buf.clear()
        if (colorFormat == CF_SEMI) {
            // NV12: all Y first, then U and V interleaved.
            val ySize = w * h
            val q = ySize / 4
            buf.put(yuv, 0, ySize)
            val uv = ByteArray(q * 2)
            for (i in 0 until q) {
                uv[2 * i] = yuv[ySize + i]
                uv[2 * i + 1] = yuv[ySize + q + i]
            }
            buf.put(uv)
        } else {
            buf.put(yuv)
        }
    }

    private fun copyPlane(plane: Image.Plane, src: ByteArray, offset: Int, w: Int, h: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride == 1) {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.put(src, offset + row * w, w)
            }
        } else {
            for (row in 0 until h) {
                for (col in 0 until w) {
                    buf.put(row * rowStride + col * pixelStride, src[offset + row * w + col])
                }
            }
        }
    }

    /** ARGB bitmap (512x512) -> planar YUV 4:2:0 (Y, then U, then V). */
    private fun toI420(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3 / 2)
        val uBase = w * h
        val vBase = uBase + w * h / 4
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = px[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                out[j * w + i] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val idx = (j / 2) * (w / 2) + i / 2
                    out[uBase + idx] = u.coerceIn(0, 255).toByte()
                    out[vBase + idx] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    // ---------- Frame sources (frames are 512x512 on a black background) ----------

    private interface TgSource {
        val durationMs: Long
        val isStill: Boolean get() = false
        fun frameAt(ms: Long): Bitmap?
        fun close() {}
    }

    private fun openSource(context: Context, uri: Uri): TgSource {
        val type = context.contentResolver.getType(uri) ?: ""
        return when {
            type == "image/gif" -> try {
                TgGifSource(context, uri)
            } catch (e: Exception) {
                TgStillSource(context, uri)
            }
            type.startsWith("video/") -> TgVideoSource(context, uri)
            type.startsWith("image/") -> TgStillSource(context, uri)
            else -> try {
                TgVideoSource(context, uri)
            } catch (e: Exception) {
                TgStillSource(context, uri)
            }
        }
    }

    @Suppress("DEPRECATION")
    private class TgGifSource(context: Context, uri: Uri) : TgSource {
        private val movie: Movie

        init {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Could not read GIF")
            movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid GIF")
            if (movie.width() <= 0 || movie.height() <= 0 || movie.duration() <= 0) error("GIF is not animated")
        }

        override val durationMs: Long = movie.duration().toLong()

        override fun frameAt(ms: Long): Bitmap? {
            movie.setTime((ms % durationMs).toInt())
            val out = Bitmap.createBitmap(TG_SIZE, TG_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val s = min(TG_SIZE.toFloat() / movie.width(), TG_SIZE.toFloat() / movie.height())
            canvas.translate((TG_SIZE - movie.width() * s) / 2f, (TG_SIZE - movie.height() * s) / 2f)
            canvas.scale(s, s)
            movie.draw(canvas, 0f, 0f)
            return out
        }
    }

    private class TgVideoSource(context: Context, uri: Uri) : TgSource {
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

        override fun frameAt(ms: Long): Bitmap? {
            val t = ms % durationMs
            val raw = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val fitted = StickerConverter.fitOnTransparentSquare(raw, TG_SIZE)
            raw.recycle()
            return fitted
        }

        override fun close() {
            retriever.release()
        }
    }

    /** Only the chosen part of another source (it loops inside that part if needed). */
    private class TgTrimmedSource(private val inner: TgSource, private val startMs: Long, lengthMs: Long) : TgSource {
        override val durationMs: Long

        init {
            val available = (inner.durationMs - startMs).coerceAtLeast(1L)
            durationMs = if (lengthMs > 0) min(lengthMs, available) else available
        }

        override val isStill: Boolean get() = inner.isStill

        override fun frameAt(ms: Long): Bitmap? = inner.frameAt(startMs + ms % durationMs)

        override fun close() = inner.close()
    }

    private class TgStillSource(context: Context, uri: Uri) : TgSource {
        private val bitmap: Bitmap = StickerConverter.decode(context, uri)
        override val durationMs: Long = 1000L
        override val isStill: Boolean = true

        override fun frameAt(ms: Long): Bitmap? = StickerConverter.fitOnTransparentSquare(bitmap, TG_SIZE)
    }
}

// =====================================================================
//  Screen
// =====================================================================

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // no app can open the link
    }
}

@Composable
fun TelegramScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("telegram", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var userId by remember { mutableStateOf(prefs.getLong("userId", 0L)) }
    var botName by remember { mutableStateOf(prefs.getString("bot", "") ?: "") }
    var setupOpen by remember { mutableStateOf(token.isBlank() || userId == 0L) }
    var setupStatus by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    var title by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("\uD83D\uDE00") }
    var animated by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }

    val trims = remember { mutableStateMapOf<Uri, Trim>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        picked = it
        trims.clear()
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(Lang.t("disconnect_title")) },
            text = { Text(Lang.t("disconnect_text")) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().remove("token").remove("userId").remove("bot").apply()
                    token = ""
                    userId = 0L
                    botName = ""
                    link = ""
                    status = ""
                    setupOpen = true
                    setupStatus = Lang.t("disconnected")
                    confirmDisconnect = false
                }) { Text(Lang.t("disconnect_yes")) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(Lang.t("cancel")) } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(Lang.t("back")) }
        Text(Lang.t("tg_stickers"), style = MaterialTheme.typography.headlineMedium)
        Text(Lang.t("tg_vpn_note"), color = MaterialTheme.colorScheme.error)

        if (setupOpen) {
            Text(Lang.t("tg_setup"))
            OutlinedTextField(
                token, { token = it.trim() },
                label = { Text(Lang.t("bot_token")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = token.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    setupStatus = Lang.t("tg_checking_token")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val me = TelegramApi.call(token, "getMe").getJSONObject("result")
                                val uname = me.getString("username")
                                val updates = TelegramApi.call(token, "getUpdates").getJSONArray("result")
                                var id = 0L
                                for (i in updates.length() - 1 downTo 0) {
                                    val from = updates.getJSONObject(i).optJSONObject("message")
                                        ?.optJSONObject("from") ?: continue
                                    if (from.optBoolean("is_bot")) continue
                                    id = from.getLong("id")
                                    break
                                }
                                Pair(uname, id)
                            }
                        }
                        checking = false
                        result
                            .onSuccess { (uname, id) ->
                                if (id == 0L) {
                                    setupStatus = Lang.t("tg_cant_see", uname)
                                } else {
                                    prefs.edit().putString("token", token).putLong("userId", id)
                                        .putString("bot", uname).apply()
                                    userId = id
                                    botName = uname
                                    setupOpen = false
                                    setupStatus = ""
                                }
                            }
                            .onFailure { setupStatus = tgErrorText(it) }
                    }
                },
            ) { Text(if (checking) Lang.t("checking") else Lang.t("tg_save")) }
            if ((prefs.getString("token", "") ?: "").isNotBlank()) {
                // The bot is still linked: allow leaving this form without changing anything.
                TextButton(onClick = {
                    token = prefs.getString("token", "") ?: ""
                    setupOpen = false
                    setupStatus = ""
                }) { Text(Lang.t("cancel")) }
            }
            if (setupStatus.isNotBlank()) Text(setupStatus)
        } else {
            Text(Lang.t("bot", botName))
            Row {
                TextButton(onClick = { setupOpen = true }) { Text(Lang.t("change_bot")) }
                TextButton(onClick = { confirmDisconnect = true }) { Text(Lang.t("disconnect_bot")) }
            }

            OutlinedTextField(
                title, { title = it },
                label = { Text(Lang.t("pack_title")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                emoji, { emoji = it },
                label = { Text(Lang.t("emoji")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(animated, { animated = it; picked = emptyList(); trims.clear() })
                Spacer(Modifier.width(12.dp))
                Text(if (animated) Lang.t("animated_tg") else Lang.t("static_tg"))
            }
            Button(onClick = {
                picker.launch(if (animated) arrayOf("video/*", "image/*") else arrayOf("image/*"))
            }) { Text(Lang.t("choose_tg", picked.size)) }

            if (animated && picked.isNotEmpty()) {
                Text(Lang.t("trim_hint"))
                picked.forEachIndexed { i, uri -> TrimRow(i + 1, uri, TG_MAX_MS, trims) }
            }

            Button(
                enabled = !busy && title.isNotBlank() && emoji.isNotBlank() && picked.size in 1..50,
                onClick = {
                    busy = true
                    link = ""
                    status = Lang.t("starting")
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                TelegramPackBuilder.create(
                                    context, token, userId, botName, title.trim(), animated, emoji.trim(), picked, trims.toMap(),
                                ) { status = it }
                            }
                        }
                        busy = false
                        r.onSuccess {
                            link = it.link
                            status = (Lang.t("tg_done") + "\n" + it.note).trim()
                            openLink(context, it.link)
                        }.onFailure { status = tgErrorText(it) }
                    }
                },
            ) { Text(if (busy) Lang.t("working") else Lang.t("create_tg")) }

            if (status.isNotBlank()) Text(status)
            if (link.isNotBlank()) {
                Button(onClick = { openLink(context, link) }) { Text(Lang.t("open_tg")) }
                Text(link)
            }
        }
    }
}
