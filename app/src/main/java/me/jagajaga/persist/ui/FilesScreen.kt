package me.jagajaga.persist.ui

import me.jagajaga.persist.container

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.jagajaga.persist.data.FsEntry
import me.jagajaga.persist.data.FsListing
import me.jagajaga.persist.data.FsRead
import me.jagajaga.persist.data.Store

/**
 * The server's files, read-only. Directories first; a file opens in a viewer
 * that pages through it, so a large log loads a piece at a time on a bad link.
 */
@Composable
fun FilesScreen(store: Store, startPath: String, pickFolder: ((String) -> Unit)? = null) {
    var path by remember { mutableStateOf(startPath) }
    var listing by remember { mutableStateOf<FsListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<FsEntry?>(null) }
    // Made before the viewer can take over the screen, so coming back from a
    // file finds the list where you left it; and each folder keeps its own place.
    val listState = rememberLazyListState()
    val places = remember { HashMap<String, Pair<Int, Int>>() }
    fun go(to: String) {
        places[path] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        path = to
    }

    var reload by remember { mutableStateOf(0) }
    var naming by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = context.container
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(path, reload) {
        error = null
        listing = runCatching { store.list(path) }.onFailure { error = it.message }.getOrNull()
        val (index, offset) = places[path] ?: (0 to 0)
        listState.scrollToItem(index, offset)
    }

    val file = viewing
    if (file != null) {
        // Images, video, PDFs and the rest open in the same viewer as a link in a chat.
        me.jagajaga.persist.ui.viewer.ViewerScreen(androidx.compose.ui.platform.LocalContext.current.container, path.trimEnd('/') + "/" + file.name) { viewing = null }
        return
    }

    Column(Modifier.fillMaxSize().background(Palette.page)) {
        Row(
            Modifier.fillMaxWidth().background(Palette.surface).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { listing?.parent?.let { go(it) } }, enabled = listing?.parent != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
            }
            Text(path, style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { naming = true }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "New folder")
            }
            if (pickFolder != null) {
                OutlinedButton(onClick = { pickFolder(path) }) { Text("Use this folder") }
            }
        }
        if (naming) {
            var name by remember { mutableStateOf("") }
            var problem by remember { mutableStateOf<String?>(null) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { naming = false },
                title = { Text("New folder in ${path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}") },
                text = {
                    Column {
                        androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it; problem = null }, singleLine = true, placeholder = { Text("Folder name") })
                        problem?.let { Text(it, style = Type.small.copy(color = Palette.error)) }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = {
                        val target = path.trimEnd('/') + "/" + name.trim()
                        scope.launch {
                            runCatching { store.mkdir(target) }
                                .onSuccess { naming = false; reload++ }
                                .onFailure { problem = it.message }
                        }
                    }) { Text("Create") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { naming = false }) { Text("Cancel") } },
            )
        }
        error?.let { Text(it, style = Type.small.copy(color = Palette.error), modifier = Modifier.padding(14.dp)) }
        val entries = listing?.entries.orEmpty().let { all -> if (pickFolder != null) all.filter { it.isDir } else all }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(entries, key = { it.name }) { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.surface)
                        .clickable {
                            if (entry.isDir) {
                                go(path.trimEnd('/') + "/" + entry.name)
                            } else {
                                // A picture or video swipes on through the folder's others.
                                val here = path.trimEnd('/')
                                me.jagajaga.persist.ui.viewer.Gallery.items = entries.filter { !it.isDir }.map { "$here/${it.name}" }
                                    .filter { me.jagajaga.persist.ui.viewer.Links.kind(it).let { k -> k == me.jagajaga.persist.ui.viewer.FileKind.Image || k == me.jagajaga.persist.ui.viewer.FileKind.Video } }
                                viewing = entry
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            entry.isDir -> Icons.Filled.Folder
                            entry.kind == "link" -> Icons.Filled.Link
                            else -> Icons.Filled.Description
                        },
                        contentDescription = null,
                        tint = if (entry.isDir) Palette.accent else Palette.muted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(entry.name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!entry.isDir) {
                        Text(bytes(entry.size), style = Type.tiny)
                        // To the phone's Downloads, by Android's download manager.
                        IconButton(onClick = {
                            val full = path.trimEnd('/') + "/" + entry.name
                            runCatching { me.jagajaga.persist.net.SystemDownload.start(context, c.settings.fileUrl(full), c.downloads.cookieHeader(), entry.name) }
                                .onSuccess { android.widget.Toast.makeText(context, "Downloading ${entry.name}", android.widget.Toast.LENGTH_SHORT).show() }
                                .onFailure { error = "Couldn't download: ${it.message}" }
                        }) { Icon(Icons.Filled.Download, contentDescription = "Download ${entry.name}", tint = Palette.muted) }
                    }
                }
                HorizontalDivider(color = Palette.border, modifier = Modifier.padding(start = 48.dp))
            }
            if (listing?.truncated == true) item { Text("…list truncated", style = Type.tiny, modifier = Modifier.padding(14.dp)) }
        }
    }
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

@Composable
fun TextFileViewer(store: Store, filePath: String, onClose: () -> Unit) {
    var pages by remember(filePath) { mutableStateOf(listOf<FsRead>()) }
    var error by remember(filePath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isImage = filePath.substringAfterLast('.', "").lowercase() in imageExtensions

    suspend fun loadNext() {
        val offset = pages.lastOrNull()?.let { it.offset + it.length } ?: 0
        runCatching { store.read(filePath, offset) }
            .onSuccess { pages = pages + it }
            .onFailure { error = it.message }
    }

    LaunchedEffect(filePath) { loadNext() }

    Column(Modifier.fillMaxSize().background(Palette.surface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(filePath.substringAfterLast('/'), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            pages.firstOrNull()?.let { Text(bytes(it.size), style = Type.tiny, modifier = Modifier.padding(end = 14.dp)) }
        }
        error?.let { Text(it, style = Type.small.copy(color = Palette.error), modifier = Modifier.padding(14.dp)) }
        val last = pages.lastOrNull()
        if (isImage && last != null) {
            // An image is shown once it has all arrived.
            if (last.eof) {
                val all = pages.flatMap { Base64.decode(it.data, Base64.DEFAULT).toList() }.toByteArray()
                val bitmap = remember(all.size) { BitmapFactory.decodeByteArray(all, 0, all.size) }
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                }
            } else {
                LaunchedEffect(pages.size) { loadNext() }
                Text("Loading… ${bytes(last.offset + last.length)} of ${bytes(last.size)}", style = Type.small, modifier = Modifier.padding(14.dp))
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                val text = pages.joinToString("") { if (it.encoding == "utf8") it.data else "[binary: ${bytes(it.length)}]" }
                Text(text, style = Type.code, softWrap = false)
                if (last != null && !last.eof) {
                    Box(Modifier.padding(top = 10.dp)) {
                        OutlinedButton(onClick = { scope.launch { loadNext() } }) {
                            Text("Load more (${bytes(last.offset + last.length)} of ${bytes(last.size)})")
                        }
                    }
                }
            }
        }
    }
}
