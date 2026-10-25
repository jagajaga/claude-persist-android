package me.jagajaga.persist.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.detectTapGestures
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
    // Opened from a chat's preview: swipe through every picture and video in it.
    val gallery = Gallery.items
    val at = gallery.indexOf(path)
    if (at >= 0 && gallery.size > 1) {
        GalleryViewer(c, gallery, at, onClose)
        return
    }
    when (Links.kind(path.substringBefore('?'))) {
        FileKind.Text -> TextFileViewer(c.store, path, onClose)
        FileKind.Video, FileKind.Audio -> Frame(path, onClose, dark = true) { MediaPlayer(c, path, active = true) }
        FileKind.Image -> Frame(path, onClose, dark = true) { Fetched(c, path) { ZoomableImage(it) {} } }
        FileKind.Pdf -> Frame(path, onClose) { Fetched(c, path) { PdfPages(it) } }
        FileKind.Other -> Frame(path, onClose) { Fetched(c, path) { OpenWith(it) } }
    }
}

/** Swipe through a chat's pictures and videos, as the panel's lightbox does. */
@Composable
private fun GalleryViewer(c: Container, items: List<String>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { items.size }
    // A zoomed picture pans under the finger; only at 1x does a swipe change picture.
    var zoomed by remember { mutableStateOf(false) }
    val current = items[pager.currentPage]
    Frame(current, onClose, dark = true, title = "${current.substringBefore('?').substringAfterLast('/')}  ·  ${pager.currentPage + 1}/${items.size}") {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { items[it] }, beyondViewportPageCount = 1) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize()) {
                if (Links.kind(item.substringBefore('?')) == FileKind.Video) {
                    MediaPlayer(c, item, active = page == pager.currentPage)
                } else {
                    Fetched(c, item) { file -> ZoomableImage(file) { if (page == pager.currentPage) zoomed = it } }
                }
            }
        }
    }
}

@Composable
private fun Frame(path: String, onClose: () -> Unit, dark: Boolean = false, title: String? = null, content: @Composable BoxScope.() -> Unit) {
    val fg = if (dark) Color.White else Palette.text
    Column(Modifier.fillMaxSize().background(if (dark) Color.Black else Palette.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fg) }
            Text(title ?: path.substringBefore('?').substringAfterLast('/'), style = Type.title.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
        val web = path.startsWith("http://") || path.startsWith("https://")
        val url = if (web) path else c.settings.fileUrl(path)
        val target = File(context.cacheDir, "viewer/${path.hashCode().toUInt()}-${path.substringBefore('?').substringAfterLast('/')}")
        // Our own server gets the login; another site never sees it.
        runCatching { c.downloads.download(url, target, withLogin = !web || path.startsWith(c.settings.baseUrl)) { done, total -> progress = done to total } }
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
private fun BoxScope.ZoomableImage(file: File, onZoomed: (Boolean) -> Unit) {
    val bitmap = remember(file) { decodeBounded(file) }
    if (bitmap == null) {
        Text("Can't show this image", style = Type.body.copy(color = Color.White), modifier = Modifier.align(Alignment.Center))
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale > 1f) { onZoomed(scale > 1f) }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            // Double-tap: 2.5x and back, as in the panel's lightbox.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            // Pinch always zooms; one finger pans only when zoomed in, and is
            // otherwise left alone so the gallery can swipe to the next picture.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
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
private fun BoxScope.MediaPlayer(c: Container, path: String, active: Boolean) {
    var error by remember(path) { mutableStateOf<String?>(null) }
    val web = path.startsWith("http://") || path.startsWith("https://")
    val url = if (web) path else c.settings.fileUrl(path)
    val headers = if (!web || path.startsWith(c.settings.baseUrl)) mapOf("Cookie" to c.downloads.cookieHeader()) else emptyMap()
    var view by remember { mutableStateOf<VideoView?>(null) }
    var ready by remember(path) { mutableStateOf(false) }
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableStateOf(0) }
    var duration by remember(path) { mutableStateOf(0) }
    var dragging by remember(path) { mutableStateOf<Float?>(null) }
    var controls by remember(path) { mutableStateOf(true) }
    DisposableEffect(path) { onDispose { view?.stopPlayback() } }
    // Plays only while it is the page in view: a neighbour being laid out must not start talking.
    LaunchedEffect(active, ready) {
        val v = view ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        if (active) { v.start(); playing = true } else { v.pause(); playing = false }
    }
    LaunchedEffect(path) {
        while (true) {
            view?.let { v -> if (ready && dragging == null) { position = v.currentPosition; playing = v.isPlaying } }
            kotlinx.coroutines.delay(250)
        }
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    duration = mp.duration
                    ready = true
                }
                setOnCompletionListener { playing = false; controls = true }
                setOnErrorListener { _, what, extra ->
                    error = "Can't play this ($what/$extra). If the login expired, log in again."
                    true
                }
                setVideoURI(Uri.parse(url), headers)
                view = this
            }
        },
        modifier = Modifier.fillMaxWidth().align(Alignment.Center).clickable { controls = !controls },
    )
    if (controls && ready) {
        // The panel's player: play/pause, a bar to pick any moment, the time.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            androidx.compose.material3.Slider(
                value = dragging ?: position.toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { view?.seekTo(it.toInt()); position = it.toInt() }
                    dragging = null
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    view?.let { v -> if (v.isPlaying) { v.pause(); playing = false } else { v.start(); playing = true } }
                }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
                Text("${mmss((dragging ?: position.toFloat()).toInt())} / ${mmss(duration)}", style = Type.small.copy(color = Color.White))
            }
        }
    }
    error?.let { Text(it, style = Type.small.copy(color = Color.White), modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)) }
}

private fun mmss(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
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
