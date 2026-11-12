package me.jagajaga.persist.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.MoreVert
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
    viewed: Map<String, Long> = emptyMap(),
    usage: String? = null,
    onRename: (SessionInfo, String) -> Unit = { _, _ -> },
    onDelete: (SessionInfo) -> Unit = {},
    onNewHere: (SessionInfo) -> Unit = {},
    onFork: (SessionInfo) -> Unit = {},
) {
    var menuFor by remember { mutableStateOf<SessionInfo?>(null) }
    var renaming by remember { mutableStateOf<SessionInfo?>(null) }
    var deleting by remember { mutableStateOf<SessionInfo?>(null) }
    // New activity since the chat was last on screen here.
    fun unread(s: SessionInfo) = s.lastActivityAt > (viewed[s.id] ?: 0L) && viewed.isNotEmpty()
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Toggle("Recent", grouping == ChatGrouping.Recent) { grouping = ChatGrouping.Recent }
                    Toggle("By project", grouping == ChatGrouping.Projects) { grouping = ChatGrouping.Projects }
                    Spacer(Modifier.weight(1f))
                    usage?.let { Text("✦ $it", style = Type.tiny) }
                }
            }
            if (grouping == ChatGrouping.Recent) {
                items(recent, key = { it.id }) { session ->
                    ChatRow(session, showFolder = true, unread = unread(session), onMenu = { menuFor = session }) { onOpen(session) }
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
                    ChatRow(session, showFolder = false, unread = unread(session), onMenu = { menuFor = session }) { onOpen(session) }
                    HorizontalDivider(color = Palette.border, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
        menuFor?.let { target -> ChatMenu(target, onDismiss = { menuFor = null }, onNewHere = { onNewHere(target) }, onFork = { onFork(target) }, onRename = { renaming = target }, onDelete = { deleting = target }) }
        renaming?.let { target ->
            var title by remember(target.id) { mutableStateOf(target.title) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename chat") },
                text = { androidx.compose.material3.OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
                confirmButton = {
                    androidx.compose.material3.TextButton(enabled = title.isNotBlank(), onClick = { onRename(target, title.trim()); renaming = null }) { Text("Rename") }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel") } },
            )
        }
        deleting?.let { target ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleting = null },
                title = { Text("Delete this chat?") },
                text = { Text("\"${target.title}\" and its whole history are removed from the server. This can't be undone.", style = Type.body) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { onDelete(target); deleting = null }) { Text("Delete", color = Palette.error) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel") } },
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(session: SessionInfo, showFolder: Boolean, unread: Boolean, onMenu: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.surface)
            .combinedClickable(onClick = onClick, onLongClick = onMenu)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
        Column(horizontalAlignment = Alignment.End) {
            Text(ago(session.lastActivityAt), style = Type.tiny)
            if (unread) Text("●", style = Type.small.copy(color = Palette.error))
        }
        // The chat's menu, in plain sight: long-press opens it too, but nobody finds that.
        androidx.compose.material3.IconButton(onClick = onMenu) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Chat menu", tint = Palette.muted)
        }
    }
}

/** What can be done with a chat: another in its folder, a fork of it, rename, delete. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatMenu(
    session: SessionInfo,
    onDismiss: () -> Unit,
    onNewHere: () -> Unit,
    onFork: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.surface) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(session.title, style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Text(session.cwd, style = Type.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp))
            MenuItem("New chat in this folder", "Starts fresh in ${session.cwd.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }}") { onDismiss(); onNewHere() }
            MenuItem("Fork", "A new chat that carries on from here; this one stays as it is") { onDismiss(); onFork() }
            MenuItem("Rename", null) { onDismiss(); onRename() }
            MenuItem("Delete", null, danger = true) { onDismiss(); onDelete() }
        }
    }
}

@Composable
private fun MenuItem(title: String, detail: String?, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = Type.body.copy(color = if (danger) Palette.error else Palette.text))
        detail?.let { Text(it, style = Type.tiny, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}
