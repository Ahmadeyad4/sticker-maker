package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
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
        movie.draw(Canvas(bmp), 0f, 0f)
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

/**
 * Lets the user choose which part of a video or GIF becomes the sticker:
 * where it starts and how long it lasts (up to [maxLengthMs]), with preview pictures of the first and last frame.
 * The choice is stored in [trims]; clips the user does not touch keep their default (the beginning).
 */
@Composable
fun TrimRow(number: Int, uri: Uri, maxLengthMs: Long, trims: MutableMap<Uri, Trim>) {
    val context = LocalContext.current
    val minLen = 300L
    var duration by remember(uri) { mutableStateOf(0L) }
    var startMs by remember(uri) { mutableStateOf(0L) }
    var lengthMs by remember(uri) { mutableStateOf(maxLengthMs) }
    var committedStart by remember(uri) { mutableStateOf(0L) }
    var committedEnd by remember(uri) { mutableStateOf(maxLengthMs) }
    var startThumb by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var endThumb by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        val d = withContext(Dispatchers.IO) { TrimSupport.durationMs(context, uri) }
        duration = d
        if (d > 0) {
            lengthMs = min(maxLengthMs, d)
            committedEnd = lengthMs
        }
    }

    LaunchedEffect(uri, duration, committedStart, committedEnd) {
        if (duration > 1000L) {
            startThumb = withContext(Dispatchers.IO) { TrimSupport.thumbnail(context, uri, committedStart) }
            endThumb = withContext(Dispatchers.IO) {
                TrimSupport.thumbnail(context, uri, (committedEnd - 1).coerceAtLeast(0L))
            }
        }
    }

    // Plain images and very short clips have nothing to trim.
    if (duration <= 1000L) return

    val maxStart = (duration - minLen).coerceAtLeast(1L)
    val maxLen = min(maxLengthMs, duration - startMs).coerceAtLeast(minLen)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Lang.t("trim_title", number, secs(duration)), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrimThumb(startThumb, Lang.t("trim_from"))
            TrimThumb(endThumb, Lang.t("trim_to"))
        }
        Text(Lang.t("trim_start", secs(startMs)))
        Slider(
            value = startMs.toFloat(),
            onValueChange = { v ->
                startMs = v.toLong()
                lengthMs = min(lengthMs, duration - startMs).coerceAtLeast(minLen)
                trims[uri] = Trim(startMs, lengthMs)
            },
            onValueChangeFinished = {
                committedStart = startMs
                committedEnd = startMs + lengthMs
            },
            valueRange = 0f..maxStart.toFloat(),
        )
        Text(Lang.t("trim_length", secs(lengthMs), secs(maxLengthMs)))
        if (maxLen > minLen + 50) {
            Slider(
                value = lengthMs.toFloat().coerceIn(minLen.toFloat(), maxLen.toFloat()),
                onValueChange = { v ->
                    lengthMs = v.toLong()
                    trims[uri] = Trim(startMs, lengthMs)
                },
                onValueChangeFinished = {
                    committedStart = startMs
                    committedEnd = startMs + lengthMs
                },
                valueRange = minLen.toFloat()..maxLen.toFloat(),
            )
        }
    }
}

@Composable
private fun TrimThumb(bitmap: Bitmap?, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
        Text(caption, style = MaterialTheme.typography.labelSmall)
    }
}
