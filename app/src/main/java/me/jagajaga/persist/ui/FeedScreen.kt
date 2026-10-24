package me.jagajaga.persist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.jagajaga.persist.data.FeedItem
import me.jagajaga.persist.data.Store

/** The newest messages across every chat, live: one place to see what is happening. */
@Composable
fun FeedScreen(store: Store, items: List<FeedItem>, onOpen: (String) -> Unit) {
    DisposableEffect(Unit) {
        store.wantFeed(true)
        onDispose { store.wantFeed(false) }
    }
    Box(Modifier.fillMaxSize().background(Palette.page)) {
        if (items.isEmpty()) Text("Nothing yet", style = Type.small, modifier = Modifier.align(Alignment.Center))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { "${it.sessionId}:${(it.event["seq"] as? JsonPrimitive)?.content}" }) { item ->
                FeedCard(item) { onOpen(item.sessionId) }
            }
        }
    }
}

@Composable
private fun FeedCard(item: FeedItem, onClick: () -> Unit) {
    val event = item.event["event"] as? JsonObject
    val type = (event?.get("type") as? JsonPrimitive)?.contentOrNull
    val ts = (item.event["ts"] as? JsonPrimitive)?.longOrNull ?: 0
    val (who, text) = when (type) {
        "user_message" -> "You" to ((event?.get("text") as? JsonPrimitive)?.contentOrNull ?: "")
        "assistant_text" -> "Claude" to ((event?.get("text") as? JsonPrimitive)?.contentOrNull ?: "")
        "permission_request" -> "Needs you" to "Allow ${(event?.get("toolName") as? JsonPrimitive)?.contentOrNull ?: "a tool"}?"
        "question_request" -> "Needs you" to "Claude asked a question"
        "result" -> "Done" to ((event?.get("summary") as? JsonPrimitive)?.contentOrNull ?: "Turn finished")
        else -> "" to ""
    }
    val needsYou = type == "permission_request" || type == "question_request"
    Column(
        Modifier
            .fillMaxWidth()
            .border(if (needsYou) 1.5.dp else 1.dp, if (needsYou) Palette.warn else Palette.border, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, style = Type.tiny.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(ago(ts), style = Type.tiny)
        }
        Text(
            buildString {
                if (who.isNotEmpty()) append("$who: ")
                append(text.replace('\n', ' '))
            },
            style = Type.body,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
