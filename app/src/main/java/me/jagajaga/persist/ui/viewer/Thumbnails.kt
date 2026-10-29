package me.jagajaga.persist.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.jagajaga.persist.container
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.Type
import java.io.File

/** Decoded thumbnails, so scrolling back past a picture doesn't fetch and decode it again. */
private object Thumbs {
    val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

/** A picture on the server, small, in the flow of the chat; tap for the full viewer. */
@Composable
fun ServerImage(path: String, maxHeight: Dp = 240.dp, modifier: Modifier = Modifier) =
    MediaPreview(path, maxHeight, hideOnFailure = false, modifier = modifier)

/**
 * A picture or a video a message refers to -- a server path or a web URL --
 * shown in place: the picture itself, or a video's first frame with a play
 * mark. Tap opens the full viewer. [hideOnFailure] is for previews found in
 * text, where a path to a file that doesn't exist should simply not show.
 */
@Composable
fun MediaPreview(target: String, maxHeight: Dp = 240.dp, hideOnFailure: Boolean = true, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val gallery = LocalGallery.current
    val web = target.startsWith("http://") || target.startsWith("https://")
    val video = Links.kind(target.substringBefore('?')) == FileKind.Video
    // Our own server gets the login; any other site never sees it.
    val ours = !web || target.startsWith(c.settings.baseUrl)
    val url = if (web) target else c.settings.fileUrl(target)
    var bitmap by remember(target) { mutableStateOf(Thumbs.cache.get(target)) }
    var failed by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (bitmap != null) return@LaunchedEffect
        runCatching {
            if (video) {
                withContext(Dispatchers.IO) { videoFrame(url, if (ours) c.downloads.cookieHeader() else null) }
            } else {
                val name = target.substringBefore('?').substringAfterLast('/')
                val file = File(context.cacheDir, "viewer/${target.hashCode().toUInt()}-$name")
                val got = if (file.exists() && file.length() > 0) file else c.downloads.download(url, file, withLogin = ours)
                withContext(Dispatchers.Default) { decodeThumb(got) }
            }
        }.onSuccess { b ->
            if (b == null) failed = true else { Thumbs.cache.put(target, b); bitmap = b }
        }.onFailure { failed = true }
    }
    val b = bitmap
    if (b == null && failed && hideOnFailure) return
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.codeBg)
            .clickable {
                // The viewer opens on this one and swipes through the rest of the chat's.
                Gallery.items = gallery.ifEmpty { listOf(target) }
                uri.openUri(Links.MEDIA + target)
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            b != null -> Image(
                b,
                contentDescription = target.substringAfterLast('/'),
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = maxHeight).widthIn(max = 600.dp),
            )
            failed -> Text("▣ ${target.substringAfterLast('/')}", style = Type.tiny.copy(color = Palette.accent), modifier = Modifier.size(width = 160.dp, height = 40.dp))
            else -> Box(Modifier.size(160.dp, 110.dp))
        }
        if (video && b != null) {
            Box(Modifier.size(52.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/** One frame near the start, read over HTTP with ranges: the video itself is not downloaded. */
private fun videoFrame(url: String, cookie: String?): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(url, if (cookie != null) mapOf("Cookie" to cookie) else emptyMap())
        val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1f, 900f / maxOf(frame.width, frame.height))
        (if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame).asImageBitmap()
    } finally {
        runCatching { retriever.release() }
    }
}

/** A picture not yet sent, from its bytes on the phone. */
@Composable
fun LocalImage(image: me.jagajaga.persist.data.OutgoingImage, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(image.uploadId) {
        runCatching {
            image.localPath?.let { decodeThumb(File(it), target = 200) } ?: run {
                val bytes = Base64.decode(image.base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)))
    }
}

/** Sampled down to about thumbnail size before decoding, so a 12 MP photo costs little. */
private fun decodeThumb(file: File, target: Int = 900): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
    val bitmap: Bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bitmap.asImageBitmap()
}

/** The pictures and videos of the chat on screen, for the viewer to swipe through. */
val LocalGallery = androidx.compose.runtime.staticCompositionLocalOf { emptyList<String>() }

/** Handed from the tapped preview to the viewer it opens. */
object Gallery {
    @Volatile
    var items: List<String> = emptyList()
}
