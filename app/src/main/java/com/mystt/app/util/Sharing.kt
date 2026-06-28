package com.mystt.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object Sharing {
    private fun authority(c: Context) = "${c.packageName}.fileprovider"

    fun copyText(c: Context, text: String) {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("myStt", text))
    }

    fun shareText(c: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        c.startActivity(Intent.createChooser(i, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(c: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(c, authority(c), file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(i, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openFile(c: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(c, authority(c), file)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { c.startActivity(i) } catch (_: Exception) {}
    }
}
