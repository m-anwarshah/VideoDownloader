package com.manwar.videodownloader

import android.content.Context
import java.io.File

/**
 * Remembers which links have already been downloaded, so the same video is not
 * fetched twice. Keyed on a normalised URL plus the chosen quality, because the
 * 720p and the MP3 of one video are different files.
 */
object History {

    private const val PREFS = "download_history"
    private const val LAST_FILE = "__last_file"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- URL normalising ----------

    private val ytId = Regex(
        """(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})"""
    )

    /** Same video shared from different apps should produce the same key. */
    fun normalize(url: String): String {
        val clean = url.trim().substringBefore('#')

        ytId.find(clean)?.let { return "yt:" + it.groupValues[1] }

        // Drop tracking parameters that vary per share
        val noise = setOf(
            "si", "igsh", "igshid", "fbclid", "gclid", "feature", "app",
            "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
            "_nc_cat", "share_id", "is_from_webapp", "sender_device"
        )
        val base = clean.substringBefore('?')
        val query = clean.substringAfter('?', "")
            .split('&')
            .filter { it.isNotBlank() && it.substringBefore('=') !in noise }
            .sorted()
            .joinToString("&")

        val stripped = (if (query.isEmpty()) base else "$base?$query")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("m.")
            .trimEnd('/')

        return stripped.lowercase()
    }

    private fun key(url: String, quality: String) = normalize(url) + "|" + quality

    // ---------- lookups ----------

    /** The file previously downloaded for this link, or null if it is gone or was never there. */
    fun existing(context: Context, url: String, quality: String): File? {
        val k = key(url, quality)
        val path = prefs(context).getString(k, null) ?: return null
        val file = File(path)
        if (file.exists() && file.length() > 0) return file
        prefs(context).edit().remove(k).apply()   // user deleted it, so let them download again
        return null
    }

    fun record(context: Context, url: String, quality: String, file: File) {
        prefs(context).edit()
            .putString(key(url, quality), file.absolutePath)
            .putString(LAST_FILE, file.absolutePath)
            .apply()
    }

    /** Most recently finished download, for the Open button after a restart. */
    fun lastFile(context: Context): File? {
        val path = prefs(context).getString(LAST_FILE, null) ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0) file else null
    }

    fun setLastFile(context: Context, file: File) {
        prefs(context).edit().putString(LAST_FILE, file.absolutePath).apply()
    }

    // ---------- filename matching ----------

    /**
     * Second line of defence: a file with this video's title may already sit in the
     * folder even if this install has no record of it (reinstall, cleared data,
     * downloaded by something else). Compares titles loosely, since yt-dlp strips
     * characters that are illegal in filenames.
     */
    fun fileWithTitle(dir: File, title: String, wantAudio: Boolean): File? {
        val target = squash(title)
        if (target.length < 4) return null
        val files = dir.listFiles() ?: return null

        return files.firstOrNull { f ->
            if (!f.isFile || f.length() == 0L) return@firstOrNull false
            if (isAudio(f) != wantAudio) return@firstOrNull false
            val name = squash(f.nameWithoutExtension)
            val n = minOf(name.length, target.length, 60)
            n >= 4 && name.take(n) == target.take(n)
        }
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun isAudio(file: File) =
        file.extension.lowercase() in setOf("mp3", "m4a", "aac", "opus", "ogg", "wav", "flac")
}
