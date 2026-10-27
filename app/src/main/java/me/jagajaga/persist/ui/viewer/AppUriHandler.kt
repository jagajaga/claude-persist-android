package me.jagajaga.persist.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.UriHandler

/**
 * Every link tapped anywhere in the app comes here: web pages to your own
 * browser on the phone, server files to the app's viewer, the rest to
 * whatever app handles them.
 */
class AppUriHandler(
    private val context: Context,
    private val openFile: (path: String, line: Int?) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        when (val t = Links.target(uri)) {
            is LinkTarget.Web -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            is LinkTarget.ServerFile -> openFile(t.path, t.line)
            is LinkTarget.External -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(t.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
