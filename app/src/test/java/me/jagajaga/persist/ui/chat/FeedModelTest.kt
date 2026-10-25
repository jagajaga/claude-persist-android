package me.jagajaga.persist.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.jagajaga.persist.data.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedModelTest {
    private fun item(session: String, seq: Int, ts: Long, event: String) = FeedItem(
        sessionId = session,
        title = "title $session",
        event = Json.parseToJsonElement("""{"seq":$seq,"ts":$ts,"event":$event}""").jsonObject,
    )

    private fun text(session: String, seq: Int, ts: Long, t: String) =
        item(session, seq, ts, """{"type":"assistant_text","text":"$t"}""")

    private fun List<FeedEntry>.shape() = map {
        when (it) {
            is FeedEntry.Header -> "# ${it.title}"
            is FeedEntry.Item -> when (val i = it.item) {
                is ThreadItem.Assistant -> i.text
                is ThreadItem.Tool -> "tool ${i.name} ${if (i.done) "done" else "running"}"
                else -> i::class.simpleName!!
            }
        }
    }

    @Test
    fun `oldest first, with the chat's name above each run of its messages`() {
        val feed = listOf(
            text("b", 7, 300, "b two"), // newest first, as the store holds them
            text("a", 3, 200, "a two"),
            text("a", 2, 150, "a one"),
            text("b", 6, 100, "b one"),
        )
        assertEquals(
            listOf("# title b", "b one", "# title a", "a one", "a two", "# title b", "b two"),
            FeedModel.build(feed).shape(),
        )
    }

    /** Built per chat, so a tool card gets its result even when another chat spoke in between. */
    @Test
    fun `a tool card is paired with its result across interleaving`() {
        val feed = listOf(
            item("a", 1, 100, """{"type":"tool_use","toolUseId":"t1","toolName":"Bash","input":{"command":"ls"}}"""),
            text("b", 9, 150, "meanwhile"),
            item("a", 2, 200, """{"type":"tool_result","toolUseId":"t1","summary":"ok"}"""),
        )
        assertEquals(listOf("# title a", "tool Bash done", "# title b", "meanwhile"), FeedModel.build(feed).shape())
    }

    @Test
    fun `the live chat name wins over the one the event was recorded under`() {
        val entries = FeedModel.build(listOf(text("a", 1, 1, "x")), titles = mapOf("a" to "renamed"))
        assertEquals("renamed", (entries.first() as FeedEntry.Header).title)
    }

    @Test
    fun `an event that arrived twice is shown once, and every key is unique`() {
        val feed = listOf(text("a", 1, 1, "x"), text("a", 1, 1, "x"), text("b", 1, 2, "y"), text("a", 2, 3, "z"))
        val entries = FeedModel.build(feed)
        assertEquals(listOf("# title a", "x", "# title b", "y", "# title a", "z"), entries.shape())
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertTrue(FeedModel.build(emptyList()).isEmpty())
    }
}
