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

    @Test
    fun `an uploaded file shows the name that was picked`() {
        assertEquals("report.pdf", attachmentName("/home/me/.claude-persist/uploads/s1/0f8e2b1a-1c2d-4e5f-8a9b-0123456789ab-report.pdf"))
        assertEquals("notes.txt", attachmentName("/w/project/notes.txt"))
    }

    @Test
    fun `the context ring reads the newest turn that reported it`() {
        val events = listOf(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"seq":1,"event":{"type":"result","contextTokens":100000,"contextWindow":1000000}}""") as JsonObject,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"seq":2,"event":{"type":"result","contextTokens":250000,"contextWindow":1000000}}""") as JsonObject,
            kotlinx.serialization.json.Json.parseToJsonElement("""{"seq":3,"event":{"type":"result","durationMs":5}}""") as JsonObject,
        )
        assertEquals(0.25, ThreadModel.contextFill(events)!!, 1e-9)
        assertEquals(null, ThreadModel.contextFill(emptyList()))
    }

    private fun obj(json: String) = kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `an edit is shown as the lines it removed and the lines it added`() {
        val diff = ThreadModel.diffOf("Edit", obj("""{"file_path":"/a.kt","old_string":"val x = 1\nval y = 2","new_string":"val x = 3"}"""))!!
        assertEquals(listOf('-' to "val x = 1", '-' to "val y = 2", '+' to "val x = 3"), diff.map { it.sign to it.text })
        assertEquals(listOf('+' to "hello"), ThreadModel.diffOf("Write", obj("""{"file_path":"/n.txt","content":"hello"}"""))!!.map { it.sign to it.text })
        assertEquals(null, ThreadModel.diffOf("Bash", obj("""{"command":"ls"}""")))
    }

    @Test
    fun `todos come out as a checklist with their state`() {
        val todos = ThreadModel.todosOf("TodoWrite", obj("""{"todos":[{"content":"Build","status":"completed"},{"content":"Test","status":"in_progress","activeForm":"Testing"}]}"""))!!
        assertEquals(listOf("completed", "in_progress"), todos.map { it.status })
        assertEquals("Testing", todos[1].activeForm)
    }

    @Test
    fun `what is waiting on you, and every prompt newest first`() {
        val events = listOf(
            obj("""{"seq":1,"ts":10,"event":{"type":"user_message","text":"first"}}"""),
            obj("""{"seq":2,"ts":20,"event":{"type":"permission_request","requestId":"r1","toolName":"Bash","input":{}}}"""),
            obj("""{"seq":3,"ts":30,"event":{"type":"permission_resolved","requestId":"r1","allowed":true}}"""),
            obj("""{"seq":4,"ts":40,"event":{"type":"user_message","text":"second"}}"""),
            obj("""{"seq":5,"ts":50,"event":{"type":"permission_request","requestId":"r2","toolName":"Write","input":{}}}"""),
        )
        val items = ThreadModel.build(events, emptyList())
        assertEquals(listOf("Write"), ThreadModel.waiting(items).map { (it.second as ThreadItem.Permission).toolName })
        assertEquals(listOf("second", "first"), ThreadModel.prompts(items).map { it.second })
        assertEquals(40L, ThreadModel.turnStart(events))
    }

    @Test
    fun `the gallery is every picture and video in the chat, in order, once`() {
        val events = listOf(
            obj("""{"seq":1,"event":{"type":"user_message","text":"look","attachments":[{"kind":"image","label":"a.png","path":"/up/a.png"}]}}"""),
            obj("""{"seq":2,"event":{"type":"tool_use","toolUseId":"t","toolName":"Read","input":{"file_path":"/w/b.jpg"}}}"""),
            obj("""{"seq":3,"event":{"type":"tool_result","toolUseId":"t","summary":"ok"}}"""),
            obj("""{"seq":4,"event":{"type":"assistant_text","text":"Cut /w/c.mp4 from /w/b.jpg; log in /w/run.log"}}"""),
        )
        assertEquals(listOf("/up/a.png", "/w/b.jpg", "/w/c.mp4"), ThreadModel.media(ThreadModel.build(events, emptyList())))
    }

    @Test
    fun `thinking is its own item before the answer, and a signature-only block is not shown`() {
        val items = ThreadModel.build(
            listOf(
                ev("""{"type":"thinking","text":"Counting the solutions."}"""),
                ev("""{"type":"thinking","text":"   "}"""),
                ev("""{"type":"assistant_text","text":"54"}"""),
            ),
            emptyList(),
        )
        assertEquals(listOf("Thinking", "Assistant"), items.map { it::class.simpleName })
        assertEquals("Counting the solutions.", (items[0] as ThreadItem.Thinking).text)
    }

    @Test
    fun `only the newest thinking is shown`() {
        val items = ThreadModel.build(
            listOf(
                ev("""{"type":"thinking","text":"First thought."}"""),
                ev("""{"type":"assistant_text","text":"answer"}"""),
                ev("""{"type":"thinking","text":"Second thought."}"""),
                ev("""{"type":"assistant_text","text":"more"}"""),
            ),
            emptyList(),
        )
        assertEquals(listOf("Second thought."), items.filterIsInstance<ThreadItem.Thinking>().map { it.text })
        assertEquals(3, items.size)
    }
}
