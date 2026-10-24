package me.jagajaga.persist.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.jagajaga.persist.data.SessionInfo

/** Every chat, grouped by folder, the way the editor's sidebar lists them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    sessions: List<SessionInfo>,
    onOpen: (SessionInfo) -> Unit,
    onNew: () -> Unit,
) {
    // Groups ordered by their most recent chat, so the work in hand is on top.
    val groups = remember(sessions) {
        sessions.groupBy { it.folder }
            .entries
            .sortedByDescending { (_, list) -> list.maxOf { it.lastActivityAt } }
    }
    Box(Modifier.fillMaxSize().background(Palette.page)) {
        if (sessions.isEmpty()) {
            Text(
                "No chats yet",
                style = Type.small,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for ((folder, list) in groups) {
                stickyHeader(key = "h:$folder") {
                    Text(
                        folder,
                        style = Type.tiny.copy(fontWeight = FontWeight.SemiBold, color = Palette.muted),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Palette.page)
                            .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                    )
                }
                items(list, key = { it.id }) { session ->
                    ChatRow(session) { onOpen(session) }
                    HorizontalDivider(color = Palette.border, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
        FloatingActionButton(
            onClick = onNew,
            containerColor = Palette.accent,
            contentColor = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
    }
}

@Composable
private fun ChatRow(session: SessionInfo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(working = session.working, error = session.status == "error")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(session.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val model = session.activeModel ?: session.model
            Text(
                listOfNotNull(if (session.working) "working" else null, model).joinToString(" · "),
                style = Type.tiny,
                maxLines = 1,
            )
        }
        Text(ago(session.lastActivityAt), style = Type.tiny)
    }
}
