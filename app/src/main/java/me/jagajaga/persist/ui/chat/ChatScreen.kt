package me.jagajaga.persist.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import me.jagajaga.persist.ui.viewer.FileKind
import me.jagajaga.persist.ui.viewer.Links
import me.jagajaga.persist.ui.viewer.LocalImage
import me.jagajaga.persist.ui.viewer.ServerImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.jagajaga.persist.data.OutgoingFile
import me.jagajaga.persist.data.OutgoingImage
import me.jagajaga.persist.data.SessionInfo
import me.jagajaga.persist.data.SessionLog
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.StatusDot
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.clock
import me.jagajaga.persist.ui.list.ChatOrder
import me.jagajaga.persist.ui.markdown.MarkdownText
import java.io.ByteArrayOutputStream

@Composable
fun ChatScreen(
    store: Store,
    sessionId: String,
    onBack: () -> Unit,
    onSwitch: (String) -> Unit,
    banner: @Composable () -> Unit,
) {
    DisposableEffect(sessionId) {
        store.openChat(sessionId)
        onDispose { store.closeChat(sessionId) }
    }
    val sessions by store.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val events by store.events(sessionId).collectAsState()
    val outgoing by store.outgoing.collectAsState()
    val streaming by store.streaming(sessionId).collectAsState()
    val mine = remember(outgoing, sessionId) { outgoing.filter { it.sessionId == sessionId } }
    val items = remember(events, mine) { ThreadModel.build(events, mine) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.page)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(session, onBack, onStop = { scope.launch { runCatching { store.interrupt(sessionId) } } })
        val others = remember(sessions, sessionId) { ChatOrder.active(sessions).filter { it.id != sessionId } }
        if (others.isNotEmpty()) ActiveStrip(others, onSwitch)
        banner()
        ThreadModel.lastPrompt(items)?.let { PromptBar(it) }

        val listState = rememberLazyListState()
        val hasEarlier = (events.firstOrNull()?.let { SessionLog.seqOf(it) } ?: 0) > 0
        var loadingEarlier by remember(sessionId) { mutableStateOf(false) }
        var earlierError by remember(sessionId) { mutableStateOf<String?>(null) }
        fun loadEarlier() {
            if (loadingEarlier || !hasEarlier) return
            loadingEarlier = true
            earlierError = null
            scope.launch {
                runCatching { store.loadEarlier(sessionId) }.onFailure { earlierError = it.message ?: "failed" }
                loadingEarlier = false
            }
        }
        // A chat opens on its newest message, as soon as there is one to show.
        var landed by remember(sessionId) { mutableStateOf(false) }
        LaunchedEffect(sessionId, items.isNotEmpty()) {
            if (!landed && items.isNotEmpty()) {
                // Counted from the data, not the layout, which may not be measured yet;
                // the list clamps an index past its end to the last item.
                listState.scrollToItem(items.size + 3)
                landed = true
            }
        }
        // Then follow the conversation while you are at the bottom of it; leave
        // you be if you have scrolled up to read.
        LaunchedEffect(items.size, streaming.length) {
            if (!landed) return@LaunchedEffect
            val last = listState.layoutInfo.totalItemsCount - 1
            val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= last - 3 } ?: true
            if (atBottom && last >= 0) listState.scrollToItem(last)
        }
        // Reaching the top pages the earlier part in; the list keeps its place.
        LaunchedEffect(listState, hasEarlier) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { first -> if (landed && first <= 1 && hasEarlier) loadEarlier() }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hasEarlier) {
                item(key = "earlier") {
                    EarlierRow(loading = loadingEarlier, error = earlierError, onLoad = ::loadEarlier)
                }
            }
            items(items, key = { it.key }) { item ->
                ThreadItemView(
                    item,
                    onPermission = { requestId, allow ->
                        scope.launch { runCatching { store.answerPermission(sessionId, requestId, allow) } }
                    },
                    onAnswers = { requestId, answers ->
                        scope.launch { runCatching { store.answerPermission(sessionId, requestId, true, answers) } }
                    },
                )
            }
            if (streaming.isNotBlank()) item(key = "streaming") { MarkdownText(streaming) }
            if (session?.working == true) item(key = "working") { WorkingRow(onStop = { scope.launch { runCatching { store.interrupt(sessionId) } } }) }
        }
        val models by store.models.collectAsState()
        var optionsOpen by remember { mutableStateOf(false) }
        Composer(
            session,
            pill = session?.let { Options.pill(it.model, it.activeModel, it.effort, models) } ?: "Default",
            filesDir = store.outgoingFilesDir,
            onOptions = { optionsOpen = true },
        ) { text, images, files -> store.send(sessionId, text, images, files) }
        if (optionsOpen && session != null) OptionsSheet(store, session) { optionsOpen = false }
    }
}

/** One item of a thread, drawn the same in a chat and in the feed. */
@Composable
fun ThreadItemView(
    item: ThreadItem,
    onPermission: (requestId: String, allow: Boolean) -> Unit,
    onAnswers: (requestId: String, answers: Map<String, String>) -> Unit,
) {
    when (item) {
        is ThreadItem.User -> UserBubble(item)
        is ThreadItem.Assistant -> AssistantText(item)
        is ThreadItem.Tool -> ToolCard(item)
        is ThreadItem.Footer -> Footer(item)
        is ThreadItem.Permission -> PermissionCard(item) { allow -> onPermission(item.requestId, allow) }
        is ThreadItem.Question -> QuestionCard(item) { answers -> onAnswers(item.requestId, answers) }
        is ThreadItem.Notice -> Text(
            item.text,
            style = Type.small.copy(color = if (item.error) Palette.error else Palette.muted),
        )
    }
}

@Composable
private fun EarlierRow(loading: Boolean, error: String?, onLoad: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(enabled = !loading, onClick = onLoad).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            when {
                loading -> "Loading earlier messages…"
                error != null -> "Couldn't load earlier messages — tap to retry"
                else -> "Load earlier messages"
            },
            style = Type.small.copy(color = if (error != null) Palette.error else Palette.accent),
        )
    }
}

/** The other chats working right now, one tap away. */
@Composable
private fun ActiveStrip(others: List<SessionInfo>, onSwitch: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (s in others) {
            Row(
                Modifier
                    .border(1.dp, Palette.border, RoundedCornerShape(50))
                    .clickable { onSwitch(s.id) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(working = true)
                Spacer(Modifier.width(6.dp))
                Text(s.title, style = Type.tiny.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
        }
    }
}

@Composable
private fun TopBar(session: SessionInfo?, onBack: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.surface).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f)) {
            Text(session?.title ?: "", style = Type.title.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(session?.cwd ?: "", style = Type.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (session?.working == true) {
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Palette.error) }
        }
    }
}

/** "☰ What was the problem?" -- which exchange you are reading. */
@Composable
private fun PromptBar(text: String) {
    Row(
        Modifier.fillMaxWidth().background(Palette.surface).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Menu, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text.replace('\n', ' '), style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UserBubble(item: ThreadItem.User) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.5.dp, Palette.accent, RoundedCornerShape(12.dp))
                .background(Palette.surface, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (item.text.isNotBlank()) Text(item.text, style = Type.body)
            if (item.images.isNotEmpty()) {
                // Pictures as pictures; anything else (or a picture with no copy on the server) as a chip.
                val uri = LocalUriHandler.current
                item.images.forEachIndexed { i, name ->
                    val path = item.paths.getOrNull(i)
                    if (path != null && Links.kind(path) == FileKind.Image) {
                        ServerImage(path)
                    } else {
                        Chip("▣ $name", onClick = path?.let { p -> { uri.openUri(p) } })
                    }
                }
            }
        }
        if (item.sending) {
            Text("sending…", style = Type.tiny, modifier = Modifier.align(Alignment.End).padding(top = 3.dp, end = 4.dp))
        }
    }
}

@Composable
private fun AssistantText(item: ThreadItem.Assistant) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.agent?.let { AgentChip(it) }
        MarkdownText(item.text)
    }
}

@Composable
private fun AgentChip(name: String) {
    Text(
        name,
        style = Type.tiny.copy(color = Palette.ok),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .border(1.dp, Palette.ok, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun Chip(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = Type.tiny.copy(color = if (onClick != null) Palette.accent else Palette.text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(Palette.codeBg, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

private val FILE_TOOLS = setOf("Read", "Write", "Edit", "MultiEdit", "NotebookEdit")

/** Like the panel's: dot, agent, bold tool name, the command or path in mono. Tap for detail. */
@Composable
private fun ToolCard(item: ThreadItem.Tool) {
    var open by remember(item.key) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(working = !item.done, error = item.isError)
            item.agent?.let { AgentChip(it) }
            Text(item.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
            Text(item.label, style = Type.code.copy(color = Palette.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        // Claude looking at a picture: show the picture.
        if (item.name == "Read" && item.label.startsWith("/") && Links.kind(item.label) == FileKind.Image) {
            Spacer(Modifier.size(8.dp))
            ServerImage(item.label, maxHeight = 200.dp)
        }
        if (open) {
            if (item.name in FILE_TOOLS && item.label.startsWith("/")) {
                val uri = LocalUriHandler.current
                Text(
                    "Open ${item.label.substringAfterLast('/')}",
                    style = Type.small.copy(color = Palette.accent),
                    modifier = Modifier.padding(top = 8.dp).clickable { uri.openUri(item.label) },
                )
            }
            Spacer(Modifier.size(8.dp))
            DetailBlock("IN", item.input)
            item.result?.let {
                Spacer(Modifier.size(6.dp))
                DetailBlock("OUT", it, error = item.isError)
            }
        }
    }
}

@Composable
private fun DetailBlock(label: String, body: String, error: Boolean = false) {
    Column {
        Text(label, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold))
        // Paths in a tool's output -- a screenshot it saved, a log it wrote -- open on tap.
        val shown = remember(body) {
            buildAnnotatedString {
                for ((piece, link) in Links.segments(body.take(20_000))) {
                    if (link == null) append(piece)
                    else withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = Palette.accent)))) { append(piece) }
                }
            }
        }
        Text(
            shown,
            style = Type.code.copy(color = if (error) Palette.error else Palette.text),
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.codeBg, RoundedCornerShape(6.dp))
                .heightIn(max = 320.dp)
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        )
    }
}

@Composable
private fun Footer(item: ThreadItem.Footer) {
    Text(
        ThreadModel.footerText(item) { clock(it) },
        style = Type.tiny,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun PermissionCard(item: ThreadItem.Permission, onAnswer: (Boolean) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, if (item.resolved) Palette.border else Palette.warn, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Allow ${item.toolName}?", style = Type.body.copy(fontWeight = FontWeight.SemiBold))
        if (item.detail.isNotBlank()) Text(item.detail, style = Type.code.copy(color = Palette.muted))
        if (item.resolved) {
            Text(if (item.allowed == true) "Allowed" else "Denied", style = Type.small)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onAnswer(true) }, colors = ButtonDefaults.buttonColors(containerColor = Palette.accent)) { Text("Allow") }
                OutlinedButton(onClick = { onAnswer(false) }) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun QuestionCard(item: ThreadItem.Question, onAnswer: (Map<String, String>) -> Unit) {
    val chosen = remember(item.key) { mutableStateMapOf<String, String>() }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, if (item.resolved) Palette.border else Palette.accent, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (q in item.questions) {
            Text(q.header.uppercase(), style = Type.tiny.copy(fontWeight = FontWeight.SemiBold))
            Text(q.question, style = Type.body)
            val answered = item.answers[q.question]
            for ((label, description) in q.options) {
                val selected = (answered ?: chosen[q.question]) == label
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (selected) Palette.accent else Palette.border, RoundedCornerShape(10.dp))
                        .background(if (selected) Palette.accentSoft else Palette.surface, RoundedCornerShape(10.dp))
                        .clickable(enabled = !item.resolved) { chosen[q.question] = label }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(label, style = Type.body.copy(fontWeight = FontWeight.Medium))
                    description?.let { Text(it, style = Type.small) }
                }
            }
        }
        if (!item.resolved) {
            val ready = item.questions.all { chosen[it.question] != null }
            Button(
                onClick = { onAnswer(chosen.toMap()) },
                enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
            ) { Text("Answer") }
        }
    }
}

@Composable
private fun WorkingRow(onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(working = true)
        Spacer(Modifier.width(8.dp))
        Text("Working…", style = Type.small, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onStop, border = BorderStroke(1.dp, Palette.border)) { Text("Stop", color = Palette.text) }
    }
}

/** The panel's composer: a rounded box with + and the model pill, and the blue send button. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    session: SessionInfo?,
    pill: String,
    filesDir: java.io.File,
    onOptions: () -> Unit,
    onSend: (String, List<OutgoingImage>, List<OutgoingFile>) -> Unit,
) {
    val textState = rememberTextFieldState()
    val text = textState.text.toString()
    val images = remember { mutableStateListOf<OutgoingImage>() }
    val files = remember { mutableStateListOf<OutgoingFile>() }
    var attachMenu by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        uris.forEach { uri -> encodeImage(context, uri)?.let { images += it } }
    }
    // Any file at all; copied off the main thread, since it may be large.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            for (uri in uris) {
                val copied = withContext(Dispatchers.IO) { copyForUpload(context, uri, filesDir) }
                copied.onSuccess { files += it }.onFailure { attachError = it.message }
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .border(1.5.dp, Palette.accent, RoundedCornerShape(16.dp))
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        attachError?.let { Text(it, style = Type.tiny.copy(color = Palette.error), modifier = Modifier.padding(bottom = 4.dp)) }
        if (images.isNotEmpty() || files.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                files.toList().forEach { file ->
                    Row(
                        Modifier.background(Palette.codeBg, RoundedCornerShape(50)).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("▤ ${file.name}", style = Type.tiny.copy(color = Palette.text), maxLines = 1)
                        Box(Modifier.size(32.dp).clickable { files.remove(file); java.io.File(file.localPath).delete() }, contentAlignment = Alignment.Center) {
                            Text("×", style = Type.body)
                        }
                    }
                }
                images.toList().forEach { image ->
                    Row(
                        Modifier.background(Palette.codeBg, RoundedCornerShape(50)).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LocalImage(image.base64, 36.dp)
                        // A real target for a finger, not the glyph.
                        Box(Modifier.size(32.dp).clickable { images.remove(image) }, contentAlignment = Alignment.Center) {
                            Text("×", style = Type.body)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 220.dp)) {
            if (text.isEmpty()) Text("Message Claude…", style = Type.body.copy(color = Palette.faint))
            BasicTextField(
                state = textState,
                textStyle = Type.body,
                cursorBrush = SolidColor(Palette.accent),
                // Images from the keyboard (Gboard's clipboard, stickers) and from a
                // paste: without a receiver the keyboard says the app can't take them.
                modifier = Modifier.fillMaxWidth().contentReceiver(
                    ReceiveContentListener { content ->
                        if (!content.hasMediaType(MediaType.Image)) return@ReceiveContentListener content
                        content.consume { item ->
                            val uri = item.uri ?: return@consume false
                            scope.launch {
                                withContext(Dispatchers.IO) { encodeImage(context, uri) }?.let { images += it }
                                    ?: run { attachError = "Couldn't read that image" }
                            }
                            true
                        }
                    },
                ),
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Box(
                    Modifier
                        .size(36.dp)
                        .border(1.dp, Palette.border, RoundedCornerShape(10.dp))
                        .clickable { attachMenu = true },
                    contentAlignment = Alignment.Center,
                ) { Text("+", style = Type.title) }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(text = { Text("Photo") }, onClick = {
                        attachMenu = false
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    DropdownMenuItem(text = { Text("File") }, onClick = {
                        attachMenu = false
                        attachError = null
                        filePicker.launch(arrayOf("*/*"))
                    })
                }
            }
            Text(
                pill,
                style = Type.small.copy(color = Palette.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .border(1.dp, Palette.border, RoundedCornerShape(50))
                    .clickable(enabled = session != null, onClick = onOptions)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            Spacer(Modifier.weight(1f))
            val canSend = text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()
            Box(
                Modifier
                    .size(42.dp)
                    .background(if (canSend) Palette.accent else Palette.border, CircleShape)
                    .clickable(enabled = canSend) {
                        onSend(text.trim(), images.toList(), files.toList())
                        textState.clearText()
                        images.clear()
                        files.clear()
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White) }
        }
    }
}

/**
 * A picked file, copied into the app's own storage so the send does not
 * depend on the original staying where it is. Refused over the server's limit.
 */
private fun copyForUpload(context: android.content.Context, uri: Uri, dir: java.io.File): Result<OutgoingFile> = runCatching {
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size > MAX_UPLOAD_BYTES) error("$name is over ${MAX_UPLOAD_BYTES / 1024 / 1024} MB")
    val id = java.util.UUID.randomUUID().toString()
    val target = java.io.File(dir, "$id-${name.replace('/', '_')}")
    resolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
    if (target.length() > MAX_UPLOAD_BYTES) {
        target.delete()
        error("$name is over ${MAX_UPLOAD_BYTES / 1024 / 1024} MB")
    }
    OutgoingFile(name = name, localPath = target.path, size = target.length(), uploadId = id)
}

private const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024

/**
 * A picked image as an attachment, shrunk if it is big.
 *
 * The daemon refuses images over 5 MB, and on a bad network a phone photo at
 * full size is a send that never finishes: anything over 1.5 MB is scaled to
 * 2000 px on its long side and re-encoded as JPEG.
 */
private fun encodeImage(context: android.content.Context, uri: Uri): OutgoingImage? = runCatching {
    val resolver = context.contentResolver
    val raw = resolver.openInputStream(uri)!!.use { it.readBytes() }
    val type = resolver.getType(uri) ?: "image/jpeg"
    val name = runCatching {
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "image"
    val bytes: ByteArray
    val mediaType: String
    if (raw.size <= 1_500_000 && type in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) {
        bytes = raw
        mediaType = type
    } else {
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@runCatching null
        val scale = minOf(1f, 2000f / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bytes = out.toByteArray()
        mediaType = "image/jpeg"
    }
    OutgoingImage(name = name, mediaType = mediaType, base64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
}.getOrNull()
