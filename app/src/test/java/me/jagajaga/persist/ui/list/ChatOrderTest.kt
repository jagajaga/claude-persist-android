package me.jagajaga.persist.ui.list

import me.jagajaga.persist.data.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatOrderTest {
    private fun s(id: String, cwd: String, at: Long, status: String = "idle") =
        SessionInfo(id = id, title = id, cwd = cwd, status = status, lastActivityAt = at)

    private val chats = listOf(
        s("old-blp", "/w/blooper", 10),
        s("new-cp", "/w/persist", 50),
        s("mid-blp", "/w/blooper", 40, status = "running"),
        s("newest-blp", "/w/blooper", 60),
    )

    @Test
    fun `recent is newest first across every project`() {
        assertEquals(listOf("newest-blp", "new-cp", "mid-blp", "old-blp"), ChatOrder.recent(chats).map { it.id })
    }

    @Test
    fun `by project puts the project with the newest chat first, newest first inside`() {
        val groups = ChatOrder.byProject(chats)
        assertEquals(listOf("blooper", "persist"), groups.map { it.first })
        assertEquals(listOf("newest-blp", "mid-blp", "old-blp"), groups[0].second.map { it.id })
    }

    @Test
    fun `active is only the working chats, newest first`() {
        val more = chats + s("busy-cp", "/w/persist", 70, status = "running")
        assertEquals(listOf("busy-cp", "mid-blp"), ChatOrder.active(more).map { it.id })
    }
}
