package me.jagajaga.persist.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun ServerImage(path: String, maxHeight: Dp = 240.dp, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var bitmap by remember(path) { mutableStateOf(Thumbs.cache.get(path)) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        runCatching {
            val target = File(context.cacheDir, "viewer/${path.hashCode().toUInt()}-${path.substringAfterLast('/')}")
            val file = if (target.exists() && target.length() > 0) target else c.downloads.download(c.settings.fileUrl(path), target)
            withContext(Dispatchers.Default) { decodeThumb(file) }
        }.onSuccess { b ->
            if (b == null) failed = true else { Thumbs.cache.put(path, b); bitmap = b }
        }.onFailure { failed = true }
    }
    val b = bitmap
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.codeBg)
            .clickable { uri.openUri(path) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            b != null -> Image(
                b,
                contentDescription = path.substringAfterLast('/'),
                contentScale = ContentScale.Fit,
                modifier = Modifier.heightIn(max = maxHeight).widthIn(max = 600.dp),
            )
            failed -> Text("▣ ${path.substringAfterLast('/')}", style = Type.tiny.copy(color = Palette.accent), modifier = Modifier.size(width = 160.dp, height = 40.dp))
            else -> Box(Modifier.size(160.dp, 110.dp))
        }
    }
}

/** A picture not yet sent, from its bytes on the phone. */
@Composable
fun LocalImage(base64: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(base64) {
        runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
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
