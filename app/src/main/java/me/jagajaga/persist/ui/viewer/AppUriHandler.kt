package me.jagajaga.persist.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.UriHandler

/**
 * Every link tapped anywhere in the app comes here: web pages to the in-app
 * browser, server files to the viewer, and the rest to the phone.
 */
class AppUriHandler(
    private val context: Context,
    private val openWeb: (String) -> Unit,
    private val openFile: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        when (val t = Links.target(uri)) {
            is LinkTarget.Web -> openWeb(t.url)
            is LinkTarget.ServerFile -> openFile(t.path)
            is LinkTarget.External -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
