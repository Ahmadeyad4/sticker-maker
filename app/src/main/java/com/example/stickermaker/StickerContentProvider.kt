package com.example.stickermaker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Exposes sticker packs to WhatsApp using its required contract. */
class StickerContentProvider : ContentProvider() {
    private lateinit var store: StickerStore
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)

    companion object {
        private const val METADATA_ALL = 1
        private const val METADATA_ONE = 2
        private const val STICKERS = 3
        private const val ASSET = 4
        fun authority(packageName: String) = "$packageName.stickercontentprovider"
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        store = StickerStore(ctx)
        val a = authority(ctx.packageName)
        matcher.addURI(a, "metadata", METADATA_ALL)
        matcher.addURI(a, "metadata/*", METADATA_ONE)
        matcher.addURI(a, "stickers/*", STICKERS)
        matcher.addURI(a, "stickers_asset/*/*", ASSET)
        return true
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = when (matcher.match(uri)) {
        METADATA_ALL -> metadata(store.list())
        METADATA_ONE -> metadata(listOfNotNull(store.get(uri.lastPathSegment)))
        STICKERS -> stickers(store.get(uri.lastPathSegment))
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun metadata(packs: List<StickerPack>): Cursor {
        val cols = arrayOf(
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
            "sticker_pack_icon", "android_play_store_link", "ios_app_download_link",
            "sticker_pack_publisher_email", "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
            "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
        )
        val c = MatrixCursor(cols)
        packs.forEach {
            c.addRow(arrayOf<Any>(it.id, it.name, it.publisher, it.trayFile, "", "", "", "", "", "", "1", 0, if (it.animated) 1 else 0))
        }
        return c
    }

    private fun stickers(pack: StickerPack?): Cursor {
        val c = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        pack?.stickers?.forEach { c.addRow(arrayOf<Any>(it.file, it.emoji, "")) }
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != ASSET) return null
        val seg = uri.pathSegments
        val id = seg[seg.size - 2]
        val name = seg.last()
        if (id.contains("..") || name.contains("/") || name.contains("..")) throw FileNotFoundException()
        val file = File(store.packDir(id), name)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? {
        val a = authority(context!!.packageName)
        return when (matcher.match(uri)) {
            METADATA_ALL -> "vnd.android.cursor.dir/vnd.$a.metadata"
            METADATA_ONE -> "vnd.android.cursor.item/vnd.$a.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.$a.stickers"
            ASSET -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException()
}
