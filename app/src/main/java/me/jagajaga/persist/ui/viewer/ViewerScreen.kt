package me.jagajaga.persist.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jagajaga.persist.Container
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.TextFileViewer
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.bytes
import java.io.File

/**
 * A server file, shown in the app: images to pinch and pan, video and audio
 * streamed and seekable, PDFs page by page, text as text. Anything else is
 * fetched and offered to whichever app on the phone opens it.
 */
@Composable
fun ViewerScreen(c: Container, path: String, onClose: () -> Unit) {
    when (Links.kind(path)) {
        FileKind.Text -> TextFileViewer(c.store, path, onClose)
        FileKind.Video, FileKind.Audio -> Frame(path, onClose, dark = true) { MediaPlayer(c, path) }
        FileKind.Image -> Frame(path, onClose, dark = true) { Fetched(c, path) { ZoomableImage(it) } }
        FileKind.Pdf -> Frame(path, onClose) { Fetched(c, path) { PdfPages(it) } }
        FileKind.Other -> Frame(path, onClose) { Fetched(c, path) { OpenWith(it) } }
    }
}

@Composable
private fun Frame(path: String, onClose: () -> Unit, dark: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val fg = if (dark) Color.White else Palette.text
    Column(Modifier.fillMaxSize().background(if (dark) Color.Black else Palette.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fg) }
            Text(path.substringAfterLast('/'), style = Type.title.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** Download first (resuming if the link drops), then show. */
@Composable
private fun BoxScope.Fetched(c: Container, path: String, show: @Composable BoxScope.(File) -> Unit) {
    val context = LocalContext.current
    var file by remember(path) { mutableStateOf<File?>(null) }
    var progress by remember(path) { mutableStateOf(0L to 0L) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var attempt by remember(path) { mutableStateOf(0) }
    LaunchedEffect(path, attempt) {
        error = null
        val target = File(context.cacheDir, "viewer/${path.hashCode().toUInt()}-${path.substringAfterLast('/')}")
        runCatching { c.downloads.download(c.settings.fileUrl(path), target) { done, total -> progress = done to total } }
            .onSuccess { file = it }
            .onFailure { error = it.message ?: "Couldn't open it" }
    }
    val f = file
    when {
        f != null -> show(f)
        error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error!!, style = Type.body.copy(color = Palette.error))
            Button(onClick = { attempt++ }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)) { Text("Try again") }
        }
        else -> Text(
            if (progress.second > 0) "Loading… ${bytes(progress.first)} of ${bytes(progress.second)}" else "Loading…",
            style = Type.small.copy(color = Palette.faint),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun BoxScope.ZoomableImage(file: File) {
    val bitmap = remember(file) { decodeBounded(file) }
    if (bitmap == null) {
        Text("Can't show this image", style = Type.body.copy(color = Color.White), modifier = Modifier.align(Alignment.Center))
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

/** Big photos are sampled down to what a screen can show, rather than run out of memory. */
private fun decodeBounded(file: File, max: Int = 4096): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Streamed straight from the server with Range requests: starts at once and seeks. */
@Composable
private fun BoxScope.MediaPlayer(c: Container, path: String) {
    var error by remember(path) { mutableStateOf<String?>(null) }
    val url = c.settings.fileUrl(path)
    val cookie = c.downloads.cookieHeader()
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(path) { onDispose { view?.stopPlayback() } }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { start(); controller.show(3000) }
                setOnErrorListener { _, what, extra ->
                    error = "Can't play this ($what/$extra). If the login expired, log in again."
                    true
                }
                setVideoURI(Uri.parse(url), mapOf("Cookie" to cookie))
                view = this
            }
        },
        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
    )
    error?.let { Text(it, style = Type.small.copy(color = Color.White), modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) }
}

@Composable
private fun PdfPages(file: File) {
    val renderer = remember(file) { runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    if (renderer == null) {
        Text("Can't open this PDF", style = Type.body, modifier = Modifier.padding(24.dp))
        return
    }
    val width = LocalContext.current.resources.displayMetrics.widthPixels
    LazyColumn(Modifier.fillMaxSize().background(Palette.page), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(renderer.pageCount) { index ->
            var page by remember(index) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(index) {
                page = withContext(Dispatchers.Default) {
                    synchronized(renderer) {
                        val p = renderer.openPage(index)
                        try {
                            val bitmap = Bitmap.createBitmap(width, (width.toFloat() * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        } finally {
                            p.close()
                        }
                    }
                }
            }
            page?.let { Image(it.asImageBitmap(), contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxWidth()) }
                ?: Box(Modifier.fillMaxWidth().padding(40.dp)) { Text("Page ${index + 1}…", style = Type.small) }
        }
    }
}

/** Something the app can't show itself: hand it to one that can. */
@Composable
private fun BoxScope.OpenWith(file: File) {
    val context = LocalContext.current
    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${file.name.substringAfter('-')} · ${bytes(file.length())}", style = Type.body)
        Button(onClick = { openExternally(context, file) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Text("  Open with…")
        }
    }
}

fun openExternally(context: android.content.Context, file: File, type: String? = null) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val mime = type ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
