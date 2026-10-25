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

    /** A send that dies after its upload must not upload again, even across a restart. */
    @Test
    fun `an uploaded file stays uploaded across a restart`() {
        val file = tmpFile()
        val local = File(file.parentFile, "copy.pdf").apply { writeText("pdf") }
        val box = Outbox(file)
        val m = box.add("s1", "see attached", files = listOf(OutgoingFile("report.pdf", local.path, 3, "u1")))
        box.fileUploaded(m.clientMessageId, "u1", "/srv/uploads/s1/u1-report.pdf")
        val reopened = Outbox(file).pending().single()
        assertEquals("/srv/uploads/s1/u1-report.pdf", reopened.files.single().uploadedPath)
    }

    @Test
    fun `a confirmed message's file copies are cleaned up`() {
        val file = tmpFile()
        val local = File(file.parentFile, "copy.bin").apply { writeText("x") }
        val box = Outbox(file)
        val m = box.add("s1", "", files = listOf(OutgoingFile("a.bin", local.path, 1, "u1")))
        box.confirm(m.clientMessageId)
        assertEquals(false, local.exists())
    }

    /** Exactly what the version before this one wrote: photos inline, no upload ids, no failed field. */
    @Test
    fun `a queue written by the previous version loads, photos and all`() {
        val file = tmpFile()
        file.writeText("""[{"clientMessageId":"phone-1","sessionId":"s1","text":"why so far away?","createdAt":5,
            "images":[{"name":"195106.jpg","mediaType":"image/jpeg","base64":"AAAA"}],"attempts":7},
            {"clientMessageId":"phone-2","sessionId":"s1","text":"status","createdAt":6}]""")
        val box = Outbox(file)
        assertEquals(listOf("phone-1", "phone-2"), box.pending().map { it.clientMessageId })
        assertEquals("AAAA", box.pending()[0].images.single().base64)
    }

    /** One entry it can't read must not wipe the others -- before, the next save emptied the queue. */
    @Test
    fun `an unreadable entry is set aside and the rest survive a save`() {
        val file = tmpFile()
        file.writeText("""[{"clientMessageId":"phone-ok","sessionId":"s1","text":"keep me","createdAt":1},
            {"clientMessageId":12,"sessionId":null}]""")
        val box = Outbox(file)
        box.add("s1", "new one")
        assertEquals(listOf("keep me", "new one"), Outbox(file).pending().map { it.text })
        assertEquals(1, file.parentFile!!.listFiles()!!.count { it.name.startsWith("outbox.json.unreadable-") })
    }

    @Test
    fun `a file that is not a list at all is kept, not overwritten`() {
        val file = tmpFile()
        file.writeText("{garbage")
        Outbox(file).add("s1", "after")
        assertEquals("{garbage", file.parentFile!!.listFiles()!!.first { it.name.startsWith("outbox.json.unreadable-") }.readText())
    }

    @Test
    fun `a refusal is kept with its reason, and retry clears it`() {
        val file = tmpFile()
        val box = Outbox(file)
        val m = box.add("s1", "hi")
        box.fail(m.clientMessageId, "unknown session")
        assertEquals("unknown session", Outbox(file).pending().single().failed)
        box.retry(m.clientMessageId)
        assertEquals(null, Outbox(file).pending().single().failed)
    }

    @Test
    fun `an uploaded photo stops holding its bytes`() {
        val file = tmpFile()
        val box = Outbox(file)
        val m = box.add("s1", "pic", images = listOf(OutgoingImage("a.jpg", "image/jpeg", "AAAA", uploadId = "u1")))
        box.imageUploaded(m.clientMessageId, "u1", "/srv/uploads/s1/u1-a.jpg")
        val img = Outbox(file).pending().single().images.single()
        assertEquals("/srv/uploads/s1/u1-a.jpg", img.uploadedPath)
        assertEquals("", img.base64)
    }
}
