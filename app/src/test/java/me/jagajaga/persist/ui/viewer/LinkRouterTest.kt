package me.jagajaga.persist.ui.viewer

import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.LinkAnnotation
import me.jagajaga.persist.ui.markdown.Markdown
import me.jagajaga.persist.ui.markdown.annotate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkRouterTest {
    private val opened = mutableListOf<String>()
    private val systemOpened = mutableListOf<String>()

    private fun install() {
        LinkRouter.handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        LinkRouter.fallback = { systemOpened += it }
    }

    @After fun reset() { LinkRouter.handler = null; LinkRouter.fallback = null }

    /** Tap every link in a rendered paragraph, the way a finger would trigger it. */
    private fun tapAll(markdown: String) {
        val block = Markdown.parse(markdown).single() as me.jagajaga.persist.ui.markdown.Block.Paragraph
        val text = annotate(block.inlines)
        for (range in text.getLinkAnnotations(0, text.length)) {
            val link = range.item
            if (link is LinkAnnotation.Clickable) link.linkInteractionListener?.onClick(link)
            else error("a link not routed through the app: $link")
        }
    }

    @Test
    fun `every kind of link in a message opens through the app, never the system browser`() {
        install()
        tapAll("See [the page](https://app.blooper.ai/p/1), https://example.com/x, /tmp/a.png and `/w/main.kt`.")
        assertEquals(listOf("https://app.blooper.ai/p/1", "https://example.com/x", "/tmp/a.png", "/w/main.kt"), opened)
        assertEquals(emptyList<String>(), systemOpened)
    }

    @Test
    fun `only with no app handler at all does a link go to the system`() {
        LinkRouter.fallback = { systemOpened += it }
        LinkRouter.open("https://example.com")
        assertEquals(listOf("https://example.com"), systemOpened)
    }
}
