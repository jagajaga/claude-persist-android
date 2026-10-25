package me.jagajaga.persist.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.jagajaga.persist.data.SessionInfo
import me.jagajaga.persist.ui.list.ChatGrouping
import me.jagajaga.persist.ui.list.ChatOrder

/**
 * Every chat, newest activity first across all projects; or, at a tap,
 * grouped by project the way the editor's sidebar lists them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    sessions: List<SessionInfo>,
    onOpen: (SessionInfo) -> Unit,
    onNew: () -> Unit,
) {
    var grouping by rememberSaveable { mutableStateOf(ChatGrouping.Recent) }
    val recent = remember(sessions) { ChatOrder.recent(sessions) }
    val groups = remember(sessions) { ChatOrder.byProject(sessions) }
    Box(Modifier.fillMaxSize().background(Palette.page)) {
        if (sessions.isEmpty()) {
            Text(
                "No chats yet",
                style = Type.small,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "grouping") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Toggle("Recent", grouping == ChatGrouping.Recent) { grouping = ChatGrouping.Recent }
                    Toggle("By project", grouping == ChatGrouping.Projects) { grouping = ChatGrouping.Projects }
                }
            }
            if (grouping == ChatGrouping.Recent) {
                items(recent, key = { it.id }) { session ->
                    ChatRow(session, showFolder = true) { onOpen(session) }
                    HorizontalDivider(color = Palette.border, modifier = Modifier.padding(start = 16.dp))
                }
            } else for ((folder, list) in groups) {
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
                    ChatRow(session, showFolder = false) { onOpen(session) }
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
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.small.copy(color = if (on) Palette.surface else Palette.muted, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (on) Palette.accent else Palette.surface, RoundedCornerShape(50))
            .border(1.dp, if (on) Palette.accent else Palette.border, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun ChatRow(session: SessionInfo, showFolder: Boolean, onClick: () -> Unit) {
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
                listOfNotNull(
                    if (session.working) "working" else null,
                    if (showFolder) session.folder else null,
                    model,
                ).joinToString(" · "),
                style = Type.tiny,
                maxLines = 1,
            )
        }
        Text(ago(session.lastActivityAt), style = Type.tiny)
    }
}
