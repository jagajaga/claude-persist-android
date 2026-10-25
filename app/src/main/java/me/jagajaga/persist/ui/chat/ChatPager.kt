package me.jagajaga.persist.ui.chat

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.list.ChatOrder

/**
 * A chat, and its neighbours a swipe away: the strip's chats, working first
 * then recent, as pages. The order is taken once, when the chat is opened --
 * reshuffling as statuses change would move the page under your finger.
 */
@Composable
fun ChatPager(store: Store, startId: String, onBack: () -> Unit, banner: @Composable () -> Unit) {
    val sessions by store.sessions.collectAsState()
    // Taken again only once the list first arrives, for a cold start straight into a chat.
    val order = remember(sessions.isEmpty()) { ChatOrder.strip(sessions, startId).ifEmpty { null } }
    val ids = order?.map { it.id } ?: listOf(startId)
    val pager = rememberPagerState(initialPage = ids.indexOf(startId).coerceAtLeast(0)) { ids.size }
    val scope = rememberCoroutineScope()
    // The order arriving late must not leave a different chat on screen.
    LaunchedEffect(order != null) { ids.indexOf(startId).takeIf { it >= 0 }?.let { pager.scrollToPage(it) } }
    // Only the conversation sits in the pager: the title bar, the strip and
    // the composer stay where they are and follow the chat in view.
    val current = ids.getOrElse(pager.currentPage) { startId }
    ChatFrame(
        store,
        current,
        onBack = onBack,
        strip = order ?: emptyList(),
        onSelect = { id -> ids.indexOf(id).takeIf { it >= 0 }?.let { scope.launch { pager.animateScrollToPage(it) } } },
        banner = banner,
    ) {
        HorizontalPager(
            state = pager,
            key = { ids[it] },
            beyondViewportPageCount = 0,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            ChatThread(store, ids[page], Modifier.fillMaxSize())
        }
    }
}
