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

object TelegramApi {
    /** Calls a Bot API method and returns the JSON answer. Throws with Telegram's own message on errors. */
    fun call(
        token: String,
        method: String,
        fields: Map<String, String> = emptyMap(),
        files: Map<String, File> = emptyMap(),
    ): JSONObject {
        val boundary = "----stickermaker" + UUID.randomUUID().toString().replace("-", "")
        val conn = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.setChunkedStreamingMode(0)
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.outputStream.use { out ->
                fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                for ((k, v) in fields) {
                    text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
                }
                for ((k, f) in files) {
                    text("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"; filename=\"${f.name}\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n")
                    f.inputStream().use { it.copyTo(out) }
                    text("\r\n")
                }
                text("--$boundary--\r\n")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IllegalStateException("Unexpected answer from Telegram (HTTP $code)")
            }
            if (!json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("description", "Telegram error (HTTP $code)"))
            }
            return json
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
        progress: (String) -> Unit,
    ): TelegramResult {
        val dir = File(context.cacheDir, "tg_" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val files = ArrayList<File>()
            val skipped = ArrayList<String>()
            uris.forEachIndexed { i, uri ->
                progress("Converting file ${i + 1} of ${uris.size}...")
                val out = File(dir, "s${files.size}." + (if (animated) "webm" else "webp"))
                try {
                    if (animated) TelegramVideoEncoder.encode(context, uri, out)
                    else TelegramStatic.convert(context, uri, out)
                    files.add(out)
                } catch (e: Exception) {
                    out.delete()
                    skipped.add("file ${i + 1}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            if (files.isEmpty()) error("No usable files. " + skipped.joinToString("; "))

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

            progress("Uploading ${files.size} sticker(s) to Telegram...")
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
            val note = if (skipped.isEmpty()) "" else "Skipped ${skipped.size}: ${skipped.joinToString("; ")}"
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
//  Frames are encoded with the phone's own VP9 encoder (MediaCodec).
// =====================================================================

object TelegramVideoEncoder {
    private val bitrates = intArrayOf(600_000, 450_000, 330_000, 240_000, 170_000, 110_000)

    fun isSupported(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) }
        }

    fun encode(context: Context, input: Uri, output: File) {
        if (!isSupported()) {
            throw IllegalStateException("This phone has no VP9 video encoder, so animated Telegram stickers can't be made here")
        }
        val frames = ArrayList<ByteArray>()
        val source = openSource(context, input)
        try {
            val durationMs = if (source.isStill) 1000L else min(max(source.durationMs, 1000L), TG_MAX_MS)
            val count = max(2, (durationMs * TG_FPS / 1000).toInt())
            for (i in 0 until count) {
                if (source.isStill && frames.isNotEmpty()) {
                    frames.add(frames[0])
                    continue
                }
                val bmp = source.frameAt(i * 1000L / TG_FPS) ?: continue
                frames.add(toI420(bmp))
                bmp.recycle()
            }
        } finally {
            source.close()
        }
        if (frames.size < 2) error("Could not read enough frames from this file")

        for (bitrate in bitrates) {
            output.delete()
            encodeYuv(frames, bitrate, output)
            if (output.length() in 1..TG_MAX_VIDEO_BYTES) return
        }
        output.delete()
        error("Could not fit the animation under 256KB. Try a shorter or simpler clip.")
    }

    private fun encodeYuv(frames: List<ByteArray>, bitrate: Int, out: File) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, TG_SIZE, TG_SIZE).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, TG_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_VP9)
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
                            fillInput(c, inIndex, yuv)
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

    private fun fillInput(codec: MediaCodec, index: Int, yuv: ByteArray) {
        val image = codec.getInputImage(index) ?: throw IllegalStateException("The encoder did not give an input buffer")
        val planes = image.planes
        val w = TG_SIZE
        val h = TG_SIZE
        copyPlane(planes[0], yuv, 0, w, h)
        copyPlane(planes[1], yuv, w * h, w / 2, h / 2)
        copyPlane(planes[2], yuv, w * h + w * h / 4, w / 2, h / 2)
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
            canvas.drawColor(Color.BLACK)
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
            return tgFlatten(fitted)
        }

        override fun close() {
            retriever.release()
        }
    }

    private class TgStillSource(context: Context, uri: Uri) : TgSource {
        private val bitmap: Bitmap = StickerConverter.decode(context, uri)
        override val durationMs: Long = 1000L
        override val isStill: Boolean = true

        override fun frameAt(ms: Long): Bitmap? = tgFlatten(StickerConverter.fitOnTransparentSquare(bitmap, TG_SIZE))
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

    var title by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("\uD83D\uDE00") }
    var animated by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked = it }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("\u2190 Back") }
        Text("Telegram stickers", style = MaterialTheme.typography.headlineMedium)

        if (setupOpen) {
            Text(
                "One-time setup (Telegram only lets a bot create sticker packs):\n" +
                    "1. In Telegram open @BotFather, send /newbot and follow the steps.\n" +
                    "2. Paste the token it gives you below.\n" +
                    "3. Open your new bot in Telegram and press Start.\n" +
                    "4. Tap \"Save and detect my ID\".",
            )
            OutlinedTextField(
                token, { token = it.trim() },
                label = { Text("Bot token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = token.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    setupStatus = "Checking the token..."
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
                                    setupStatus = "The token is valid (bot @$uname), but I can't see you yet. " +
                                        "Open the bot in Telegram, press Start, then tap this button again."
                                } else {
                                    prefs.edit().putString("token", token).putLong("userId", id)
                                        .putString("bot", uname).apply()
                                    userId = id
                                    botName = uname
                                    setupOpen = false
                                    setupStatus = ""
                                }
                            }
                            .onFailure { setupStatus = "Error: ${it.message ?: it.javaClass.simpleName}" }
                    }
                },
            ) { Text(if (checking) "Checking..." else "Save and detect my ID") }
            if (setupStatus.isNotBlank()) Text(setupStatus)
        } else {
            Text("Bot: @$botName")
            TextButton(onClick = { setupOpen = true }) { Text("Change bot") }

            OutlinedTextField(
                title, { title = it },
                label = { Text("Pack title") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                emoji, { emoji = it },
                label = { Text("Emoji for the stickers") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(animated, { animated = it; picked = emptyList() })
                Spacer(Modifier.width(12.dp))
                Text(if (animated) "Animated (video sticker, up to 3s)" else "Static (images)")
            }
            Button(onClick = {
                picker.launch(if (animated) arrayOf("video/*", "image/*") else arrayOf("image/*"))
            }) { Text("Choose files (${picked.size}) - 1 to 50") }

            Button(
                enabled = !busy && title.isNotBlank() && emoji.isNotBlank() && picked.size in 1..50,
                onClick = {
                    busy = true
                    link = ""
                    status = "Starting..."
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                TelegramPackBuilder.create(
                                    context, token, userId, botName, title.trim(), animated, emoji.trim(), picked,
                                ) { status = it }
                            }
                        }
                        busy = false
                        r.onSuccess {
                            link = it.link
                            status = ("Done! Your pack is ready.\n" + it.note).trim()
                            openLink(context, it.link)
                        }.onFailure { status = "Error: ${it.message ?: it.javaClass.simpleName}" }
                    }
                },
            ) { Text(if (busy) "Working..." else "Create Telegram pack") }

            if (status.isNotBlank()) Text(status)
            if (link.isNotBlank()) {
                Button(onClick = { openLink(context, link) }) { Text("Open in Telegram") }
                Text(link)
            }
        }
    }
}
