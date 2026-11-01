package me.jagajaga.persist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.jagajaga.persist.data.FeedItem
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.chat.FeedEntry
import me.jagajaga.persist.ui.chat.FeedModel
import me.jagajaga.persist.ui.chat.ThreadItemView

/**
 * Every agent's conversation as one chat: what each is saying and doing, in
 * the order it happens, with the chat's name above each run of its messages.
 * Drawn with the chat screen's own pieces, so a tool card or a permission
 * reads -- and answers -- the same here as there.
 */
@Composable
fun FeedScreen(store: Store, items: List<FeedItem>, onOpen: (String) -> Unit) {
    DisposableEffect(Unit) {
        store.wantFeed(true)
        onDispose { store.wantFeed(false) }
    }
    val sessions by store.sessions.collectAsState()
    val titles = remember(sessions) { sessions.associate { it.id to it.title } }
    val working = remember(sessions) { sessions.filter { it.working }.map { it.id }.toSet() }
    val entries = remember(items, titles) { FeedModel.build(items, titles) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // The chat a reply goes to: picked by tapping any of its messages or its name.
    var replyTo by remember { mutableStateOf<String?>(null) }

    // Like a chat: open on the newest, then follow while you are at the bottom.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= last - 3 } ?: true
        if (!landed || atBottom) listState.scrollToItem(entries.size + 1)
        landed = true
    }

    Column(Modifier.fillMaxSize().background(Palette.page).imePadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (entries.isEmpty()) Text("Nothing yet", style = Type.small, modifier = Modifier.align(Alignment.Center))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is FeedEntry.Header -> ChatHeader(
                        entry,
                        entry.sessionId in working,
                        picked = entry.sessionId == replyTo,
                        onPick = { replyTo = entry.sessionId },
                        onOpen = { onOpen(entry.sessionId) },
                    )
                    is FeedEntry.Item -> {
                        val picked = entry.sessionId == replyTo
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable { replyTo = entry.sessionId }
                                .then(if (picked) Modifier.drawBehind { drawRect(Palette.accent, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }.padding(start = 9.dp) else Modifier),
                        ) {
                            ThreadItemView(
                                entry.item,
                                onPermission = { requestId, allow ->
                                    scope.launch { runCatching { store.answerPermission(entry.sessionId, requestId, allow) } }
                                },
                                onAnswers = { requestId, answers ->
                                    scope.launch { runCatching { store.answerPermission(entry.sessionId, requestId, true, answers) } }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    replyTo?.let { id ->
        ReplyBar(titles[id] ?: "chat", onCancel = { replyTo = null }) { text -> store.send(id, text) }
    }
    }
}

/** "Replying to blp|Jean": a message to the picked chat, without leaving the feed. */
@Composable
private fun ReplyBar(title: String, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val state = rememberTextFieldState()
    val text = state.text.toString()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .border(1.5.dp, Palette.accent, RoundedCornerShape(16.dp))
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Replying to ", style = Type.tiny)
            Text(title, style = Type.tiny.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(28.dp).clickable(onClick = onCancel), contentAlignment = Alignment.Center) { Text("✕", style = Type.small) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).heightIn(min = 28.dp, max = 160.dp)) {
                if (text.isEmpty()) Text("Message this chat…", style = Type.body.copy(color = Palette.faint))
                BasicTextField(state = state, textStyle = Type.body, cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(8.dp))
            val canSend = text.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .background(if (canSend) Palette.accent else Palette.border, CircleShape)
                    .clickable(enabled = canSend) {
                        onSend(text.trim())
                        state.clearText()
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White) }
        }
    }
}

/** Whose messages follow: tap the name to open that chat; tap a message to reply from here. */
@Composable
private fun ChatHeader(entry: FeedEntry.Header, working: Boolean, picked: Boolean, onPick: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(working = working)
        Spacer(Modifier.width(8.dp))
        Text(
            entry.title.ifBlank { "Untitled chat" },
            style = Type.small.copy(color = Palette.accent, fontWeight = if (picked) FontWeight.Bold else FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(clock(entry.ts), style = Type.tiny)
        Spacer(Modifier.width(6.dp))
        Text("Reply", style = Type.tiny.copy(color = if (picked) Palette.accent else Palette.muted, fontWeight = FontWeight.SemiBold), modifier = Modifier.clickable(onClick = onPick).padding(horizontal = 6.dp, vertical = 8.dp))
        Text("Open ›", style = Type.tiny.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 2.dp, top = 8.dp, bottom = 8.dp))
    }
    HorizontalDivider(color = Palette.border)
}
