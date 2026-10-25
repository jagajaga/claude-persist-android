package me.jagajaga.persist.ui.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.list.Tabs

/**
 * Your open chats as tabs, a swipe apart. Opening a chat adds its tab; the
 * ✕ on a tab, or the one at top right for the tab in view, closes it. Closing
 * the last returns to the list.
 */
@Composable
fun ChatPager(store: Store, startId: String, onBack: () -> Unit, banner: @Composable () -> Unit) {
    val sessions by store.sessions.collectAsState()
    val saved by store.tabs.collectAsState()
    LaunchedEffect(startId) { store.setTabs(Tabs.open(store.tabs.value, startId)) }
    val known = remember(sessions) { sessions.map { it.id }.toSet() }
    val ids = Tabs.existing(Tabs.open(saved, startId), known).ifEmpty { listOf(startId) }
    var current by remember { mutableStateOf(startId) }
    val pager = rememberPagerState(initialPage = ids.indexOf(startId).coerceAtLeast(0)) { ids.size }
    val scope = rememberCoroutineScope()

    // The tab in view is followed by its id, so tabs opening or closing never
    // leave a different chat on screen.
    LaunchedEffect(ids, current) {
        val at = ids.indexOf(current)
        if (at >= 0 && at != pager.currentPage) pager.scrollToPage(at)
    }
    LaunchedEffect(pager, ids) {
        snapshotFlow { pager.settledPage }.collect { page -> ids.getOrNull(page)?.let { current = it } }
    }

    fun close(id: String) {
        val (left, next) = Tabs.close(ids, id, current)
        store.setTabs(left)
        if (next == null) onBack() else current = next
    }

    ChatFrame(
        store,
        current,
        onBack = onBack,
        strip = ids.mapNotNull { id -> sessions.firstOrNull { it.id == id } },
        onSelect = { id -> ids.indexOf(id).takeIf { it >= 0 }?.let { scope.launch { pager.animateScrollToPage(it) } } },
        onCloseTab = ::close,
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
