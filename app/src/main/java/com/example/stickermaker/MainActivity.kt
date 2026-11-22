package com.example.stickermaker

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {

    // Message shown on screen (progress, errors, and WhatsApp's answer).
    private var status by mutableStateOf("")

    private val addLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val err = result.data?.getStringExtra("validation_error")
            status = when {
                result.resultCode == RESULT_OK -> "Done: the pack was added to WhatsApp."
                err != null -> "WhatsApp rejected the pack: $err"
                else -> "The pack was not added (cancelled, or WhatsApp refused it)."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StickerConverter.animatedEncoder = AnimatedWebpEncoder()
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        var name by remember { mutableStateOf("") }
        var animated by remember { mutableStateOf(false) }
        var picked by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked = it }

        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sticker Maker", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Pack name") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(animated, { animated = it; picked = emptyList() })
                Spacer(Modifier.width(12.dp))
                Text(if (animated) "Animated pack (GIF / video)" else "Static pack (images)")
            }
            Button(onClick = { picker.launch(if (animated) arrayOf("video/*", "image/gif") else arrayOf("image/*")) }) {
                Text("Choose files (${picked.size}) - 3 to 30")
            }
            Button(
                enabled = !busy && name.isNotBlank() && picked.size in 3..30,
                onClick = {
                    busy = true
                    status = "Starting..."
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { buildPack(name.trim(), animated, picked) } }
                        busy = false
                        result
                            .onSuccess { status = "Pack created. Opening WhatsApp..."; addToWhatsApp(it) }
                            .onFailure { status = "Error: ${it.message ?: it.javaClass.simpleName}" }
                    }
                },
            ) { Text(if (busy) "Working..." else "Create and add to WhatsApp") }

            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyLarge)
        }
    }

    private fun buildPack(name: String, animated: Boolean, uris: List<Uri>): StickerPack {
        val store = StickerStore(this)
        val id = UUID.randomUUID().toString()
        val dir = store.packDir(id).apply { mkdirs() }
        try {
            val stickers = uris.mapIndexed { i, uri ->
                status = "Converting sticker ${i + 1} of ${uris.size}..."
                val file = File(dir, "sticker_$i.webp")
                if (animated) StickerConverter.convertAnimated(this, uri, file)
                else StickerConverter.convertStatic(this, uri, file)
                Sticker(file.name, "\uD83D\uDE00")
            }
            // Tray icon needs a still image (animated packs use the first frame).
            val trayFile = File(dir, "tray.png")
            val traySource = if (animated) File(dir, "sticker_0_still.png") else File(dir, stickers.first().file)
            StickerConverter.makeTray(traySource, trayFile)
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
            status = "WhatsApp is not installed, or it does not support sticker packs."
        } catch (e: Exception) {
            status = "Could not open WhatsApp: ${e.message}"
        }
    }
}
