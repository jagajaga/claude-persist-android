package me.jagajaga.persist.ui.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.Type

/** A web page inside the app, sharing the login's cookies; Back steps back through the page first. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler { if (canGoBack) web?.goBack() else onClose() }
    Column(Modifier.fillMaxSize().background(Palette.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") }
            Text(title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web?.url ?: url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in browser") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String?) {
                            title = view.title?.takeIf { it.isNotBlank() } ?: finishedUrl ?: url
                            canGoBack = view.canGoBack()
                        }
                    }
                    loadUrl(url)
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
