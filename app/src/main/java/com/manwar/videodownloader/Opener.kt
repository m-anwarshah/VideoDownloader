package com.manwar.videodownloader

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Files land in shared storage, so other apps need a content:// URI with a
 * temporary read grant rather than a raw file path.
 */
object Opener {

    private fun uri(context: Context, file: File) = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )

    private fun mimeOf(file: File) = if (History.isAudio(file)) "audio/*" else "video/*"

    /** Intent that hands the finished file to a player. */
    fun viewIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri(context, file), mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun open(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "That file is no longer on the phone", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            context.startActivity(
                Intent.createChooser(viewIntent(context, file), "Open with")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(context, "No app on this phone can play it", Toast.LENGTH_SHORT).show()
        }
    }

    /** Reveal the file in a file manager or gallery. */
    fun share(context: Context, file: File) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
