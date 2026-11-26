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
import androidx.compose.ui.platform.LocalContext
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

    // Packs waiting to be added to WhatsApp (a big Telegram pack becomes several WhatsApp packs).
    private val queue = ArrayDeque<StickerPack>()
    private var totalPacks = 0
    private var pending by mutableStateOf(0)

    // Files or a pack link shared to this app from the gallery, Telegram, etc.
    private var sharedUris by mutableStateOf<List<Uri>>(emptyList())
    private var sharedLink by mutableStateOf("")

    private val addLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val err = result.data?.getStringExtra("validation_error")
            val answer = when {
                result.resultCode == RESULT_OK -> Lang.t("wa_done")
                err != null -> Lang.t("wa_rejected", err)
                else -> Lang.t("wa_not_added")
            }
            status = (summary + "\n\n" + answer).trim()
            if (result.resultCode == RESULT_OK && queue.isNotEmpty()) launchNext() else pending = queue.size
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        StickerConverter.animatedEncoder = AnimatedWebpEncoder()
        handleShare(intent)
        setContent { MaterialTheme { Screen() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Picks up pictures, videos, GIFs or a Telegram pack link that another app shared with us. */
    @Suppress("DEPRECATION")
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) {
                    sharedUris = listOf(uri)
                } else if (text != null && (text.contains("addstickers/") || text.contains("set="))) {
                    sharedLink = text
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!list.isNullOrEmpty()) sharedUris = list
            }
        }
    }

    @Composable
    private fun Screen() {
        var mode by remember { mutableStateOf("home") }
        var startUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var startLink by remember { mutableStateOf("") }
        var tgTarget by remember { mutableStateOf<TgSetRef?>(null) }

        val goBack: () -> Unit = {
            val next = if (mode == "telegram" && tgTarget != null) "manage" else "home"
            tgTarget = null
            startUris = emptyList()
            startLink = ""
            status = ""
            mode = next
        }
        BackHandler(enabled = mode != "home") { goBack() }

        // A pack link shared from Telegram goes straight to the "move to WhatsApp" screen.
        LaunchedEffect(sharedLink) {
            if (sharedLink.isNotBlank()) {
                startLink = sharedLink
                sharedLink = ""
                mode = "import"
            }
        }

        // Arabic is written right-to-left, so the whole layout is mirrored.
        CompositionLocalProvider(
            LocalLayoutDirection provides (if (Lang.isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr),
        ) {
            key(startUris, startLink, tgTarget) {
                when (mode) {
                    "whatsapp" -> WhatsAppScreen(startUris, goBack)
                    "import" -> ImportScreen(startLink, goBack)
                    "telegram" -> TelegramScreen(goBack, startUris, tgTarget)
                    "manage" -> ManageScreen(
                        statusText = status,
                        onBack = goBack,
                        onReadd = { pack ->
                            queue.clear()
                            pending = 0
                            summary = ""
                            status = Lang.t("wa_opening")
                            addToWhatsApp(pack)
                        },
                        onAddToSet = { ref ->
                            tgTarget = ref
                            mode = "telegram"
                        },
                    )
                    else -> HomeScreen { mode = it }
                }
            }

            if (sharedUris.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { sharedUris = emptyList() },
                    title = { Text(Lang.t("share_title")) },
                    text = { Text(Lang.t("share_text", sharedUris.size)) },
                    confirmButton = {
                        Row {
                            TextButton(onClick = {
                                startUris = sharedUris
                                sharedUris = emptyList()
                                mode = "whatsapp"
                            }) { Text(Lang.t("wa_stickers")) }
                            TextButton(onClick = {
                                startUris = sharedUris
                                sharedUris = emptyList()
                                mode = "telegram"
                            }) { Text(Lang.t("tg_stickers")) }
                        }
                    },
                    dismissButton = { TextButton(onClick = { sharedUris = emptyList() }) { Text(Lang.t("cancel")) } },
                )
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
            OutlinedButton(onClick = { onPick("manage") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(Lang.t("manage_stickers"), style = MaterialTheme.typography.titleMedium)
            }
            Text(Lang.t("both_support"))
            Text(Lang.t("vpn_home"), color = MaterialTheme.colorScheme.error)
            UpdateBanner()
        }
    }

    @Composable
    private fun WhatsAppScreen(initial: List<Uri>, onBack: () -> Unit) {
        val context = LocalContext.current
        var name by remember { mutableStateOf("") }
        var animated by remember { mutableStateOf(initial.any { context.looksAnimated(it) }) }
        var picked by remember { mutableStateOf(initial) }
        var busy by remember { mutableStateOf(false) }
        val trims = remember { mutableStateMapOf<Uri, Trim>() }
        val emojis = remember { mutableStateMapOf<Uri, String>() }
        val captions = remember { mutableStateMapOf<Uri, String>() }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { list ->
            picked = (picked + list).distinct().take(30)
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(Lang.t("back")) }
            Text(Lang.t("wa_stickers"), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, label = { Text(Lang.t("pack_name")) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(animated, {
                    animated = it
                    picked = emptyList()
                    trims.clear()
                    emojis.clear()
                    captions.clear()
                })
                Spacer(Modifier.width(12.dp))
                Text(if (animated) Lang.t("animated_wa") else Lang.t("static_wa"))
            }
            Button(onClick = { picker.launch(if (animated) arrayOf("video/*", "image/*") else arrayOf("image/*")) }) {
                Text(Lang.t("choose_wa", picked.size))
            }

            if (picked.isNotEmpty()) {
                StickerEditor(
                    picked = picked,
                    onPickedChange = { picked = it },
                    animated = animated,
                    maxLengthMs = 9500L,
                    trims = trims,
                    emojis = emojis,
                    captions = captions,
                )
            }

            Button(
                enabled = !busy && name.isNotBlank() && picked.size in 3..30,
                onClick = {
                    busy = true
                    status = Lang.t("starting")
                    scope.launch {
                        val uris = picked
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                buildPack(
                                    name.trim(), animated, uris,
                                    emojis = uris.map { emojis[it] ?: "" },
                                    trims = trims.toMap(),
                                    captions = uris.map { captions[it] ?: "" },
                                )
                            }
                        }
                        busy = false
                        result
                            .onSuccess {
                                queue.clear()
                                pending = 0
                                status = "$summary\n\n" + Lang.t("wa_opening")
                                addToWhatsApp(it)
                            }
                            .onFailure { status = Lang.t("error_with", it.message ?: it.javaClass.simpleName) }
                    }
                },
            ) { Text(if (busy) Lang.t("working") else Lang.t("create_wa")) }

            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }

    /** Takes a Telegram sticker pack link, downloads the pack and turns it into WhatsApp pack(s). */
    @Composable
    private fun ImportScreen(initialLink: String, onBack: () -> Unit) {
        val prefs = remember { getSharedPreferences("telegram", Context.MODE_PRIVATE) }
        val token = remember { prefs.getString("token", "") ?: "" }
        var link by remember { mutableStateOf(initialLink) }
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
                            .onSuccess { packs ->
                                queue.clear()
                                queue.addAll(packs)
                                totalPacks = packs.size
                                launchNext()
                            }
                            .onFailure {
                                status = if (it is TelegramNetworkException) Lang.t("tg_blocked")
                                else Lang.t("error_with", it.message ?: it.javaClass.simpleName)
                            }
                    }
                },
            ) { Text(if (busy) Lang.t("working") else Lang.t("imp_go")) }

            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyLarge)
            if (pending > 0 && !busy) {
                Button(onClick = { launchNext() }) { Text(Lang.t("imp_next", pending)) }
            }
        }
    }

    /** Opens WhatsApp for the next waiting pack. */
    private fun launchNext() {
        val pack = queue.removeFirst()
        pending = queue.size
        status = "$summary\n\n" +
            (if (totalPacks > 1) Lang.t("imp_pack_of", totalPacks - queue.size, totalPacks) else Lang.t("wa_opening"))
        addToWhatsApp(pack)
    }

    /** Downloads the whole Telegram pack and builds one or more WhatsApp packs (30 stickers each at most). */
    private fun importTelegramPack(token: String, input: String): List<StickerPack> {
        val name = TelegramImport.parseName(input) ?: error(Lang.t("imp_bad_link"))
        val set = TelegramImport.fetchSet(token, name)

        // A WhatsApp pack is all static or all animated: keep the bigger group.
        // Animated means video (WebM) or Lottie (TGS) stickers.
        val statics = set.stickers.filter { it.kind == "static" }
        val motion = set.stickers.filter { it.kind == "video" || it.kind == "animated" }
        val animated = motion.size > statics.size
        val chosen = if (animated) motion else statics
        if (chosen.size < 3) error(Lang.t("imp_few"))

        val notes = ArrayList<String>()
        val work = File(cacheDir, "tgimport_" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val uris = ArrayList<Uri>()
            chosen.forEachIndexed { i, sticker ->
                status = Lang.t("imp_downloading", i + 1, chosen.size)
                val extension = when (sticker.kind) {
                    "video" -> "webm"
                    "animated" -> "tgs"
                    else -> "webp"
                }
                val file = File(work, "t$i.$extension")
                TelegramImport.download(token, sticker.fileId, file)
                uris.add(Uri.fromFile(file))
            }

            // Spread the stickers evenly over as few packs as possible (each pack needs 3 to 30).
            val packCount = (chosen.size + 29) / 30
            val perPack = (chosen.size + packCount - 1) / packCount
            if (packCount > 1) notes.add(Lang.t("imp_split", chosen.size, packCount))

            val packs = ArrayList<StickerPack>()
            val summaries = ArrayList<String>()
            var lastError: Exception? = null
            chosen.chunked(perPack).forEachIndexed { index, group ->
                val start = index * perPack
                val title = if (packCount > 1) set.title.take(45) + " " + (index + 1) else set.title.take(50)
                try {
                    packs.add(
                        buildPack(
                            title, animated, uris.subList(start, start + group.size), group.map { it.emoji },
                        ) { uri, out ->
                            val source = File(uri.path!!)
                            when {
                                source.name.endsWith(".tgs") -> TelegramImport.convertTgsSticker(source, out)
                                animated -> TelegramImport.convertVideoSticker(this, source, out)
                                else -> StickerConverter.convertStatic(this, uri, out)
                            }
                        },
                    )
                    summaries.add(summary)
                } catch (e: Exception) {
                    lastError = e
                    notes.add(Lang.t("imp_pack_failed", index + 1, e.message ?: e.javaClass.simpleName))
                }
            }
            if (packs.isEmpty()) throw lastError ?: IllegalStateException(Lang.t("imp_few"))
            summary = (summaries + notes).joinToString("\n\n")
            return packs
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Converts every file to a WhatsApp-ready sticker (resizing, compressing, trimming, adding text).
     * Files that cannot be converted are skipped, as long as 3 or more are left.
     * [emojis] gives each sticker its own emoji, [captions] puts text on it, [converter] replaces the default conversion.
     */
    private fun buildPack(
        name: String,
        animated: Boolean,
        uris: List<Uri>,
        emojis: List<String> = emptyList(),
        trims: Map<Uri, Trim> = emptyMap(),
        captions: List<String> = emptyList(),
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
                val caption = captions.getOrNull(i) ?: ""
                try {
                    when {
                        converter != null -> converter(uri, file)
                        animated -> StickerConverter.convertAnimated(
                            this, uri, file, trims[uri]?.startMs ?: 0L, trims[uri]?.lengthMs ?: 0L, caption,
                        )
                        else -> StickerConverter.convertStatic(this, uri, file, caption)
                    }
                    StickerValidator.check(file, animated)?.let { error(it) }
                    stickers.add(Sticker(file.name, Emoji.forWhatsApp(emojis.getOrNull(i) ?: "")))
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
