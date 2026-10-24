package me.jagajaga.persist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OutboxTest {
    private fun tmpFile(): File = File(Files.createTempDirectory("outbox").toFile(), "outbox.json")

    /** The point of it: a message survives the app being killed before it was sent. */
    @Test
    fun `a queued message survives a restart`() {
        val file = tmpFile()
        val first = Outbox(file)
        val m = first.add("s1", "hello")
        val reopened = Outbox(file)
        assertEquals(listOf(m.clientMessageId), reopened.pending().map { it.clientMessageId })
        assertEquals("hello", reopened.pending().single().text)
    }

    @Test
    fun `confirmed messages leave, and stay gone after a restart`() {
        val file = tmpFile()
        val box = Outbox(file)
        val a = box.add("s1", "a")
        val b = box.add("s1", "b")
        box.confirm(a.clientMessageId)
        assertEquals(listOf(b.clientMessageId), Outbox(file).pending().map { it.clientMessageId })
    }

    /** The id is what makes a resend safe; it must be stable and unique. */
    @Test
    fun `every message gets its own id, kept across resends`() {
        val file = tmpFile()
        val box = Outbox(file)
        val a = box.add("s1", "same text")
        val b = box.add("s1", "same text")
        assertNotEquals(a.clientMessageId, b.clientMessageId)
        box.attempted(a.clientMessageId)
        val after = Outbox(file).pending().first { it.clientMessageId == a.clientMessageId }
        assertEquals(1, after.attempts)
        assertTrue(after.clientMessageId.startsWith("phone-"))
    }

    @Test
    fun `messages go out in the order they were written`() {
        val box = Outbox(tmpFile())
        val ids = (1..5).map { box.add("s1", "m$it").clientMessageId }
        assertEquals(ids, box.pending().map { it.clientMessageId })
    }

    @Test
    fun `pending is reported per chat`() {
        val box = Outbox(tmpFile())
        box.add("s1", "x")
        box.add("s2", "y")
        assertEquals(listOf("y"), box.pendingFor("s2").map { it.text })
    }

    /** A half-written file from a crash must not take the queue with it. */
    @Test
    fun `a corrupt file starts an empty queue instead of crashing`() {
        val file = tmpFile()
        file.parentFile!!.mkdirs()
        file.writeText("{ this is not json")
        assertTrue(Outbox(file).pending().isEmpty())
    }
}
