package me.jagajaga.persist.ui.viewer

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles

/**
 * Where every tapped link goes. Set by the app to its own handler -- web pages
 * to the in-app browser, server files to the viewer.
 *
 * Links were built as LinkAnnotation.Url and left to Compose to open through
 * LocalUriHandler, and on the phone they still opened the system browser.
 * Each link now calls this directly, so nothing in between can route it
 * elsewhere.
 */
object LinkRouter {
    @Volatile
    var handler: UriHandler? = null

    /** The phone's own way of opening a link, for when the app's is not set (never, once the app is up). */
    @Volatile
    var fallback: ((String) -> Unit)? = null

    fun open(url: String) {
        handler?.openUri(url) ?: fallback?.invoke(url)
    }

    /** A link in text that opens through the router. */
    fun link(url: String, styles: TextLinkStyles?): LinkAnnotation =
        LinkAnnotation.Clickable(tag = url, styles = styles) { open(url) }
}
