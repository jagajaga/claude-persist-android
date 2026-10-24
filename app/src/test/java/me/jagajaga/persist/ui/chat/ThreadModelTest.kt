package me.jagajaga.persist.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.jagajaga.persist.data.OutgoingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadModelTest {
    private var seq = 0
    private fun ev(json: String): JsonObject =
        Json.parseToJsonElement("""{"seq":${seq++},"ts":1700000000000,"event":$json}""").jsonObject

    @Test
    fun `a tool call and its result become one card`() {
        val items = ThreadModel.build(
            listOf(
                ev("""{"type":"tool_use","toolUseId":"t1","toolName":"Bash","input":{"command":"ls -la"}}"""),
                ev("""{"type":"tool_result","toolUseId":"t1","summary":"total 8","isError":false}"""),
            ),
            emptyList(),
        )
        val tool = items.single() as ThreadItem.Tool
        assertEquals("Bash", tool.name)
        assertEquals("ls -la", tool.label)
        assertEquals("total 8", tool.result)
        assertTrue(tool.done)
    }

    @Test
    fun `a tool still running has no result yet`() {
        val tool = ThreadModel.build(listOf(ev("""{"type":"tool_use","toolUseId":"t1","toolName":"Read","input":{"file_path":"/a/b.kt"}}""")), emptyList())
            .single() as ThreadItem.Tool
        assertEquals("/a/b.kt", tool.label)
        assertEquals(false, tool.done)
    }

    /** An open request needs buttons; an answered one must not offer them again. */
    @Test
    fun `a permission request is open until resolved`() {
        val open = ThreadModel.build(listOf(ev("""{"type":"permission_request","requestId":"r1","toolName":"Bash","input":{"command":"rm x"}}""")), emptyList())
        assertEquals(false, (open.single() as ThreadItem.Permission).resolved)
        val resolved = ThreadModel.build(
            listOf(
                ev("""{"type":"permission_request","requestId":"r2","toolName":"Bash","input":{"command":"rm x"}}"""),
                ev("""{"type":"permission_resolved","requestId":"r2","allowed":true}"""),
            ),
            emptyList(),
        )
        val p = resolved.single() as ThreadItem.Permission
        assertTrue(p.resolved)
        assertEquals(true, p.allowed)
    }

    @Test
    fun `questions carry their options, and their answers once given`() {
        val items = ThreadModel.build(
            listOf(
                ev("""{"type":"question_request","requestId":"q1","questions":[{"question":"Which?","header":"Pick","options":[{"label":"A","description":"first"},{"label":"B"}]}]}"""),
                ev("""{"type":"permission_resolved","requestId":"q1","allowed":true,"answers":{"Which?":"B"}}"""),
            ),
            emptyList(),
        )
        val q = items.single() as ThreadItem.Question
        assertEquals(listOf("A", "B"), q.questions.single().options.map { it.first })
        assertTrue(q.resolved)
        assertEquals("B", q.answers["Which?"])
    }

    /** Written on the phone and not yet confirmed: shown in place, as sending. */
    @Test
    fun `queued messages show as sending until the server has them`() {
        val queued = OutgoingMessage("phone-1", "s1", "hello", 0)
        val before = ThreadModel.build(emptyList(), listOf(queued))
        assertTrue((before.single() as ThreadItem.User).sending)
        val after = ThreadModel.build(
            listOf(ev("""{"type":"user_message","text":"hello","clientMessageId":"phone-1"}""")),
            listOf(queued),
        )
        assertEquals("shown once, as delivered", 1, after.size)
        assertEquals(false, (after.single() as ThreadItem.User).sending)
    }

    @Test
    fun `turn footer reads like the panel`() {
        val footer = ThreadModel.build(listOf(ev("""{"type":"result","summary":"done","durationMs":8400,"turnTokens":1234}""")), emptyList())
            .single() as ThreadItem.Footer
        assertEquals("01:18 · 8.4s · 1.2k tokens", ThreadModel.footerText(footer) { "01:18" })
    }

    @Test
    fun `error statuses are shown, routine ones without detail are not`() {
        val items = ThreadModel.build(
            listOf(
                ev("""{"type":"status","status":"running"}"""),
                ev("""{"type":"status","status":"error","detail":"Login expired"}"""),
            ),
            emptyList(),
        )
        val notice = items.single() as ThreadItem.Notice
        assertTrue(notice.error)
        assertEquals("Login expired", notice.text)
    }

    @Test
    fun `tool labels follow the panel's choice of field`() {
        fun label(name: String, input: String) = ThreadModel.toolLabel(name, Json.parseToJsonElement(input))
        assertEquals("Map the app composer UI", label("Agent", """{"description":"Map the app composer UI","prompt":"..."}"""))
        assertEquals("needle", label("Grep", """{"pattern":"needle","path":"/x"}"""))
        assertEquals("one two", label("Bash", """{"command":"one\ntwo"}"""))
    }

    @Test
    fun `the prompt bar shows the newest message`() {
        val items = ThreadModel.build(
            listOf(ev("""{"type":"user_message","text":"first"}"""), ev("""{"type":"user_message","text":"second"}""")),
            emptyList(),
        )
        assertEquals("second", ThreadModel.lastPrompt(items))
    }
}
