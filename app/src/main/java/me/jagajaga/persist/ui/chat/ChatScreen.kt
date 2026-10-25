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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import me.jagajaga.persist.ui.viewer.MediaPreview
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
import me.jagajaga.persist.ui.markdown.MarkdownText
import java.io.ByteArrayOutputStream

/**
 * What stays put while you swipe between chats: the title bar, the strip of
 * chats and the composer, all for whichever chat is in view. Only [thread] --
 * the conversation itself -- moves under the finger.
 */
@Composable
fun ChatFrame(
    store: Store,
    sessionId: String,
    onBack: () -> Unit,
    /** The chats along the top, in swipe order; this one among them. */
    strip: List<SessionInfo>,
    onSelect: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onMove: (String) -> Unit,
    banner: @Composable () -> Unit,
    thread: @Composable ColumnScope.() -> Unit,
) {
    val sessions by store.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val events by store.events(sessionId).collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.page)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(session, onBack, onStop = { scope.launch { runCatching { store.interrupt(sessionId) } } }, onClose = { onCloseTab(sessionId) }, onMove = { onMove(sessionId) })
        if (strip.isNotEmpty()) ChatStrip(strip, sessionId, onSelect, onCloseTab)
        banner()
        thread()
        val models by store.models.collectAsState()
        val allAgents by store.agents.collectAsState()
        // Where this chat is working, refreshed when it opens and as each turn ends.
        var places by remember(sessionId) { mutableStateOf<List<me.jagajaga.persist.data.WorkPlace>>(emptyList()) }
        val turns = remember(events) { events.count { ThreadModel.isResult(it) } }
        LaunchedEffect(sessionId, turns) { runCatching { store.workPlaces(sessionId) }.onSuccess { places = it } }
        var optionsOpen by remember { mutableStateOf(false) }
        var compactAsk by remember { mutableStateOf(false) }
        // One composer, following the chat in view; each chat keeps its own draft.
        key(sessionId) { Composer(
            session,
            draftKey = sessionId,
            pill = session?.let { Options.pill(it.model, it.activeModel, it.effort, models) } ?: "Default",
            filesDir = store.outgoingFilesDir,
            contextFill = remember(events) { ThreadModel.contextFill(events) },
            agents = allAgents[sessionId].orEmpty(),
            places = places,
            onStopAgent = { taskId -> scope.launch { runCatching { store.stopAgent(sessionId, taskId) } } },
            onCompact = { compactAsk = true },
            onOptions = { optionsOpen = true },
            onToggleBypass = {
                val bypass = session?.permissionMode == "bypassPermissions"
                scope.launch { runCatching { store.setPermissionMode(sessionId, if (bypass) "default" else "bypassPermissions") } }
            },
        ) { text, images, files -> store.send(sessionId, text, images, files) } }
        if (optionsOpen && session != null) OptionsSheet(store, session) { optionsOpen = false }
        if (compactAsk) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { compactAsk = false },
                title = { Text("Compact this conversation?") },
                text = { Text("Claude summarises the conversation so far to free context. It runs as the next turn.", style = Type.body) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { compactAsk = false; store.send(sessionId, "/compact") }) { Text("Compact") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { compactAsk = false }) { Text("Cancel") } },
            )
        }
    }
}

/** One chat's conversation: the part a sideways swipe moves. */
@Composable
fun ChatThread(store: Store, sessionId: String, modifier: Modifier = Modifier) {
    // Opened: its notifications have done their job.
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(sessionId) { me.jagajaga.persist.notify.Notifications.clearChat(appContext, sessionId) }
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

    Column(modifier) {
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
        // Where you were in this chat last time: at the bottom (the usual), or
        // somewhere up, reading. Pinned is decided by your own scrolling only,
        // so a burst of new messages can't knock you off the bottom.
        val remembered = remember(sessionId) { ScrollMemory.get(sessionId) }
        var pinned by remember(sessionId) { mutableStateOf(remembered?.pinned ?: true) }
        var landed by remember(sessionId) { mutableStateOf(false) }
        val headerRows = if (hasEarlier) 1 else 0
        suspend fun toBottom() {
            // Past the end is clamped to the last row; the scrollBy then reaches
            // the bottom of that row even when it is taller than the screen.
            listState.scrollToItem(items.size + 3)
            listState.scrollBy(100_000f)
        }
        LaunchedEffect(sessionId, items.isNotEmpty()) {
            if (landed || items.isEmpty()) return@LaunchedEffect
            val at = remembered?.key?.let { key -> items.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
            if (!pinned && at != null) listState.scrollToItem(at + headerRows, remembered!!.offset) else toBottom()
            landed = true
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { moving ->
                if (!moving && landed) pinned = !listState.canScrollForward
            }
        }
        DisposableEffect(sessionId) {
            onDispose {
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is String && (it.key as String).startsWith("e") }
                ScrollMemory.put(sessionId, ScrollMemory.Place(pinned, first?.key as? String, first?.let { -it.offset } ?: 0))
            }
        }
        // Follow the conversation while pinned to the bottom.
        LaunchedEffect(items.size, streaming.length, session?.working) {
            if (landed && pinned) toBottom()
        }
        // The keyboard opening or closing resizes the list from below. At the
        // bottom, stay at the bottom: the newest line, the working row with its
        // Stop, sits just above the input either way. (Scrolling back by the
        // height the keyboard gave up hid exactly that row.) Reading further
        // up, keep the same lines on screen instead.
        LaunchedEffect(listState) {
            var last = 0
            snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { height ->
                if (last != 0 && height != last && landed) {
                    if (pinned) toBottom() else if (height < last) listState.scrollBy((last - height).toFloat())
                }
                last = height
            }
        }
        // Reaching the top pages the earlier part in; the list keeps its place.
        LaunchedEffect(listState, hasEarlier) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .collect { first -> if (landed && first <= 1 && hasEarlier) loadEarlier() }
        }
        // The prompt behind what you are reading, and every other one a tap away.
        val prompts = remember(items) { ThreadModel.prompts(items) }
        val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex - headerRows } }
        val reading = prompts.firstOrNull { it.first <= firstVisible }?.second ?: prompts.lastOrNull()?.second
        if (reading != null) PromptBar(reading, prompts) { index -> pinned = false; scope.launch { listState.animateScrollToItem(index + headerRows) } }
        // Every picture and video here: a tapped preview opens the viewer on it, swiping through the rest.
        val media = remember(items) { ThreadModel.media(items) }
        androidx.compose.runtime.CompositionLocalProvider(me.jagajaga.persist.ui.viewer.LocalGallery provides media) {
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
            if (streaming.isNotBlank()) item(key = "streaming") { StreamingText(streaming) }
            if (session?.working == true) item(key = "working") {
                WorkingRow(ThreadModel.turnStart(events), onStop = { scope.launch { runCatching { store.interrupt(sessionId) } } })
            }
        }
        }
        // Waiting on you: pinned above the input so it is never scrolled past.
        val waiting = remember(items) { ThreadModel.waiting(items) }
        if (waiting.isNotEmpty()) WaitingBar(waiting) { index -> pinned = false; scope.launch { listState.animateScrollToItem(index + headerRows) } }
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

/** Your open chats as tabs: tap one, or swipe the chat sideways; ✕ closes it. */
@Composable
private fun ChatStrip(strip: List<SessionInfo>, current: String, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    val state = rememberLazyListState()
    val index = strip.indexOfFirst { it.id == current }
    LaunchedEffect(index) { if (index >= 0) state.animateScrollToItem((index - 1).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().background(Palette.surface),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(strip, key = { it.id }) { s ->
            val on = s.id == current
            Row(
                Modifier
                    .background(if (on) Palette.accentSoft else Palette.surface, RoundedCornerShape(50))
                    .border(1.dp, if (on) Palette.accent else Palette.border, RoundedCornerShape(50))
                    .clickable(enabled = !on) { onSelect(s.id) }
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(working = s.working, error = s.status == "error")
                Spacer(Modifier.width(6.dp))
                Text(s.title, style = Type.tiny.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                // A finger-sized target, not just the glyph.
                Box(Modifier.size(26.dp).clickable { onClose(s.id) }, contentAlignment = Alignment.Center) {
                    Text("✕", style = Type.tiny.copy(color = Palette.muted))
                }
            }
        }
    }
}

@Composable
private fun TopBar(session: SessionInfo?, onBack: () -> Unit, onStop: () -> Unit, onClose: () -> Unit, onMove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.surface).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Column(Modifier.weight(1f)) {
            Text(session?.title ?: "", style = Type.title.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The folder: tap to move the chat somewhere else.
            Text(
                (session?.cwd ?: "") + "  ✎",
                style = Type.tiny.copy(color = Palette.accent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = session != null, onClick = onMove),
            )
        }
        if (session?.working == true) {
            IconButton(onClick = onStop) { Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Palette.error) }
        }
        // Close this tab: the chat goes on running on the server, it just leaves your tabs.
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close tab") }
    }
}

/** "☰ What was the problem?" -- which exchange you are reading. */
@Composable
private fun PromptBar(text: String, prompts: List<Pair<Int, String>>, onJump: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().background(Palette.surface).clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Jump to a prompt", tint = Palette.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text.replace('\n', ' '), style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            for ((index, prompt) in prompts) {
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (prompt == text) "✓ " else "   ") + prompt.replace('\n', ' '),
                            style = Type.small.copy(color = Palette.text),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { open = false; onJump(index) },
                )
            }
        }
    }
}

/** "? Allow Bash?  +1 more  Show": what is waiting on you, above the input. */
@Composable
private fun WaitingBar(waiting: List<Pair<Int, ThreadItem>>, onShow: (Int) -> Unit) {
    val (index, first) = waiting.first()
    val label = when (first) {
        is ThreadItem.Permission -> "Allow ${first.toolName}?"
        is ThreadItem.Question -> first.questions.firstOrNull()?.question ?: "Claude asked a question"
        else -> ""
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.accentSoft).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("? ", style = Type.body.copy(color = Palette.warn, fontWeight = FontWeight.SemiBold))
        Text(label, style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (waiting.size > 1) Text("+${waiting.size - 1} more  ", style = Type.tiny)
        Text("Show", style = Type.small.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable { onShow(index) }.padding(6.dp))
    }
}

/** The panel's branch chip: "⎇ main", a worktree's name, or "⋔ 3"; tap for every place, the current one ticked. */
@Composable
private fun PlacesChip(places: List<me.jagajaga.persist.data.WorkPlace>) {
    var open by remember { mutableStateOf(false) }
    val one = places.singleOrNull()
    val text = if (one != null) (if (one.worktree) "⋔ " else "⎇ ") + one.name else "⋔ ${places.size}"
    Box {
        Text(
            text,
            style = Type.tiny.copy(color = Palette.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 110.dp)
                .border(1.dp, Palette.border, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (p in places) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text((if (p.current) "✓ " else "   ") + (if (p.worktree) "⋔ " else "⎇ ") + p.name, style = Type.small.copy(color = Palette.text))
                            p.branch?.takeIf { it != p.name }?.let { Text("on $it", style = Type.tiny) }
                            Text(p.path, style = Type.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    onClick = { open = false },
                )
            }
        }
    }
}

/** The subagents working, as a count; tap for the list, each with a stop. */
@Composable
private fun AgentsChip(agents: List<me.jagajaga.persist.data.ActiveAgent>, onStop: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .border(1.dp, Palette.ok, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(working = true)
            Spacer(Modifier.width(4.dp))
            Text("${agents.size}", style = Type.small.copy(color = Palette.ok))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in agents) {
                DropdownMenuItem(
                    text = { Text(a.description.ifBlank { a.kind ?: "subagent" }, style = Type.small.copy(color = Palette.text), maxLines = 2) },
                    trailingIcon = {
                        val task = a.taskId
                        if (task != null) Text("×", style = Type.title.copy(color = Palette.error), modifier = Modifier.clickable { onStop(task); open = false }.padding(6.dp))
                    },
                    onClick = {},
                )
            }
        }
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
            MediaRow(remember(item.text) { Links.mediaIn(item.text) })
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
    Column(Modifier.agentStripe(item.agent).padding(start = if (item.agent != null) 10.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.agent?.let { AgentChip(it) }
        MarkdownText(item.text)
        MediaRow(remember(item.text) { Links.mediaIn(item.text) })
    }
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m ${s % 60}s" else "${s / 3600}h ${(s % 3600) / 60}m"
}

/** TodoWrite as the panel shows it: done struck through, the active one named by what it is doing. */
@Composable
private fun TodoList(todos: List<Todo>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in todos) {
            val done = t.status == "completed"
            val active = t.status == "in_progress"
            Row(verticalAlignment = Alignment.Top) {
                Text(if (done) "☑" else if (active) "◐" else "☐", style = Type.body.copy(color = if (active) Palette.accent else Palette.muted), modifier = Modifier.width(22.dp))
                Text(
                    if (active) t.activeForm ?: t.content else t.content,
                    style = Type.small.copy(
                        color = if (done) Palette.faint else Palette.text,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    ),
                )
            }
        }
    }
}

/** An edit's removed lines in red and added lines in green. */
@Composable
private fun DiffBlock(label: String, lines: List<DiffLine>) {
    Column {
        Text(label, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.codeBg, RoundedCornerShape(6.dp))
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            for (line in lines.take(2_000)) {
                val (bg, fg) = when (line.sign) {
                    '-' -> Color(0xFFFDECEC) to Color(0xFFB3261E)
                    '+' -> Color(0xFFE8F5E9) to Color(0xFF1B5E20)
                    else -> Color.Transparent to Palette.muted
                }
                Text(
                    "${line.sign} ${line.text}",
                    style = Type.code.copy(color = fg),
                    softWrap = false,
                    modifier = Modifier.background(bg).padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** A reply as it arrives, with the panel's blinking caret at its end. */
@Composable
private fun StreamingText(text: String) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(530); on = !on } }
    MarkdownText(text + if (on) " ▍" else "  ")
}

/** Previews of the pictures and videos a message names, one under another. */
@Composable
private fun MediaRow(media: List<String>, maxHeight: androidx.compose.ui.unit.Dp = 240.dp) {
    if (media.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in media) MediaPreview(m, maxHeight = maxHeight)
    }
}

@Composable
private fun AgentChip(name: String) {
    val color = agentColor(name)
    Text(
        name,
        style = Type.tiny.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Each subagent its own colour, as the panel gives them: the same name always the same colour. */
private val AGENT_COLORS = listOf(
    Color(0xFF2E7D32), Color(0xFF6A1B9A), Color(0xFFC2185B), Color(0xFF00838F),
    Color(0xFFEF6C00), Color(0xFF283593), Color(0xFF558B2F), Color(0xFF8D6E63),
)

internal fun agentColor(name: String): Color = AGENT_COLORS[Math.floorMod(name.hashCode(), AGENT_COLORS.size)]

/** The coloured stripe down the left of a subagent's message or tool card. */
private fun Modifier.agentStripe(agent: String?): Modifier =
    if (agent == null) this else this.drawBehind {
        drawRect(agentColor(agent), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
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
            .border(1.dp, item.agent?.let { agentColor(it).copy(alpha = 0.6f) } ?: Palette.border, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .agentStripe(item.agent)
            .clickable { open = !open }
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(working = !item.done, error = item.isError)
            item.agent?.let { AgentChip(it) }
            Text(item.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
            Text(item.label, style = Type.code.copy(color = Palette.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        // Claude looking at a picture: show the picture. And whatever pictures
        // or videos the call made or named -- a screenshot saved, a clip cut.
        val readsImage = item.name == "Read" && item.label.startsWith("/") && Links.kind(item.label) == FileKind.Image
        if (readsImage) {
            Spacer(Modifier.size(8.dp))
            ServerImage(item.label, maxHeight = 200.dp)
        }
        item.todos?.let { Spacer(Modifier.size(8.dp)); TodoList(it) }
        if (item.done) {
            val media = remember(item.input, item.result) {
                Links.mediaIn(item.input + "\n" + (item.result ?: ""), max = 4).filter { !(readsImage && it == item.label) }
            }
            if (media.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                MediaRow(media, maxHeight = 180.dp)
            }
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
            val diff = item.diff
            if (diff != null) DiffBlock(if (item.name == "Write") "NEW" else "DIFF", diff) else DetailBlock("IN", item.input)
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
    // Per question: the options picked (several, for multi-select) and any "Other…" text.
    val picked = remember(item.key) { mutableStateMapOf<String, Set<String>>() }
    val other = remember(item.key) { mutableStateMapOf<String, String>() }
    val otherOpen = remember(item.key) { mutableStateMapOf<String, Boolean>() }
    fun answerFor(q: Q): String? {
        val typed = other[q.question]?.trim().orEmpty().takeIf { otherOpen[q.question] == true && it.isNotEmpty() }
        val chosen = picked[q.question].orEmpty()
        return (chosen.toList() + listOfNotNull(typed)).takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
    // One single-choice question: a tap is the answer, as in the panel.
    val oneTap = item.questions.size == 1 && !item.questions[0].multiSelect
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
                val selected = if (answered != null) answered.split(", ").contains(label) else label in picked[q.question].orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (selected) Palette.accent else Palette.border, RoundedCornerShape(10.dp))
                        .background(if (selected) Palette.accentSoft else Palette.surface, RoundedCornerShape(10.dp))
                        .clickable(enabled = !item.resolved) {
                            if (oneTap) {
                                onAnswer(mapOf(q.question to label))
                            } else {
                                val now = picked[q.question].orEmpty()
                                picked[q.question] = if (q.multiSelect) (if (label in now) now - label else now + label) else setOf(label)
                                if (!q.multiSelect) otherOpen[q.question] = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text((if (q.multiSelect) (if (selected) "☑ " else "☐ ") else "") + label, style = Type.body.copy(fontWeight = FontWeight.Medium))
                    description?.let { Text(it, style = Type.small) }
                }
            }
            if (!item.resolved) {
                if (otherOpen[q.question] == true) {
                    androidx.compose.material3.OutlinedTextField(
                        value = other[q.question].orEmpty(),
                        onValueChange = { other[q.question] = it },
                        placeholder = { Text("Your answer") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "Other…",
                        style = Type.body.copy(color = Palette.accent),
                        modifier = Modifier.clickable {
                            otherOpen[q.question] = true
                            if (!q.multiSelect) picked[q.question] = emptySet()
                        }.padding(vertical = 4.dp),
                    )
                }
            } else if (answered != null && q.options.none { answered.split(", ").contains(it.first) }) {
                Text("Answered: $answered", style = Type.small)
            }
        }
        if (!item.resolved && (!oneTap || otherOpen[item.questions[0].question] == true)) {
            val ready = item.questions.all { answerFor(it) != null }
            Button(
                onClick = { onAnswer(item.questions.associate { it.question to answerFor(it)!! }) },
                enabled = ready,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
            ) { Text("Answer") }
        }
    }
}

@Composable
private fun WorkingRow(startedAt: Long?, onStop: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(working = true)
        Spacer(Modifier.width(8.dp))
        // "Working… 14:02 · 1m 12s", as the panel's row: when it began and how long so far.
        val since = startedAt?.let { " ${clock(it)} · ${elapsed(now - it)}" } ?: ""
        Text("Working…$since", style = Type.small, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onStop, border = BorderStroke(1.dp, Palette.border)) { Text("Stop", color = Palette.text) }
    }
}

/** The panel's composer: a rounded box with + and the model pill, and the blue send button. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    session: SessionInfo?,
    draftKey: String,
    pill: String,
    filesDir: java.io.File,
    contextFill: Double?,
    agents: List<me.jagajaga.persist.data.ActiveAgent>,
    places: List<me.jagajaga.persist.data.WorkPlace>,
    onStopAgent: (String) -> Unit,
    onCompact: () -> Unit,
    onOptions: () -> Unit,
    onToggleBypass: () -> Unit,
    onSend: (String, List<OutgoingImage>, List<OutgoingFile>) -> Unit,
) {
    // Each chat keeps what you had typed into it, across swipes to other chats.
    val textState = rememberTextFieldState(Drafts.get(draftKey))
    DisposableEffect(draftKey) { onDispose { Drafts.put(draftKey, textState.text.toString()) } }
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
                    .widthIn(max = 150.dp)
                    .border(1.dp, Palette.border, RoundedCornerShape(50))
                    .clickable(enabled = session != null, onClick = onOptions)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            contextFill?.let { Box(Modifier.clickable(onClick = onCompact).padding(4.dp)) { ContextRing(it) } }
            if (places.isNotEmpty()) PlacesChip(places)
            // Everything after this sits at the right edge, as in the panel.
            Spacer(Modifier.weight(1f))
            if (agents.isNotEmpty()) AgentsChip(agents, onStopAgent)
            // Bypass permissions: the panel's shield, as an icon only, filled when on.
            val bypass = session?.permissionMode == "bypassPermissions"
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (bypass) Palette.warn else Palette.surface, CircleShape)
                    .border(1.dp, if (bypass) Palette.warn else Palette.border, CircleShape)
                    .clickable(enabled = session != null, onClick = onToggleBypass),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Shield,
                    contentDescription = if (bypass) "Bypass permissions: on" else "Bypass permissions: off",
                    tint = if (bypass) Color.White else Palette.muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            val canSend = text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()
            Box(
                Modifier
                    .size(52.dp)
                    .background(if (canSend) Palette.accent else Palette.border, CircleShape)
                    .clickable(enabled = canSend) {
                        onSend(text.trim(), images.toList(), files.toList())
                        textState.clearText()
                        images.clear()
                        files.clear()
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
    }
}

/** How full the context window is, as the panel's ring. */
@Composable
private fun ContextRing(fill: Double) {
    val color = when {
        fill > 0.9 -> Palette.error
        fill > 0.75 -> Palette.warn
        else -> Palette.accent
    }
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
        drawCircle(Palette.border, style = stroke, radius = size.minDimension / 2 - stroke.width)
        drawArc(
            color,
            startAngle = -90f,
            sweepAngle = (360 * fill).toFloat(),
            useCenter = false,
            style = stroke,
            topLeft = androidx.compose.ui.geometry.Offset(stroke.width, stroke.width),
            size = androidx.compose.ui.geometry.Size(size.width - 2 * stroke.width, size.height - 2 * stroke.width),
        )
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
