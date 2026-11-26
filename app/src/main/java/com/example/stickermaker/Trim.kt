package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Movie
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The part of a clip chosen for a sticker: where it starts and how long it lasts. */
data class Trim(val startMs: Long, val lengthMs: Long)

object TrimSupport {

    /** Length of a video or GIF in milliseconds, or 0 for a plain image (nothing to trim). */
    fun durationMs(context: Context, uri: Uri): Long {
        val type = context.contentResolver.getType(uri) ?: ""
        if (type == "image/gif") {
            return try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return 0L
                @Suppress("DEPRECATION")
                val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                movie?.duration()?.toLong() ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        if (type.startsWith("image/")) return 0L
        return try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            0L
        }
    }

    /** A small preview picture of the clip at [ms]. */
    fun thumbnail(context: Context, uri: Uri, ms: Long): Bitmap? = try {
        val type = context.contentResolver.getType(uri) ?: ""
        val raw: Bitmap? = when {
            type == "image/gif" -> gifFrame(context, uri, ms)
            type.startsWith("image/") -> StickerConverter.decode(context, uri)
            else -> videoFrame(context, uri, ms)
        }
        raw?.let { shrink(it, 200) }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun gifFrame(context: Context, uri: Uri, ms: Long): Bitmap? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (movie.width() <= 0 || movie.height() <= 0) return null
        val duration = max(movie.duration(), 1)
        movie.setTime((ms % duration).toInt())
        val bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
        movie.draw(AndroidCanvas(bmp), 0f, 0f)
        return bmp
    }

    private fun videoFrame(context: Context, uri: Uri, ms: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }

    private fun shrink(src: Bitmap, maxSide: Int): Bitmap {
        val scale = min(1f, maxSide.toFloat() / max(src.width, src.height))
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }
}

private fun secs(ms: Long): String = String.format(Locale.US, "%.1f", ms / 1000.0)

private const val MIN_CLIP_MS = 300L
private const val STRIP_FRAMES = 8

/**
 * Trim editor for a video or GIF.
 *
 * One range bar with two handles (start / end) sits on top of a filmstrip of the clip. While the handles
 * move, a live preview loops ONLY the selected part, so the result can be checked before cutting.
 * Tap the preview to pause / resume (while paused, dragging a handle shows the exact frame under it).
 * The chosen range is stored in [trims] (start + length), which the encoder uses to cut the sticker.
 * The selection can never be longer than [maxLengthMs] nor shorter than 0.3s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimRow(number: Int, uri: Uri, maxLengthMs: Long, trims: MutableMap<Uri, Trim>) {
    val context = LocalContext.current
    val isGif = remember(uri) { (context.contentResolver.getType(uri) ?: "") == "image/gif" }
    var duration by remember(uri) { mutableLongStateOf(0L) }
    var startMs by remember(uri) { mutableLongStateOf(0L) }
    var endMs by remember(uri) { mutableLongStateOf(maxLengthMs) }
    var strip by remember(uri) { mutableStateOf<List<Bitmap?>>(emptyList()) }
    var paused by remember(uri) { mutableStateOf(false) }
    val playFrac = remember(uri) { mutableFloatStateOf(0f) }

    LaunchedEffect(uri) {
        val d = withContext(Dispatchers.IO) { TrimSupport.durationMs(context, uri) }
        if (d > 1000L) {
            // Come back to the previous choice if the panel was closed and opened again.
            val saved = trims[uri]
            val s = (saved?.startMs ?: 0L).coerceIn(0L, d - MIN_CLIP_MS)
            val len = min(saved?.lengthMs?.takeIf { it > 0L } ?: maxLengthMs, maxLengthMs)
            startMs = s
            endMs = (s + len).coerceIn(s + MIN_CLIP_MS, d)
        }
        duration = d
        if (d > 1000L) {
            strip = withContext(Dispatchers.IO) {
                List(STRIP_FRAMES) { i -> TrimSupport.thumbnail(context, uri, d * (2 * i + 1) / (2 * STRIP_FRAMES)) }
            }
        }
    }

    // Plain images and very short clips have nothing to trim.
    if (duration <= 1000L) return

    val primary = MaterialTheme.colorScheme.primary
    val lengthMs = endMs - startMs

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Lang.t("trim_title", number, secs(duration)), style = MaterialTheme.typography.titleSmall)

        // Live preview of the selected part only (loops). Tap = pause / play.
        Box(
            Modifier
                .size(200.dp)
                .align(Alignment.CenterHorizontally)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { paused = !paused },
            contentAlignment = Alignment.Center,
        ) {
            if (isGif) {
                GifLoop(uri, startMs, endMs, duration, paused, playFrac)
            } else {
                VideoLoop(uri, startMs, endMs, duration, paused, playFrac)
            }
            if (paused) {
                Text("\u25B6", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            }
        }
        Text(
            Lang.t("trim_live"),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // Filmstrip + range bar (start handle / end handle).
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp) // = half a slider handle, so the strip lines up with the bar
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .drawWithContent {
                        drawContent()
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        val w = size.width
                        fun x(fraction: Float) = if (rtl) w * (1f - fraction) else w * fraction
                        val xa = x(startMs.toFloat() / duration)
                        val xb = x(endMs.toFloat() / duration)
                        val left = min(xa, xb)
                        val right = max(xa, xb)
                        val dim = Color.Black.copy(alpha = 0.6f)
                        drawRect(dim, Offset(0f, 0f), Size(left, size.height))
                        drawRect(dim, Offset(right, 0f), Size(w - right, size.height))
                        drawRect(primary, Offset(left, 0f), Size(right - left, size.height), style = Stroke(width = 6f))
                        val px = x(playFrac.floatValue.coerceIn(0f, 1f))
                        drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), strokeWidth = 3f)
                    },
            ) {
                for (i in 0 until STRIP_FRAMES) {
                    val bmp = strip.getOrNull(i)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            RangeSlider(
                value = startMs.toFloat()..endMs.toFloat(),
                onValueChange = { range ->
                    var s = range.start.toLong()
                    var e = range.endInclusive.toLong()
                    if (s != startMs) {
                        // Start handle moved: cannot pass the end; dragging left past the limit pulls the end along.
                        s = s.coerceIn(0L, e - MIN_CLIP_MS)
                        if (e - s > maxLengthMs) e = s + maxLengthMs
                    } else if (e != endMs) {
                        // End handle moved: cannot pass the start; dragging right past the limit pushes the start along.
                        e = e.coerceIn(s + MIN_CLIP_MS, duration)
                        if (e - s > maxLengthMs) s = e - maxLengthMs
                    }
                    startMs = s
                    endMs = e
                    trims[uri] = Trim(s, e - s)
                },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.fillMaxSize(),
                colors = SliderDefaults.colors(
                    thumbColor = primary,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Lang.t("trim_start", secs(startMs)), style = MaterialTheme.typography.bodyMedium)
            Text(Lang.t("trim_end", secs(endMs)), style = MaterialTheme.typography.bodyMedium)
        }
        Text(Lang.t("trim_length", secs(lengthMs), secs(maxLengthMs)), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun seekPlayer(mp: MediaPlayer, ms: Long) {
    try {
        if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST) else mp.seekTo(ms.toInt())
    } catch (e: Exception) {
        // player not ready: ignore
    }
}

/** Muted video preview that loops between [startMs] and [endMs] and follows the handles while they move. */
@Composable
private fun VideoLoop(
    uri: Uri, startMs: Long, endMs: Long, durationMs: Long, paused: Boolean, playFrac: MutableFloatState,
) {
    val context = LocalContext.current
    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    val isPaused by rememberUpdatedState(paused)
    val view = remember(uri) { VideoView(context) }
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(view) {
        // The preview is silent, so it must not steal audio focus from the user's music.
        if (Build.VERSION.SDK_INT >= 26) view.setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
        view.setOnPreparedListener { mp ->
            mp.setVolume(0f, 0f)
            seekPlayer(mp, start)
            player = mp
            if (!isPaused) view.start()
        }
        view.setOnCompletionListener {
            player?.let { mp -> seekPlayer(mp, start) }
            if (!isPaused) view.start()
        }
        view.setOnErrorListener { _, _, _ -> true }
        view.setVideoURI(uri)
        onDispose {
            player = null
            view.stopPlayback()
        }
    }

    LaunchedEffect(paused) { if (paused) view.pause() else view.start() }

    LaunchedEffect(player) {
        val mp = player ?: return@LaunchedEffect
        // Start handle moved -> show the first frame of the new range; end handle moved -> show its last moments.
        launch { snapshotFlow { start }.collect { seekPlayer(mp, it); delay(70) } }
        launch { snapshotFlow { end }.drop(1).collect { seekPlayer(mp, max(start, it - 500L)); delay(70) } }
        var lastSeek = 0L
        while (true) {
            val pos = view.currentPosition.toLong()
            val now = android.os.SystemClock.uptimeMillis()
            if (!isPaused && (pos >= end || pos < start - 80L) && now - lastSeek > 200L) {
                seekPlayer(mp, start)
                lastSeek = now
                if (!view.isPlaying) view.start()
            }
            playFrac.floatValue = pos.coerceIn(start, end).toFloat() / durationMs
            delay(30)
        }
    }

    key(view) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    addView(
                        view,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** GIF preview that loops between [startMs] and [endMs] and follows the handles while they move. */
@Suppress("DEPRECATION")
@Composable
private fun GifLoop(
    uri: Uri, startMs: Long, endMs: Long, durationMs: Long, paused: Boolean, playFrac: MutableFloatState,
) {
    val context = LocalContext.current
    val start by rememberUpdatedState(startMs)
    val end by rememberUpdatedState(endMs)
    val isPaused by rememberUpdatedState(paused)
    val frame = remember(uri) { mutableStateOf<Bitmap?>(null) }
    val tick = remember(uri) { mutableIntStateOf(0) }

    LaunchedEffect(uri) {
        val movie = withContext(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) null else Movie.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) return@LaunchedEffect
        val scale = min(1f, 320f / max(movie.width(), movie.height()))
        val bmp = Bitmap.createBitmap(
            (movie.width() * scale).toInt().coerceAtLeast(1),
            (movie.height() * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = AndroidCanvas(bmp)
        frame.value = bmp

        var pos = start.toDouble()
        var lastStart = start
        var lastEnd = end
        var lastNanos = 0L
        val gifLength = max(movie.duration(), 1)
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1_000_000.0
            lastNanos = now
            if (start != lastStart) { pos = start.toDouble(); lastStart = start }
            if (end != lastEnd) { pos = max(start.toDouble(), end - 500.0); lastEnd = end }
            if (!isPaused) pos += dt
            if (pos >= end || pos < start) pos = start.toDouble()

            movie.setTime((pos.toLong() % gifLength).toInt())
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            canvas.save()
            canvas.scale(scale, scale)
            movie.draw(canvas, 0f, 0f)
            canvas.restore()
            tick.intValue++
            playFrac.floatValue = (pos / durationMs).toFloat()
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        tick.intValue // redraw whenever a new frame is ready
        val bmp = frame.value ?: return@Canvas
        val s = min(size.width / bmp.width, size.height / bmp.height)
        val w = (bmp.width * s).toInt().coerceAtLeast(1)
        val h = (bmp.height * s).toInt().coerceAtLeast(1)
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()),
            dstSize = IntSize(w, h),
        )
    }
}
