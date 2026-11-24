package com.example.stickermaker

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** "static" (WebP), "video" (WebM) or "animated" (TGS / Lottie, which cannot be converted). */
data class TgSticker(val fileId: String, val emoji: String, val kind: String)

data class TgSet(val name: String, val title: String, val stickers: List<TgSticker>)

/** Downloads a Telegram sticker pack (through the user's bot) so it can be turned into a WhatsApp pack. */
object TelegramImport {

    /** Accepts t.me/addstickers/Name, https://..., tg://addstickers?set=Name, or just Name. */
    fun parseName(input: String): String? {
        val text = input.trim()
        val fromPath = Regex("""addstickers/([A-Za-z0-9_]+)""").find(text)?.groupValues?.get(1)
        val fromTg = Regex("""[?&]set=([A-Za-z0-9_]+)""").find(text)?.groupValues?.get(1)
        val bare = if (Regex("""[A-Za-z0-9_]+""").matches(text)) text else null
        return fromPath ?: fromTg ?: bare
    }

    fun fetchSet(token: String, name: String): TgSet {
        val result = TelegramApi.call(token, "getStickerSet", mapOf("name" to name)).getJSONObject("result")
        val arr = result.getJSONArray("stickers")
        val list = ArrayList<TgSticker>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val kind = when {
                s.optBoolean("is_video") -> "video"
                s.optBoolean("is_animated") -> "animated"
                else -> "static"
            }
            list.add(TgSticker(s.getString("file_id"), s.optString("emoji", ""), kind))
        }
        return TgSet(name, result.optString("title", name), list)
    }

    fun download(token: String, fileId: String, target: File) {
        val path = TelegramApi.call(token, "getFile", mapOf("file_id" to fileId))
            .getJSONObject("result").getString("file_path")
        val conn = URL("https://api.telegram.org/file/bot$token/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (e: java.io.IOException) {
            throw TelegramNetworkException(e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Telegram video sticker (WebM VP9) -> animated WebP for WhatsApp.
     * FFmpeg decodes the frames with their transparency; if that is not possible
     * the phone's own video decoder is used (transparent areas then turn black).
     */
    fun convertVideoSticker(context: Context, webm: File, output: File) {
        val raw = File(webm.parentFile, webm.nameWithoutExtension + ".rgba")
        var done = false
        if (decodeToRaw(webm, raw)) {
            try {
                AnimatedWebpEncoder().encodeRaw(raw, 15, output)
                done = true
            } catch (e: Exception) {
                // fall back below
            }
        }
        raw.delete()
        if (!done) StickerConverter.convertAnimated(context, Uri.fromFile(webm), output)
    }

    private fun decodeToRaw(webm: File, raw: File): Boolean = try {
        val command = "-y -c:v libvpx-vp9 -i ${webm.path} -t 9.5 " +
            "-vf fps=15,scale=512:512:force_original_aspect_ratio=decrease,format=rgba," +
            "pad=512:512:(ow-iw)/2:(oh-ih)/2:color=black@0 " +
            "-pix_fmt rgba -f rawvideo ${raw.path}"
        val session = FFmpegKit.execute(command)
        ReturnCode.isSuccess(session.returnCode) && raw.length() >= 512L * 512 * 4 * 2
    } catch (t: Throwable) {
        false
    }
}
