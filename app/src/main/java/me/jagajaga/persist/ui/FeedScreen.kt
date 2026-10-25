package me.jagajaga.persist.ui

import androidx.compose.foundation.background
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

    // Like a chat: open on the newest, then follow while you are at the bottom.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.totalItemsCount - 1
        val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= last - 3 } ?: true
        if (!landed || atBottom) listState.scrollToItem(entries.size + 1)
        landed = true
    }

    Box(Modifier.fillMaxSize().background(Palette.page)) {
        if (entries.isEmpty()) Text("Nothing yet", style = Type.small, modifier = Modifier.align(Alignment.Center))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is FeedEntry.Header -> ChatHeader(entry, entry.sessionId in working) { onOpen(entry.sessionId) }
                    is FeedEntry.Item -> ThreadItemView(
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

/** Whose messages follow: the chat's name, tap to open it. */
@Composable
private fun ChatHeader(entry: FeedEntry.Header, working: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(working = working)
        Spacer(Modifier.width(8.dp))
        Text(
            entry.title.ifBlank { "Untitled chat" },
            style = Type.small.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(clock(entry.ts), style = Type.tiny)
    }
    HorizontalDivider(color = Palette.border)
}
