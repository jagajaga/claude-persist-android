package me.jagajaga.persist.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Log in the way the browser does: Authelia's own page, in a WebView.
 *
 * Nothing about Authelia is reimplemented here -- its page, its second factor,
 * its "remember me" -- the WebView simply ends up holding the session cookie
 * that the gateway connection then sends. Login is done when the WebView
 * lands back on code-server rather than on the Authelia portal.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(baseUrl: String, onLoggedIn: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Log in to $baseUrl",
            style = Type.small,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            if (isLoggedIn(url, baseUrl)) {
                                CookieManager.getInstance().flush()
                                onLoggedIn()
                            }
                        }
                    }
                    loadUrl(baseUrl)
                }
            },
        )
    }
}

/** Back on code-server itself, not on the login portal. */
fun isLoggedIn(url: String, baseUrl: String): Boolean =
    url.startsWith(baseUrl) && !url.contains("/authelia") && !url.contains("/api/oidc")
