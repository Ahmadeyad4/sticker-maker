package com.example.stickermaker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val UPDATE_REPO = "Ahmadeyad4/sticker-maker1"

data class AppUpdate(val code: Long, val name: String, val url: String, val size: Long)

/**
 * Checks the repo's latest GitHub release (published by the build workflow),
 * downloads the APK, and opens the system installer.
 */
object Updater {

    fun currentCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun currentName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** Returns the newest release if it is newer than the installed app, otherwise null. */
    fun checkForUpdate(context: Context): AppUpdate? {
        val conn = URL("https://api.github.com/repos/$UPDATE_REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "StickerMaker-Updater")
            val code = conn.responseCode
            if (code == 404) return null   // no release published yet
            if (code != 200) throw IllegalStateException("GitHub answered HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val remote = tag.substringAfter("build-", "").toLongOrNull() ?: return null
            if (remote <= currentCode(context)) return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return AppUpdate(remote, tag, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the update APK to the cache folder (skips the download if it is already there). */
    fun download(context: Context, update: AppUpdate, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update-${update.code}.apk")
        if (target.exists() && update.size > 0 && target.length() == update.size) return target
        dir.listFiles()?.forEach { if (it != target) it.delete() }

        val tmp = File(dir, "download.tmp")
        val conn = URL(update.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "StickerMaker-Updater")
            if (conn.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else update.size
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) throw IllegalStateException("Could not save the update")
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** Opens the installer. Returns a message if the user must allow something first, otherwise null. */
    fun install(context: Context, apk: File): String? {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                )
                return "Allow \"Install unknown apps\" for Sticker Maker, then come back and tap Install again."
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            return null
        } catch (e: Exception) {
            return "Could not open the installer: ${e.message}"
        }
    }
}

/** Shown on the home screen: checks on start, downloads automatically, then offers one tap to install. */
@Composable
fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }

    val check: (Boolean) -> Unit = { manual ->
        if (!checking) {
            checking = true
            if (manual) message = "Checking for updates..."
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        val u = Updater.checkForUpdate(context)
                        if (u == null) null
                        else Pair(u, Updater.download(context, u) { p -> message = "Downloading update... $p%" })
                    }
                }
                checking = false
                r.onSuccess { pair ->
                    if (pair == null) {
                        update = null
                        apk = null
                        message = if (manual) "You have the latest version." else ""
                    } else {
                        update = pair.first
                        apk = pair.second
                        message = ""
                    }
                }.onFailure {
                    message = if (manual) "Update check failed: ${it.message ?: it.javaClass.simpleName}" else ""
                }
            }
        }
    }

    LaunchedEffect(Unit) { check(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val u = update
        val f = apk
        if (u != null && f != null) {
            Text("A new version is ready (${u.name})", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val m = Updater.install(context, f)
                if (m != null) message = m
            }) { Text("Install update") }
        }
        if (message.isNotBlank()) Text(message)
        TextButton(onClick = { check(true) }, enabled = !checking) {
            Text("Check for updates (version ${Updater.currentName(context)})")
        }
    }
}
