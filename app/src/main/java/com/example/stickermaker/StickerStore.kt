package com.example.stickermaker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Sticker(val file: String, val emoji: String)

data class StickerPack(
    val id: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    val trayFile: String,
    val stickers: List<Sticker>,
)

/** Stores each pack in filesDir/packs/<id>/ with a pack.json beside the image files. */
class StickerStore(private val context: Context) {
    private val root get() = File(context.filesDir, "packs").apply { mkdirs() }

    fun packDir(id: String) = File(root, id)

    fun save(pack: StickerPack) {
        val json = JSONObject()
            .put("id", pack.id).put("name", pack.name).put("publisher", pack.publisher)
            .put("animated", pack.animated).put("tray", pack.trayFile)
            .put("stickers", JSONArray().also { arr ->
                pack.stickers.forEach { arr.put(JSONObject().put("file", it.file).put("emoji", it.emoji)) }
            })
        File(packDir(pack.id), "pack.json").writeText(json.toString())
    }

    fun get(id: String?): StickerPack? {
        if (id == null || id.contains("/") || id.contains("..")) return null
        val f = File(packDir(id), "pack.json")
        if (!f.exists()) return null
        val j = JSONObject(f.readText())
        val arr = j.getJSONArray("stickers")
        return StickerPack(
            id = j.getString("id"), name = j.getString("name"),
            publisher = j.getString("publisher"), animated = j.getBoolean("animated"),
            trayFile = j.getString("tray"),
            stickers = (0 until arr.length()).map {
                Sticker(arr.getJSONObject(it).getString("file"), arr.getJSONObject(it).getString("emoji"))
            },
        )
    }

    fun list(): List<StickerPack> =
        root.listFiles()?.mapNotNull { get(it.name) } ?: emptyList()
}
