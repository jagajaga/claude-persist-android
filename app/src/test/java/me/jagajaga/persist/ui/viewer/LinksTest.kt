package me.jagajaga.persist.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test
    fun `web, server file and everything else go to their own places`() {
        assertEquals(LinkTarget.Web("https://example.com/a?b=1"), Links.target("https://example.com/a?b=1"))
        assertEquals(LinkTarget.ServerFile("/home/x/shot.png"), Links.target("/home/x/shot.png"))
        assertEquals(LinkTarget.ServerFile("/home/x/my shot.png"), Links.target("file:///home/x/my%20shot.png"))
        assertEquals(LinkTarget.External("mailto:a@b.c"), Links.target("mailto:a@b.c"))
    }

    @Test
    fun `a line number or anchor is not part of the file`() {
        assertEquals(LinkTarget.ServerFile("/w/app/Main.kt"), Links.target("/w/app/Main.kt:42:7"))
        assertEquals(LinkTarget.ServerFile("/w/README.md"), Links.target("/w/README.md#install"))
    }

    @Test
    fun `kinds by extension`() {
        assertEquals(FileKind.Image, Links.kind("/a/b.JPG"))
        assertEquals(FileKind.Video, Links.kind("/a/clip.webm"))
        assertEquals(FileKind.Audio, Links.kind("/a/voice.m4a"))
        assertEquals(FileKind.Pdf, Links.kind("/a/report.pdf"))
        assertEquals(FileKind.Text, Links.kind("/a/Main.kt"))
        assertEquals(FileKind.Text, Links.kind("/a/Makefile"))
        assertEquals(FileKind.Other, Links.kind("/a/archive.zip"))
    }

    @Test
    fun `paths and urls are found in running text, without the full stop`() {
        val s = Links.segments("Saved to /home/me/out/frame.png. See https://example.com/x, then /tmp/a.log:12 too")
        assertEquals(
            listOf("/home/me/out/frame.png", "https://example.com/x", "/tmp/a.log:12"),
            s.mapNotNull { it.second },
        )
        assertEquals("Saved to /home/me/out/frame.png. See https://example.com/x, then /tmp/a.log:12 too", s.joinToString("") { it.first })
    }

    @Test
    fun `things that only look a little like paths are left alone`() {
        assertEquals(emptyList<String>(), Links.segments("and/or, 1/2, a single /root, ~/x/y, src/main.kt").mapNotNull { it.second })
    }

    @Test
    fun `inline code that is just a path is a link`() {
        assertEquals("/w/app/build.gradle.kts", Links.wholeLink("/w/app/build.gradle.kts"))
        assertNull(Links.wholeLink("npm run build"))
    }

    @Test
    fun `media a message mentions, each once, in order, nothing else`() {
        val text = """
            Saved the frame to /tmp/art/full3.png and the clip to `/tmp/art/h.mp4`.
            Compare with https://example.com/ref.jpg?size=large and /tmp/art/full3.png again.
            The log is /tmp/art/run.log and the page is https://example.com/docs.
        """.trimIndent()
        assertEquals(
            listOf("/tmp/art/full3.png", "/tmp/art/h.mp4", "https://example.com/ref.jpg?size=large"),
            Links.mediaIn(text),
        )
    }

    @Test
    fun `a long listing of screenshots gives a few previews, not all of them`() {
        val text = (1..50).joinToString("\n") { "/shots/s$it.png" }
        assertEquals(6, Links.mediaIn(text).size)
    }
}
