package com.example.stickermaker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    // Message shown on screen (progress, errors, and WhatsApp's answer).
    private var status by mutableStateOf("")

    // What was created, shown together with WhatsApp's answer.
    private var summary = ""

    private val addLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val err = result.data?.getStringExtra("validation_error")
            val answer = when {
                result.resultCode == RESULT_OK -> Lang.t("wa_done")
                err != null -> Lang.t("wa_rejected", err)
                else -> Lang.t("wa_not_added")
            }
            status = "$summary\n\n$answer"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        StickerConverter.animatedEncoder = AnimatedWebpEncoder()
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        var mode by remember { mutableStateOf("home") }
        BackHandler(enabled = mode != "home") { mode = "home"; status = "" }
        // Arabic is written right-to-left, so the whole layout is mirrored.
        CompositionLocalProvider(
            LocalLayoutDirection provides (if (Lang.isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr),
        ) {
            when (mode) {
                "whatsapp" -> WhatsAppScreen { mode = "home"; status = "" }
                "import" -> ImportScreen { mode = "home"; status = "" }
                "telegram" -> TelegramScreen { mode = "home" }
                else -> HomeScreen { mode = it }
            }
        }
    }

    @Composable
    private fun HomeScreen(onPick: (String) -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedButton(onClick = { Lang.set(this@MainActivity, if (Lang.isArabic) "en" else "ar") }) {
                Text(Lang.t("lang_button"))
            }
            Spacer(Modifier.height(12.dp))
            Text(Lang.t("app"), style = MaterialTheme.typography.headlineLarge)
            Text(Lang.t("home_title"))
            Button(onClick = { onPick("whatsapp") }, modifier = Modifier.fillMaxWidth().height(88.dp)) {
                Text(Lang.t("wa_stickers"), style = MaterialTheme.typography.titleLarge)
            }
            Button(onClick = { onPick("telegram") }, modifier = Modifier.fillMaxWidth().height(88.dp)) {
                Text(Lang.t("tg_stickers"), style = MaterialTheme.typography.titleLarge)
            }
            Button(onClick = { onPick("import") }, modifier = Modifier.fillMaxWidth().height(88.dp)) {
                Text(Lang.t("imp_stickers"), style = MaterialTheme.typography.titleLarge)
            }
            Text(Lang.t("both_support"))
            Text(Lang.t("vpn_home"), color = MaterialTheme.colorScheme.error)
            UpdateBanner()
        }
    }

    @Composable
    private fun WhatsAppScreen(onBack: () -> Unit) {
        var name by remember { mutableStateOf("") }
        var animated by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked = it }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(Lang.t("back")) }
            Text(Lang.t("wa_stickers"), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, label = { Text(Lang.t("pack_name")) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(animated, { animated = it; picked = emptyList() })
                Spacer(Modifier.width(12.dp))
                Text(if (animated) Lang.t("animated_wa") else Lang.t("static_wa"))
            }
            Button(onClick = { picker.launch(if (animated) arrayOf("video/*", "image/*") else arrayOf("image/*")) }) {
                Text(Lang.t("choose_wa", picked.size))
            }
            Button(
                enabled = !busy && name.isNotBlank() && picked.size in 3..30,
                onClick = {
                    busy = true
                    status = Lang.t("starting")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { buildPack(name.trim(), animated, picked) } }
                        busy = false
                        result
                            .onSuccess { status = "$summary\n\n" + Lang.t("wa_opening"); addToWhatsApp(it) }
                            .onFailure { status = Lang.t("error_with", it.message ?: it.javaClass.simpleName) }
                    }
                },
            ) { Text(if (busy) Lang.t("working") else Lang.t("create_wa")) }

            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** Takes a Telegram sticker pack link, downloads the pack and turns it into a WhatsApp pack. */
    @Composable
    private fun ImportScreen(onBack: () -> Unit) {
        val prefs = remember { getSharedPreferences("telegram", Context.MODE_PRIVATE) }
        val token = remember { prefs.getString("token", "") ?: "" }
        var link by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(Lang.t("back")) }
            Text(Lang.t("imp_stickers"), style = MaterialTheme.typography.headlineMedium)
            Text(Lang.t("imp_intro"))
            Text(Lang.t("tg_vpn_note"), color = MaterialTheme.colorScheme.error)
            if (token.isBlank()) Text(Lang.t("imp_need_bot"))
            OutlinedTextField(
                link, { link = it },
                label = { Text(Lang.t("imp_link")) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && token.isNotBlank() && link.isNotBlank(),
                onClick = {
                    busy = true
                    status = Lang.t("imp_fetching")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { importTelegramPack(token, link) } }
                        busy = false
                        result
                            .onSuccess { status = "$summary\n\n" + Lang.t("wa_opening"); addToWhatsApp(it) }
                            .onFailure {
                                status = if (it is TelegramNetworkException) Lang.t("tg_blocked")
                                else Lang.t("error_with", it.message ?: it.javaClass.simpleName)
                            }
                    }
                },
            ) { Text(if (busy) Lang.t("working") else Lang.t("imp_go")) }

            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }

    private fun importTelegramPack(token: String, input: String): StickerPack {
        val name = TelegramImport.parseName(input) ?: error(Lang.t("imp_bad_link"))
        val set = TelegramImport.fetchSet(token, name)

        // A WhatsApp pack is all static or all animated: keep the bigger group.
        val statics = set.stickers.filter { it.kind == "static" }
        val videos = set.stickers.filter { it.kind == "video" }
        val tgsCount = set.stickers.count { it.kind == "animated" }
        val animated = videos.size > statics.size
        var chosen = if (animated) videos else statics

        val notes = ArrayList<String>()
        if (tgsCount > 0) notes.add(Lang.t("imp_tgs", tgsCount))
        if (chosen.size > 30) {
            chosen = chosen.take(30)
            notes.add(Lang.t("imp_limit"))
        }
        if (chosen.size < 3) error(Lang.t("imp_few"))

        val work = File(cacheDir, "tgimport_" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val uris = ArrayList<Uri>()
            chosen.forEachIndexed { i, sticker ->
                status = Lang.t("imp_downloading", i + 1, chosen.size)
                val file = File(work, "t$i." + (if (animated) "webm" else "webp"))
                TelegramImport.download(token, sticker.fileId, file)
                uris.add(Uri.fromFile(file))
            }
            val pack = buildPack(
                set.title.take(50), animated, uris, chosen.map { it.emoji },
            ) { uri, out ->
                if (animated) TelegramImport.convertVideoSticker(this, File(uri.path!!), out)
                else StickerConverter.convertStatic(this, uri, out)
            }
            if (notes.isNotEmpty()) summary += "\n" + notes.joinToString("\n")
            return pack
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Converts every file to a WhatsApp-ready sticker (resizing, compressing, trimming).
     * Files that cannot be converted are skipped, as long as 3 or more are left.
     * [emojis] (optional) gives each sticker its own emoji; [converter] replaces the default conversion.
     */
    private fun buildPack(
        name: String,
        animated: Boolean,
        uris: List<Uri>,
        emojis: List<String> = emptyList(),
        converter: ((Uri, File) -> Unit)? = null,
    ): StickerPack {
        val store = StickerStore(this)
        val id = UUID.randomUUID().toString()
        val dir = store.packDir(id).apply { mkdirs() }
        try {
            val stickers = ArrayList<Sticker>()
            val skipped = ArrayList<String>()
            uris.forEachIndexed { i, uri ->
                status = Lang.t("converting", i + 1, uris.size)
                val file = File(dir, "sticker_${stickers.size}.webp")
                try {
                    when {
                        converter != null -> converter(uri, file)
                        animated -> StickerConverter.convertAnimated(this, uri, file)
                        else -> StickerConverter.convertStatic(this, uri, file)
                    }
                    StickerValidator.check(file, animated)?.let { error(it) }
                    val emoji = emojis.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "\uD83D\uDE00"
                    stickers.add(Sticker(file.name, emoji))
                } catch (e: Exception) {
                    file.delete()
                    skipped.add(Lang.t("file_err", i + 1, e.message ?: e.javaClass.simpleName))
                }
            }
            if (stickers.size < 3) {
                error(Lang.t("wa_few", stickers.size, skipped.joinToString("; ")))
            }

            // Tray icon needs a still image (animated packs use the first frame).
            val trayFile = File(dir, "tray.png")
            val traySource = if (animated) File(dir, "sticker_0_still.png") else File(dir, stickers.first().file)
            StickerConverter.makeTray(traySource, trayFile)

            summary = buildString {
                append(Lang.t("created", stickers.size))
                if (skipped.isNotEmpty()) append(Lang.t("skipped", skipped.size, skipped.joinToString("; ")))
                append("\n")
                append(stickers.take(5).joinToString("\n") {
                    Lang.plain(runCatching { StickerValidator.describe(File(dir, it.file)) }.getOrDefault(it.file))
                })
            }
            return StickerPack(id, name, "Sticker Maker", animated, trayFile.name, stickers)
                .also { store.save(it) }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private fun addToWhatsApp(pack: StickerPack) {
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")
            .putExtra("sticker_pack_id", pack.id)
            .putExtra("sticker_pack_authority", StickerContentProvider.authority(packageName))
            .putExtra("sticker_pack_name", pack.name)
        try {
            addLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            status = Lang.t("wa_missing")
        } catch (e: Exception) {
            status = Lang.t("wa_open_fail", e.message)
        }
    }
}
