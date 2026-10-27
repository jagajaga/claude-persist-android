package me.jagajaga.persist.net

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * A server file saved to the phone's Downloads, by Android's own download
 * manager: it shows progress in the notification shade, carries on when the
 * app is closed, and resumes after the link drops. Sent with the login
 * cookie, as only our own server is asked.
 */
object SystemDownload {
    fun start(context: Context, url: String, cookie: String?, name: String): Long {
        val safe = name.replace('/', '_').ifBlank { "download" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
            .apply { if (!cookie.isNullOrEmpty()) addRequestHeader("Cookie", cookie) }
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }
}
