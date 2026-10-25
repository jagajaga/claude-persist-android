package me.jagajaga.persist.notify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifyRulesTest {
    private fun ev(json: String) = Json.parseToJsonElement("""{"seq":1,"ts":1,"event":$json}""") as JsonObject

    @Test
    fun `a permission is loud and says what it wants to run`() {
        val n = NotifyRules.notice("blp|Jean", ev("""{"type":"permission_request","requestId":"r","toolName":"Bash","input":{"command":"rm -rf build"}}"""))!!
        assertEquals(NotifyRules.ATTENTION, n.channel)
        assertEquals("blp|Jean", n.title)
        assertEquals("Allow Bash? rm -rf build", n.text)
    }

    @Test
    fun `a question shows the question`() {
        val n = NotifyRules.notice("c", ev("""{"type":"question_request","requestId":"r","questions":[{"question":"Ship it?","header":"Go","options":[]}]}"""))!!
        assertEquals(NotifyRules.ATTENTION, n.channel)
        assertEquals("Ship it?", n.text)
    }

    @Test
    fun `a finished turn is quiet and gives the first line of its summary`() {
        val n = NotifyRules.notice("c", ev("""{"type":"result","summary":"\n\nAll four PRs merged.\nDetails below."}"""))!!
        assertEquals(NotifyRules.DONE, n.channel)
        assertEquals("All four PRs merged.", n.text)
        assertEquals("Finished", NotifyRules.notice("c", ev("""{"type":"result"}"""))!!.text)
    }

    @Test
    fun `everything else stays silent`() {
        for (type in listOf("assistant_text", "tool_use", "tool_result", "user_message", "status")) {
            assertNull(type, NotifyRules.notice("c", ev("""{"type":"$type","text":"x"}""")))
        }
    }
}
