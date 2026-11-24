package com.example.stickermaker

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App language: Arabic or English, switchable from the home screen (remembered between launches).
 * In the Arabic texts, Latin words (VPN, Start...) are wrapped in invisible "isolate" marks
 * so Arabic and English never get scrambled together.
 */
object Lang {
    private const val FSI = "\u2068"   // first-strong isolate
    private const val LRI = "\u2066"   // left-to-right isolate
    private const val PDI = "\u2069"   // end of isolate

    var code by mutableStateOf("ar")
        private set

    val isArabic: Boolean get() = code == "ar"

    fun load(context: Context) {
        val saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", null)
        if (saved == "ar" || saved == "en") code = saved
    }

    fun set(context: Context, newCode: String) {
        code = newCode
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("lang", newCode).apply()
    }

    /** Translated text; {0}, {1}... are replaced by [args] (kept in their own direction inside Arabic text). */
    fun t(key: String, vararg args: Any?): String {
        var text = (if (isArabic) ar[key] else en[key]) ?: en[key] ?: key
        args.forEachIndexed { i, a ->
            val value = a?.toString() ?: ""
            text = text.replace("{$i}", if (isArabic) FSI + value + PDI else value)
        }
        return text
    }

    /** For technical English lines (file names, sizes...) shown inside the Arabic layout. */
    fun plain(text: String): String = if (isArabic) LRI + text + PDI else text

    private val en = mapOf(
        "app" to "Sticker Maker",
        "back" to "\u2190 Back",
        "error" to "Error",
        "error_with" to "Error: {0}",
        "starting" to "Starting...",
        "working" to "Working...",
        "checking" to "Checking...",
        "converting" to "Converting file {0} of {1}...",
        "file_n" to "file {0}",
        "file_err" to "file {0}: {1}",
        "lang_button" to "\u0627\u0644\u0639\u0631\u0628\u064A\u0629",
        "home_title" to "What do you want to make?",
        "wa_stickers" to "WhatsApp stickers",
        "tg_stickers" to "Telegram stickers",
        "imp_stickers" to "Move a Telegram pack to WhatsApp",
        "both_support" to "Static and animated stickers are supported everywhere.",
        "vpn_home" to "If Telegram is blocked in your country, turn on a VPN before using Telegram stickers.",
        "pack_name" to "Pack name",
        "animated_wa" to "Animated pack (GIF / video / image)",
        "static_wa" to "Static pack (images)",
        "choose_wa" to "Choose files ({0}) - 3 to 30",
        "create_wa" to "Create and add to WhatsApp",
        "wa_opening" to "Opening WhatsApp...",
        "wa_done" to "Done: the pack was added to WhatsApp.",
        "wa_rejected" to "WhatsApp rejected the pack: {0}",
        "wa_not_added" to "The pack was not added (cancelled, or WhatsApp refused it).",
        "wa_missing" to "WhatsApp is not installed, or it does not support sticker packs.",
        "wa_open_fail" to "Could not open WhatsApp: {0}",
        "wa_few" to "Only {0} usable file(s), WhatsApp needs at least 3. {1}",
        "created" to "Created {0} sticker(s)",
        "skipped" to ", skipped {0}: {1}",
        "tg_vpn_note" to "If Telegram is blocked in your country, turn on a VPN first, otherwise the pack can't be created or added.",
        "tg_setup" to "One-time setup (Telegram only lets a bot create sticker packs):\n1. In Telegram open @BotFather, send /newbot and follow the steps.\n2. Paste the token it gives you below.\n3. Open your new bot in Telegram and press Start.\n4. Tap \"Save and detect my ID\".",
        "bot_token" to "Bot token",
        "tg_save" to "Save and detect my ID",
        "tg_checking_token" to "Checking the token...",
        "tg_cant_see" to "The token is valid (bot @{0}), but I can't see you yet. Open the bot in Telegram, press Start, then tap this button again.",
        "bot" to "Bot: @{0}",
        "change_bot" to "Change bot",
        "pack_title" to "Pack title",
        "emoji" to "Emoji for the stickers",
        "animated_tg" to "Animated (video sticker, up to 3s)",
        "static_tg" to "Static (images)",
        "choose_tg" to "Choose files ({0}) - 1 to 50",
        "create_tg" to "Create Telegram pack",
        "uploading" to "Uploading {0} sticker(s) to Telegram...",
        "tg_done" to "Done! Your pack is ready.",
        "open_tg" to "Open in Telegram",
        "tg_no_files" to "No usable files. {0}",
        "tg_skipped" to "Skipped {0}: {1}",
        "tg_blocked" to "Couldn't reach Telegram. If it is blocked in your country, turn on a VPN, make sure it covers this app, then try again.",
        "imp_intro" to "Paste the link of a Telegram sticker pack (like t.me/addstickers/Name). The app downloads it and turns it into a WhatsApp pack.",
        "imp_need_bot" to "First set up your bot in the Telegram section (it is needed to download from Telegram).",
        "imp_link" to "Pack link or name",
        "imp_go" to "Download and convert",
        "imp_bad_link" to "That doesn't look like a Telegram sticker pack link.",
        "imp_fetching" to "Reading the pack from Telegram...",
        "imp_downloading" to "Downloading sticker {0} of {1}...",
        "imp_tgs" to "{0} animated (TGS) sticker(s) can't be converted and were skipped.",
        "imp_few" to "Not enough convertible stickers (WhatsApp needs at least 3).",
        "imp_split" to "This pack has {0} stickers, so it was split into {1} WhatsApp packs (WhatsApp allows 30 per pack).",
        "imp_pack_of" to "Adding pack {0} of {1} to WhatsApp...",
        "imp_next" to "Add the next pack ({0} left)",
        "imp_pack_failed" to "Pack {0} could not be made: {1}",
        "upd_ready" to "A new version is ready ({0})",
        "upd_install" to "Install update",
        "upd_downloading" to "Downloading update... {0}%",
        "upd_checking" to "Checking for updates...",
        "upd_latest" to "You have the latest version.",
        "upd_failed" to "Update check failed: {0}",
        "upd_check" to "Check for updates (version {0})",
        "upd_allow" to "Allow \"Install unknown apps\" for Sticker Maker, then come back and tap Install again.",
        "upd_open_fail" to "Could not open the installer: {0}",
    )

    private val ar = mapOf(
        "app" to "\u0635\u0627\u0646\u0639 \u0627\u0644\u0645\u0644\u0635\u0642\u0627\u062A",
        "back" to "\u2192 \u0631\u062C\u0648\u0639",
        "error" to "\u062E\u0637\u0623",
        "error_with" to "\u062E\u0637\u0623: {0}",
        "starting" to "\u062C\u0627\u0631\u064A \u0627\u0644\u0628\u062F\u0621...",
        "working" to "\u062C\u0627\u0631\u064A \u0627\u0644\u0639\u0645\u0644...",
        "checking" to "\u062C\u0627\u0631\u064A \u0627\u0644\u0641\u062D\u0635...",
        "converting" to "\u062C\u0627\u0631\u064A \u062A\u062D\u0648\u064A\u0644 \u0627\u0644\u0645\u0644\u0641 {0} \u0645\u0646 {1}...",
        "file_n" to "\u0627\u0644\u0645\u0644\u0641 {0}",
        "file_err" to "\u0627\u0644\u0645\u0644\u0641 {0}: {1}",
        "lang_button" to "English",
        "home_title" to "\u0645\u0627\u0630\u0627 \u062A\u0631\u064A\u062F \u0623\u0646 \u062A\u0635\u0646\u0639\u061F",
        "wa_stickers" to "\u0645\u0644\u0635\u0642\u0627\u062A \u0648\u0627\u062A\u0633\u0627\u0628",
        "tg_stickers" to "\u0645\u0644\u0635\u0642\u0627\u062A \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645",
        "imp_stickers" to "\u0646\u0642\u0644 \u0628\u0627\u0643 \u0645\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u0625\u0644\u0649 \u0648\u0627\u062A\u0633\u0627\u0628",
        "both_support" to "\u0627\u0644\u0645\u0644\u0635\u0642\u0627\u062A \u0627\u0644\u062B\u0627\u0628\u062A\u0629 \u0648\u0627\u0644\u0645\u062A\u062D\u0631\u0643\u0629 \u0645\u062F\u0639\u0648\u0645\u0629 \u0641\u064A \u0643\u0644 \u0627\u0644\u0623\u0642\u0633\u0627\u0645.",
        "vpn_home" to "\u0625\u0630\u0627 \u0643\u0627\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u0645\u062D\u0638\u0648\u0631\u0627\u064B \u0641\u064A \u0628\u0644\u062F\u0643\u060C \u0641\u0639\u0651\u0644 \u2066VPN\u2069 \u0642\u0628\u0644 \u0627\u0633\u062A\u062E\u062F\u0627\u0645 \u0645\u0644\u0635\u0642\u0627\u062A \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645.",
        "pack_name" to "\u0627\u0633\u0645 \u0627\u0644\u0628\u0627\u0643",
        "animated_wa" to "\u0628\u0627\u0643 \u0645\u062A\u062D\u0631\u0643: \u2066GIF\u2069 \u0623\u0648 \u0641\u064A\u062F\u064A\u0648 \u0623\u0648 \u0635\u0648\u0631\u0629",
        "static_wa" to "\u0628\u0627\u0643 \u062B\u0627\u0628\u062A: \u0635\u0648\u0631",
        "choose_wa" to "\u0627\u062E\u062A\u064A\u0627\u0631 \u0627\u0644\u0645\u0644\u0641\u0627\u062A: {0} (\u0645\u0646 3 \u0625\u0644\u0649 30)",
        "create_wa" to "\u0625\u0646\u0634\u0627\u0621 \u0648\u0625\u0636\u0627\u0641\u0629 \u0625\u0644\u0649 \u0648\u0627\u062A\u0633\u0627\u0628",
        "wa_opening" to "\u062C\u0627\u0631\u064A \u0641\u062A\u062D \u0648\u0627\u062A\u0633\u0627\u0628...",
        "wa_done" to "\u062A\u0645\u062A \u0625\u0636\u0627\u0641\u0629 \u0627\u0644\u0628\u0627\u0643 \u0625\u0644\u0649 \u0648\u0627\u062A\u0633\u0627\u0628.",
        "wa_rejected" to "\u0648\u0627\u062A\u0633\u0627\u0628 \u0631\u0641\u0636 \u0627\u0644\u0628\u0627\u0643: {0}",
        "wa_not_added" to "\u0644\u0645 \u062A\u062A\u0645 \u0625\u0636\u0627\u0641\u0629 \u0627\u0644\u0628\u0627\u0643 (\u0623\u064F\u0644\u063A\u064A\u062A \u0627\u0644\u0639\u0645\u0644\u064A\u0629 \u0623\u0648 \u0631\u0641\u0636\u0647\u0627 \u0648\u0627\u062A\u0633\u0627\u0628).",
        "wa_missing" to "\u0648\u0627\u062A\u0633\u0627\u0628 \u063A\u064A\u0631 \u0645\u062B\u0628\u062A\u060C \u0623\u0648 \u0644\u0627 \u064A\u062F\u0639\u0645 \u062D\u0632\u0645 \u0627\u0644\u0645\u0644\u0635\u0642\u0627\u062A.",
        "wa_open_fail" to "\u062A\u0639\u0630\u0651\u0631 \u0641\u062A\u062D \u0648\u0627\u062A\u0633\u0627\u0628: {0}",
        "wa_few" to "\u0639\u062F\u062F \u0627\u0644\u0645\u0644\u0641\u0627\u062A \u0627\u0644\u0635\u0627\u0644\u062D\u0629 {0} \u0641\u0642\u0637\u060C \u0648\u0648\u0627\u062A\u0633\u0627\u0628 \u064A\u062D\u062A\u0627\u062C 3 \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644. {1}",
        "created" to "\u062A\u0645 \u0625\u0646\u0634\u0627\u0621 {0} \u0645\u0644\u0635\u0642",
        "skipped" to "\u060C \u062A\u0645 \u062A\u062E\u0637\u064A {0}: {1}",
        "tg_vpn_note" to "\u0625\u0630\u0627 \u0643\u0627\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u0645\u062D\u0638\u0648\u0631\u0627\u064B \u0641\u064A \u0628\u0644\u062F\u0643\u060C \u0641\u0639\u0651\u0644 \u2066VPN\u2069 \u0623\u0648\u0644\u0627\u064B\u060C \u0648\u0625\u0644\u0627 \u0641\u0644\u0646 \u064A\u0645\u0643\u0646 \u0625\u0646\u0634\u0627\u0621 \u0627\u0644\u0628\u0627\u0643 \u0623\u0648 \u0625\u0636\u0627\u0641\u062A\u0647.",
        "tg_setup" to "\u0625\u0639\u062F\u0627\u062F \u0644\u0645\u0631\u0629 \u0648\u0627\u062D\u062F\u0629 (\u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u064A\u0633\u0645\u062D \u0644\u0644\u0628\u0648\u062A \u0641\u0642\u0637 \u0628\u0625\u0646\u0634\u0627\u0621 \u062D\u0632\u0645 \u0627\u0644\u0645\u0644\u0635\u0642\u0627\u062A):\n\u0623\u0648\u0644\u0627\u064B: \u0627\u0641\u062A\u062D \u2066@BotFather\u2069 \u0641\u064A \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u0648\u0623\u0631\u0633\u0644 \u2066/newbot\u2069 \u0648\u0627\u062A\u0628\u0639 \u0627\u0644\u062E\u0637\u0648\u0627\u062A.\n\u062B\u0627\u0646\u064A\u0627\u064B: \u0627\u0644\u0635\u0642 \u0627\u0644\u062A\u0648\u0643\u0646 \u0627\u0644\u0630\u064A \u064A\u0639\u0637\u064A\u0643 \u0625\u064A\u0627\u0647 \u0641\u064A \u0627\u0644\u062E\u0627\u0646\u0629 \u0623\u062F\u0646\u0627\u0647.\n\u062B\u0627\u0644\u062B\u0627\u064B: \u0627\u0641\u062A\u062D \u0628\u0648\u062A\u0643 \u0627\u0644\u062C\u062F\u064A\u062F \u0641\u064A \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u0648\u0627\u0636\u063A\u0637 \u2066Start\u2069.\n\u0631\u0627\u0628\u0639\u0627\u064B: \u0627\u0636\u063A\u0637 \u00AB\u062D\u0641\u0638 \u0648\u0627\u0644\u062A\u0639\u0631\u0651\u0641 \u0639\u0644\u0649 \u0645\u0639\u0631\u0651\u0641\u064A\u00BB.",
        "bot_token" to "\u062A\u0648\u0643\u0646 \u0627\u0644\u0628\u0648\u062A",
        "tg_save" to "\u062D\u0641\u0638 \u0648\u0627\u0644\u062A\u0639\u0631\u0651\u0641 \u0639\u0644\u0649 \u0645\u0639\u0631\u0651\u0641\u064A",
        "tg_checking_token" to "\u062C\u0627\u0631\u064A \u0641\u062D\u0635 \u0627\u0644\u062A\u0648\u0643\u0646...",
        "tg_cant_see" to "\u0627\u0644\u062A\u0648\u0643\u0646 \u0635\u0627\u0644\u062D (\u0627\u0644\u0628\u0648\u062A \u2066@{0}\u2069) \u0644\u0643\u0646\u0646\u064A \u0644\u0627 \u0623\u0631\u0627\u0643 \u0628\u0639\u062F. \u0627\u0641\u062A\u062D \u0627\u0644\u0628\u0648\u062A \u0641\u064A \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 \u0648\u0627\u0636\u063A\u0637 \u2066Start\u2069\u060C \u062B\u0645 \u0627\u0636\u063A\u0637 \u0647\u0630\u0627 \u0627\u0644\u0632\u0631 \u0645\u0631\u0629 \u0623\u062E\u0631\u0649.",
        "bot" to "\u0627\u0644\u0628\u0648\u062A: \u2066@{0}\u2069",
        "change_bot" to "\u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u0628\u0648\u062A",
        "pack_title" to "\u0639\u0646\u0648\u0627\u0646 \u0627\u0644\u0628\u0627\u0643",
        "emoji" to "\u0627\u0644\u0625\u064A\u0645\u0648\u062C\u064A \u0644\u0644\u0645\u0644\u0635\u0642\u0627\u062A",
        "animated_tg" to "\u0645\u062A\u062D\u0631\u0643 (\u0645\u0644\u0635\u0642 \u0641\u064A\u062F\u064A\u0648\u060C \u062D\u062A\u0649 3 \u062B\u0648\u0627\u0646\u064D)",
        "static_tg" to "\u062B\u0627\u0628\u062A (\u0635\u0648\u0631)",
        "choose_tg" to "\u0627\u062E\u062A\u064A\u0627\u0631 \u0627\u0644\u0645\u0644\u0641\u0627\u062A: {0} (\u0645\u0646 1 \u0625\u0644\u0649 50)",
        "create_tg" to "\u0625\u0646\u0634\u0627\u0621 \u0628\u0627\u0643 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645",
        "uploading" to "\u062C\u0627\u0631\u064A \u0631\u0641\u0639 {0} \u0645\u0644\u0635\u0642 \u0625\u0644\u0649 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645...",
        "tg_done" to "\u062A\u0645! \u0627\u0644\u0628\u0627\u0643 \u062C\u0627\u0647\u0632.",
        "open_tg" to "\u0641\u062A\u062D \u0641\u064A \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645",
        "tg_no_files" to "\u0644\u0627 \u062A\u0648\u062C\u062F \u0645\u0644\u0641\u0627\u062A \u0635\u0627\u0644\u062D\u0629. {0}",
        "tg_skipped" to "\u062A\u0645 \u062A\u062E\u0637\u064A {0}: {1}",
        "tg_blocked" to "\u062A\u0639\u0630\u0651\u0631 \u0627\u0644\u0627\u062A\u0635\u0627\u0644 \u0628\u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645. \u0625\u0630\u0627 \u0643\u0627\u0646 \u0645\u062D\u0638\u0648\u0631\u0627\u064B \u0641\u064A \u0628\u0644\u062F\u0643 \u0641\u0639\u0651\u0644 \u2066VPN\u2069 \u0648\u062A\u0623\u0643\u062F \u0623\u0646\u0647 \u064A\u0634\u0645\u0644 \u0647\u0630\u0627 \u0627\u0644\u062A\u0637\u0628\u064A\u0642\u060C \u062B\u0645 \u062D\u0627\u0648\u0644 \u0645\u0631\u0629 \u0623\u062E\u0631\u0649.",
        "imp_intro" to "\u0627\u0644\u0635\u0642 \u0631\u0627\u0628\u0637 \u0628\u0627\u0643 \u0645\u0644\u0635\u0642\u0627\u062A \u0645\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645\u060C \u0645\u062B\u0644 \u2066t.me/addstickers/Name\u2069. \u0633\u064A\u062D\u0645\u0651\u0644\u0647 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0648\u064A\u062D\u0648\u0651\u0644\u0647 \u0625\u0644\u0649 \u0628\u0627\u0643 \u0648\u0627\u062A\u0633\u0627\u0628.",
        "imp_need_bot" to "\u0623\u0648\u0644\u0627\u064B \u0623\u0639\u062F\u0651 \u0627\u0644\u0628\u0648\u062A \u0645\u0646 \u0642\u0633\u0645 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645 (\u0645\u0637\u0644\u0648\u0628 \u0644\u0644\u062A\u062D\u0645\u064A\u0644 \u0645\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645).",
        "imp_link" to "\u0631\u0627\u0628\u0637 \u0627\u0644\u0628\u0627\u0643 \u0623\u0648 \u0627\u0633\u0645\u0647",
        "imp_go" to "\u062A\u062D\u0645\u064A\u0644 \u0648\u062A\u062D\u0648\u064A\u0644",
        "imp_bad_link" to "\u0647\u0630\u0627 \u0644\u0627 \u064A\u0628\u062F\u0648 \u0631\u0627\u0628\u0637 \u0628\u0627\u0643 \u0645\u0644\u0635\u0642\u0627\u062A \u0645\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645.",
        "imp_fetching" to "\u062C\u0627\u0631\u064A \u0642\u0631\u0627\u0621\u0629 \u0627\u0644\u0628\u0627\u0643 \u0645\u0646 \u062A\u064A\u0644\u064A\u0643\u0631\u0627\u0645...",
        "imp_downloading" to "\u062C\u0627\u0631\u064A \u062A\u062D\u0645\u064A\u0644 \u0627\u0644\u0645\u0644\u0635\u0642 {0} \u0645\u0646 {1}...",
        "imp_tgs" to "\u062A\u0639\u0630\u0651\u0631 \u062A\u062D\u0648\u064A\u0644 {0} \u0645\u0644\u0635\u0642 \u0645\u062A\u062D\u0631\u0643 \u0645\u0646 \u0646\u0648\u0639 \u2066TGS\u2069 \u0641\u062A\u0645 \u062A\u062E\u0637\u064A\u0647\u0627.",
        "imp_few" to "\u0639\u062F\u062F \u0627\u0644\u0645\u0644\u0635\u0642\u0627\u062A \u0627\u0644\u0642\u0627\u0628\u0644\u0629 \u0644\u0644\u062A\u062D\u0648\u064A\u0644 \u0642\u0644\u064A\u0644 (\u0648\u0627\u062A\u0633\u0627\u0628 \u064A\u062D\u062A\u0627\u062C 3 \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644).",
        "imp_split" to "\u0647\u0630\u0627 \u0627\u0644\u0628\u0627\u0643 \u0641\u064A\u0647 {0} \u0645\u0644\u0635\u0642\u060C \u0644\u0630\u0644\u0643 \u062A\u0645 \u062A\u0642\u0633\u064A\u0645\u0647 \u0625\u0644\u0649 {1} \u0628\u0627\u0643\u0627\u062A \u0641\u064A \u0648\u0627\u062A\u0633\u0627\u0628 (\u0648\u0627\u062A\u0633\u0627\u0628 \u064A\u0633\u0645\u062D \u0628\u0640 30 \u0645\u0644\u0635\u0642\u0627\u064B \u0644\u0643\u0644 \u0628\u0627\u0643).",
        "imp_pack_of" to "\u062C\u0627\u0631\u064A \u0625\u0636\u0627\u0641\u0629 \u0627\u0644\u0628\u0627\u0643 {0} \u0645\u0646 {1} \u0625\u0644\u0649 \u0648\u0627\u062A\u0633\u0627\u0628...",
        "imp_next" to "\u0625\u0636\u0627\u0641\u0629 \u0627\u0644\u0628\u0627\u0643 \u0627\u0644\u062A\u0627\u0644\u064A (\u0627\u0644\u0645\u062A\u0628\u0642\u064A {0})",
        "imp_pack_failed" to "\u062A\u0639\u0630\u0651\u0631 \u0625\u0646\u0634\u0627\u0621 \u0627\u0644\u0628\u0627\u0643 {0}: {1}",
        "upd_ready" to "\u064A\u062A\u0648\u0641\u0631 \u0625\u0635\u062F\u0627\u0631 \u062C\u062F\u064A\u062F ({0})",
        "upd_install" to "\u062A\u062B\u0628\u064A\u062A \u0627\u0644\u062A\u062D\u062F\u064A\u062B",
        "upd_downloading" to "\u062C\u0627\u0631\u064A \u062A\u0646\u0632\u064A\u0644 \u0627\u0644\u062A\u062D\u062F\u064A\u062B... {0}%",
        "upd_checking" to "\u062C\u0627\u0631\u064A \u0627\u0644\u0628\u062D\u062B \u0639\u0646 \u062A\u062D\u062F\u064A\u062B...",
        "upd_latest" to "\u0644\u062F\u064A\u0643 \u0623\u062D\u062F\u062B \u0625\u0635\u062F\u0627\u0631.",
        "upd_failed" to "\u0641\u0634\u0644 \u0627\u0644\u0628\u062D\u062B \u0639\u0646 \u062A\u062D\u062F\u064A\u062B: {0}",
        "upd_check" to "\u0627\u0644\u0628\u062D\u062B \u0639\u0646 \u062A\u062D\u062F\u064A\u062B (\u0627\u0644\u0625\u0635\u062F\u0627\u0631 {0})",
        "upd_allow" to "\u0627\u0633\u0645\u062D \u0628\u0640 \u00AB\u062A\u062B\u0628\u064A\u062A \u0627\u0644\u062A\u0637\u0628\u064A\u0642\u0627\u062A \u0627\u0644\u0645\u062C\u0647\u0648\u0644\u0629\u00BB \u0644\u0635\u0627\u0646\u0639 \u0627\u0644\u0645\u0644\u0635\u0642\u0627\u062A\u060C \u062B\u0645 \u0627\u0631\u062C\u0639 \u0648\u0627\u0636\u063A\u0637 \u062A\u062B\u0628\u064A\u062A \u0645\u0631\u0629 \u0623\u062E\u0631\u0649.",
        "upd_open_fail" to "\u062A\u0639\u0630\u0651\u0631 \u0641\u062A\u062D \u0627\u0644\u0645\u062B\u0628\u0651\u062A: {0}",
    )
}
