package me.jagajaga.persist.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLogTest {
    private fun ev(seq: Int, text: String = "e$seq") =
        JsonObject(mapOf("seq" to JsonPrimitive(seq), "text" to JsonPrimitive(text)))

    private fun SessionLog.seqs() = events.map { it["seq"]!!.jsonPrimitive.content.toInt() }

    @Test
    fun `events are kept in sequence order whatever order they arrive in`() {
        val log = SessionLog()
        log.merge(listOf(ev(3), ev(1), ev(2)))
        assertEquals(listOf(1, 2, 3), log.seqs())
    }

    /**
     * The everyday bad-network case: an event arrives live, the link drops, and
     * the replay after reconnecting sends it again.
     */
    @Test
    fun `an event that arrives twice is kept once`() {
        val log = SessionLog()
        log.merge(listOf(ev(5)))
        val changed = log.merge(listOf(ev(4), ev(5), ev(6)))
        assertTrue(changed)
        assertEquals(listOf(4, 5, 6), log.seqs())
        assertFalse("nothing new", log.merge(listOf(ev(5), ev(6))))
    }

    @Test
    fun `resume asks for the first number not yet held`() {
        val log = SessionLog()
        assertEquals(0, log.resumeFrom)
        log.merge(listOf(ev(0), ev(1), ev(7)))
        assertEquals(8, log.resumeFrom)
    }

    @Test
    fun `the first copy of an event is the one kept`() {
        val log = SessionLog()
        log.merge(listOf(ev(1, "original")))
        log.merge(listOf(ev(1, "replayed")))
        assertEquals("original", log.events.single()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `events without a sequence number are ignored`() {
        val log = SessionLog()
        log.merge(listOf(JsonObject(mapOf("text" to JsonPrimitive("no seq")))))
        assertTrue(log.events.isEmpty())
    }

    @Test
    fun `trimming keeps the newest`() {
        val log = SessionLog((1..10).map { ev(it) })
        log.trimTo(3)
        assertEquals(listOf(8, 9, 10), log.seqs())
        assertEquals(8, log.firstSeq)
    }
}
