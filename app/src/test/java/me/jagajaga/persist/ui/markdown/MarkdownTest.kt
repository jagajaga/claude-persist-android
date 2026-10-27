package me.jagajaga.persist.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun para(md: String) = (Markdown.parse(md).single() as Block.Paragraph).inlines

    @Test
    fun `headings, paragraphs and rules`() {
        val blocks = Markdown.parse("# Title\n\nSome text\nthat wraps.\n\n---\n## Next")
        assertEquals(Block.Heading(1, listOf(Inline.Text("Title"))), blocks[0])
        assertEquals("Some text that wraps.", Markdown.plain((blocks[1] as Block.Paragraph).inlines))
        assertEquals(Block.Rule, blocks[2])
        assertEquals(2, (blocks[3] as Block.Heading).level)
    }

    @Test
    fun `bold, italic, code and links`() {
        val inlines = para("A **bold** and *italic* with `code` and [a link](https://x.y).")
        assertTrue(inlines.contains(Inline.Bold(listOf(Inline.Text("bold")))))
        assertTrue(inlines.contains(Inline.Italic(listOf(Inline.Text("italic")))))
        assertTrue(inlines.contains(Inline.Code("code")))
        assertTrue(inlines.contains(Inline.Link(listOf(Inline.Text("a link")), "https://x.y")))
    }

    /** Claude writes identifiers all the time; they must not turn into italics. */
    @Test
    fun `snake_case is not italic`() {
        assertEquals("use snake_case_names here", Markdown.plain(para("use snake_case_names here")))
        assertTrue(para("use snake_case_names here").none { it is Inline.Italic })
    }

    @Test
    fun `code spans are literal`() {
        assertEquals(listOf(Inline.Code("**not bold**")), para("`**not bold**`"))
    }

    @Test
    fun `fenced code keeps its lines and language`() {
        val code = Markdown.parse("```kotlin\nval x = 1\n\nval y = 2\n```").single() as Block.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\n\nval y = 2", code.text)
    }

    /** A reply still streaming has an open fence; it must render, not vanish. */
    @Test
    fun `an unclosed fence runs to the end`() {
        val code = Markdown.parse("Here:\n```\nline one\nline two").last() as Block.Code
        assertEquals("line one\nline two", code.text)
    }

    @Test
    fun `lists, numbered and nested`() {
        val items = Markdown.parse("1. first\n2. second\n  - nested\n- loose").filterIsInstance<Block.ListItem>()
        assertEquals(listOf(true, true, false, false), items.map { it.ordered })
        assertEquals(listOf(1, 2, 0, 0), items.map { it.number })
        assertEquals(listOf(0, 0, 1, 0), items.map { it.depth })
    }

    @Test
    fun `tables`() {
        val table = Markdown.parse("| A | B |\n|---|---|\n| 1 | **2** |\n| 3 | 4 |").single() as Block.Table
        assertEquals(listOf("A", "B"), table.header.map { Markdown.plain(it) })
        assertEquals(2, table.rows.size)
        assertEquals(Inline.Bold(listOf(Inline.Text("2"))), table.rows[0][1].single())
    }

    @Test
    fun `quotes`() {
        val quote = Markdown.parse("> quoted\n> more").single() as Block.Quote
        assertEquals("quoted more", Markdown.plain(quote.inlines))
    }

    @Test
    fun `nested emphasis`() {
        val inlines = para("**bold with `code` inside**")
        val bold = inlines.single() as Inline.Bold
        assertTrue(bold.children.contains(Inline.Code("code")))
    }

    @Test
    fun `an unmatched marker is just text`() {
        assertEquals("2 * 3 = 6 and a lone ` tick", Markdown.plain(para("2 * 3 = 6 and a lone ` tick")))
    }

    @Test
    fun `pictures are parsed, including the html tags READMEs use`() {
        val blocks = Markdown.parse("Intro ![shot](img/a.png) here\n\n<img src=\"docs/b.jpg\" width=\"300\">\n\n<video src=\"clip.mp4\"></video>")
        assertEquals(listOf("img/a.png", "docs/b.jpg", "clip.mp4"), blocks.flatMap { Markdown.imagesIn(it) }.map { it.url })
    }

    @Test
    fun `paths in a file resolve against its own folder`() {
        assertEquals("/w/repo/img/a.png", Markdown.resolve("img/a.png", "/w/repo"))
        assertEquals("/w/repo/a.png", Markdown.resolve("./a.png", "/w/repo"))
        assertEquals("/w/b.md", Markdown.resolve("../b.md#setup", "/w/repo"))
        assertEquals("/abs/x.png", Markdown.resolve("/abs/x.png", "/w/repo"))
        assertEquals("https://x.org/a.png", Markdown.resolve("https://x.org/a.png", "/w/repo"))
        assertEquals("#anchor", Markdown.resolve("#anchor", "/w/repo"))
        assertEquals("img/a.png", Markdown.resolve("img/a.png", null))
    }
}
