package com.example.stickermaker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
//  Small helpers: emoji, text on stickers, clip detection
// =====================================================================

object Emoji {
    private const val DEFAULT = "\uD83D\uDE00"

    /** Splits text into single emoji, ignoring spaces and commas. */
    fun split(text: String): List<String> {
        val result = ArrayList<String>()
        val iter = android.icu.text.BreakIterator.getCharacterInstance()
        iter.setText(text)
        var start = iter.first()
        var end = iter.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            val piece = text.substring(start, end)
            if (piece.isNotBlank() && piece != ",") result.add(piece)
            start = end
            end = iter.next()
        }
        return result
    }

    /** WhatsApp accepts up to 3 emoji per sticker, separated by commas. */
    fun forWhatsApp(text: String): String =
        split(text).take(3).joinToString(",").ifEmpty { DEFAULT }

    /** Telegram accepts up to 20 emoji per sticker; the pack's default is used when the sticker has none. */
    fun forTelegram(text: String, fallback: String): List<String> {
        val own = split(text)
        val chosen = if (own.isNotEmpty()) own else split(fallback)
        return chosen.take(20).ifEmpty { listOf(DEFAULT) }
    }
}

object Caption {
    private fun layoutFor(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()

    /** Writes [text] near the bottom of the sticker picture (white with a black outline). Does nothing for blank text. */
    fun draw(bitmap: Bitmap, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val size = bitmap.width
        val maxWidth = (size * 0.92f).toInt()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        var textSize = size * 0.14f
        paint.textSize = textSize
        var layout = layoutFor(t, paint, maxWidth)
        while ((layout.lineCount > 3 || layout.height > size * 0.4f) && textSize > size * 0.045f) {
            textSize -= 2f
            paint.textSize = textSize
            layout = layoutFor(t, paint, maxWidth)
        }

        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.translate((size - maxWidth) / 2f, size - layout.height - size * 0.03f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = textSize / 5f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.BLACK
        layout.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        layout.draw(canvas)
        canvas.restore()
    }
}

/** True for videos and GIFs. */
fun Context.looksAnimated(uri: Uri): Boolean {
    val type = contentResolver.getType(uri) ?: return false
    return type.startsWith("video/") || type == "image/gif"
}

// =====================================================================
//  Telegram packs made with this app (kept so they can be managed later)
// =====================================================================

data class TgSetRef(val name: String, val title: String, val animated: Boolean)

object TgSetStore {
    private fun prefs(context: Context) = context.getSharedPreferences("telegram", Context.MODE_PRIVATE)

    fun list(context: Context): List<TgSetRef> {
        val raw = prefs(context).getString("sets", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TgSetRef(o.getString("name"), o.optString("title", o.getString("name")), o.optBoolean("animated"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, sets: List<TgSetRef>) {
        val arr = JSONArray()
        sets.forEach { arr.put(JSONObject().put("name", it.name).put("title", it.title).put("animated", it.animated)) }
        prefs(context).edit().putString("sets", arr.toString()).apply()
    }

    fun add(context: Context, set: TgSetRef) = save(context, list(context).filter { it.name != set.name } + set)

    fun remove(context: Context, name: String) = save(context, list(context).filter { it.name != name })
}

// =====================================================================
//  Preview list: reorder, remove, emoji, text and trimming for each file
// =====================================================================

@Composable
fun StickerEditor(
    picked: List<Uri>,
    onPickedChange: (List<Uri>) -> Unit,
    animated: Boolean,
    maxLengthMs: Long,
    trims: MutableMap<Uri, Trim>,
    emojis: MutableMap<Uri, String>,
    captions: MutableMap<Uri, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Lang.t("ed_hint"))
        picked.forEachIndexed { index, uri ->
            key(uri) {
                EditorRow(
                    index = index,
                    count = picked.size,
                    uri = uri,
                    animated = animated,
                    maxLengthMs = maxLengthMs,
                    trims = trims,
                    emojis = emojis,
                    captions = captions,
                    onMove = { delta ->
                        val target = index + delta
                        if (target in picked.indices) {
                            val list = picked.toMutableList()
                            list.add(target, list.removeAt(index))
                            onPickedChange(list)
                        }
                    },
                    onRemove = {
                        trims.remove(uri)
                        emojis.remove(uri)
                        captions.remove(uri)
                        onPickedChange(picked.filterIndexed { i, _ -> i != index })
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorRow(
    index: Int,
    count: Int,
    uri: Uri,
    animated: Boolean,
    maxLengthMs: Long,
    trims: MutableMap<Uri, Trim>,
    emojis: MutableMap<Uri, String>,
    captions: MutableMap<Uri, String>,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var showTrim by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        thumb = withContext(Dispatchers.IO) { TrimSupport.thumbnail(context, uri, 0L) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = thumb
                    if (bmp != null) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(Lang.t("ed_item", index + 1), style = MaterialTheme.typography.titleSmall)
                    Row {
                        TextButton(onClick = { onMove(-1) }, enabled = index > 0) { Text("\u25B2") }
                        TextButton(onClick = { onMove(1) }, enabled = index < count - 1) { Text("\u25BC") }
                        TextButton(onClick = onRemove) { Text("\u2715") }
                    }
                }
            }
            OutlinedTextField(
                value = emojis[uri] ?: "",
                onValueChange = { emojis[uri] = it },
                label = { Text(Lang.t("ed_emoji")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = captions[uri] ?: "",
                onValueChange = { captions[uri] = it },
                label = { Text(Lang.t("ed_caption")) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (animated) {
                TextButton(onClick = { showTrim = !showTrim }) {
                    Text(Lang.t(if (showTrim) "ed_trim_hide" else "ed_trim"))
                }
                if (showTrim) TrimRow(index + 1, uri, maxLengthMs, trims)
            }
        }
    }
}

// =====================================================================
//  "My packs": WhatsApp packs made here, and Telegram packs made through the bot
// =====================================================================

@Composable
fun ManageScreen(
    statusText: String,
    onBack: () -> Unit,
    onReadd: (StickerPack) -> Unit,
    onAddToSet: (TgSetRef) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerStore(context) }
    val packs = remember { mutableStateListOf<StickerPack>().also { it.addAll(store.list()) } }
    val sets = remember { mutableStateListOf<TgSetRef>().also { it.addAll(TgSetStore.list(context)) } }
    val prefs = remember { context.getSharedPreferences("telegram", Context.MODE_PRIVATE) }
    var packToDelete by remember { mutableStateOf<StickerPack?>(null) }
    var setToDelete by remember { mutableStateOf<TgSetRef?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text(Lang.t("mg_delete_title")) },
            text = { Text(Lang.t("mg_delete_wa_text")) },
            confirmButton = {
                TextButton(onClick = {
                    store.packDir(pack.id).deleteRecursively()
                    packs.remove(pack)
                    packToDelete = null
                }) { Text(Lang.t("mg_delete")) }
            },
            dismissButton = { TextButton(onClick = { packToDelete = null }) { Text(Lang.t("cancel")) } },
        )
    }

    setToDelete?.let { set ->
        AlertDialog(
            onDismissRequest = { setToDelete = null },
            title = { Text(Lang.t("mg_delete_title")) },
            text = { Text(Lang.t("mg_delete_tg_text")) },
            confirmButton = {
                TextButton(onClick = {
                    setToDelete = null
                    val token = prefs.getString("token", "") ?: ""
                    if (token.isBlank()) {
                        message = Lang.t("mg_need_bot")
                    } else {
                        busy = true
                        message = Lang.t("mg_deleting")
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching { TelegramApi.call(token, "deleteStickerSet", mapOf("name" to set.name)) }
                            }
                            busy = false
                            val alreadyGone = (r.exceptionOrNull()?.message ?: "").contains("STICKERSET_INVALID")
                            if (r.isSuccess || alreadyGone) {
                                TgSetStore.remove(context, set.name)
                                sets.remove(set)
                                message = Lang.t("mg_deleted")
                            } else {
                                message = tgErrorText(r.exceptionOrNull() ?: IllegalStateException("error"))
                            }
                        }
                    }
                }) { Text(Lang.t("mg_delete")) }
            },
            dismissButton = { TextButton(onClick = { setToDelete = null }) { Text(Lang.t("cancel")) } },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text(Lang.t("back")) }
        Text(Lang.t("manage_stickers"), style = MaterialTheme.typography.headlineMedium)
        if (statusText.isNotBlank()) Text(statusText)
        if (message.isNotBlank()) Text(message)

        Text(Lang.t("mg_wa"), style = MaterialTheme.typography.titleLarge)
        if (packs.isEmpty()) Text(Lang.t("mg_none"))
        packs.forEach { pack ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text(Lang.t("mg_count", pack.stickers.size) + " - " + Lang.t(if (pack.animated) "mg_animated" else "mg_static"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onReadd(pack) }) { Text(Lang.t("mg_readd")) }
                        TextButton(onClick = { packToDelete = pack }) { Text(Lang.t("mg_delete")) }
                    }
                }
            }
        }

        Text(Lang.t("mg_tg"), style = MaterialTheme.typography.titleLarge)
        if (sets.isEmpty()) Text(Lang.t("mg_none"))
        sets.forEach { set ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(set.title, style = MaterialTheme.typography.titleMedium)
                    Text(Lang.t(if (set.animated) "mg_animated" else "mg_static"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openLink(context, "https://t.me/addstickers/" + set.name) }) { Text(Lang.t("mg_open")) }
                        TextButton(onClick = { onAddToSet(set) }, enabled = !busy) { Text(Lang.t("mg_add")) }
                        TextButton(onClick = { setToDelete = set }, enabled = !busy) { Text(Lang.t("mg_delete")) }
                    }
                }
            }
        }
    }
}
