package me.jagajaga.persist.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.jagajaga.persist.data.FeedItem

/** One line of the feed: a chat's name where the conversation changes hands, or one of its items. */
sealed interface FeedEntry {
    val key: String

    data class Header(override val key: String, val sessionId: String, val title: String, val ts: Long) : FeedEntry
    data class Item(override val key: String, val sessionId: String, val item: ThreadItem) : FeedEntry
}

/**
 * The feed as one long chat across every agent.
 *
 * Each chat's events go through the same [ThreadModel] as its own screen, so a
 * tool card gets its result and a permission its answer exactly as it would
 * there. The items are then interleaved by time, oldest first like a chat, and
 * the chat's name goes above each run of its messages.
 */
object FeedModel {
    fun build(feed: List<FeedItem>, titles: Map<String, String> = emptyMap()): List<FeedEntry> {
        data class Placed(val ts: Long, val seq: Long, val sessionId: String, val item: ThreadItem)

        val placed = mutableListOf<Placed>()
        val fallbackTitle = HashMap<String, String>()
        for ((sessionId, items) in feed.groupBy { it.sessionId }) {
            fallbackTitle[sessionId] = items.first().title
            val events = items.map { it.event }.distinctBy { seqOf(it) }.sortedBy { seqOf(it) }
            val tsBySeq = events.associate { "e${seqOf(it)}" to tsOf(it) }
            for (item in ThreadModel.build(events, emptyList())) {
                val ts = tsBySeq[item.key] ?: continue
                placed += Placed(ts, item.key.removePrefix("e").toLongOrNull() ?: 0, sessionId, item)
            }
        }
        placed.sortWith(compareBy<Placed> { it.ts }.thenBy { it.sessionId }.thenBy { it.seq })

        val out = mutableListOf<FeedEntry>()
        var current: String? = null
        for (p in placed) {
            if (p.sessionId != current) {
                current = p.sessionId
                val title = titles[p.sessionId] ?: fallbackTitle[p.sessionId] ?: ""
                out += FeedEntry.Header("h:${p.sessionId}:${p.item.key}", p.sessionId, title, p.ts)
            }
            out += FeedEntry.Item("${p.sessionId}:${p.item.key}", p.sessionId, p.item)
        }
        return out
    }

    private fun seqOf(stored: JsonObject): Long = (stored["seq"] as? JsonPrimitive)?.longOrNull ?: -1
    private fun tsOf(stored: JsonObject): Long = (stored["ts"] as? JsonPrimitive)?.longOrNull ?: 0
}
