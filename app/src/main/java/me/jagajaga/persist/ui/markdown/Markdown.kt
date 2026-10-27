package me.jagajaga.persist.ui.markdown

/**
 * The markdown Claude writes, parsed into blocks a phone can lay out.
 *
 * Deliberately a subset, and deliberately ours: headings, paragraphs, bullet
 * and numbered lists (nested by indent), fenced code, quotes, rules and
 * tables, with bold, italic, inline code and links inside them. That is what
 * the replies actually contain. Pure Kotlin with no Android in it, so every
 * rule here is unit-tested on the JVM.
 */
sealed interface Block {
    data class Heading(val level: Int, val inlines: List<Inline>) : Block
    data class Paragraph(val inlines: List<Inline>) : Block
    data class ListItem(val ordered: Boolean, val number: Int, val depth: Int, val inlines: List<Inline>) : Block
    data class Code(val language: String, val text: String) : Block
    data class Quote(val inlines: List<Inline>) : Block
    data class Table(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : Block
    data object Rule : Block
}

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Code(val text: String) : Inline
    data class Link(val children: List<Inline>, val url: String) : Inline
    /** `![alt](src)`, and the `<img>` / `<video>` tags READMEs use: shown as the picture or video itself. */
    data class Image(val alt: String, val url: String) : Inline
}

object Markdown {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
    private val fence = Regex("""^\s*(```|~~~)\s*([\w+#.-]*)\s*$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableSeparator = Regex("""^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$""")

    private val htmlMedia = Regex("""<(?:img|video|source)\b[^>]*?\bsrc\s*=\s*["']([^"']+)["'][^>]*>(?:\s*</video>)?""", RegexOption.IGNORE_CASE)

    /**
     * Where a link or picture in a file points: absolute paths and web links as
     * they are, anything else relative to the file's own folder, with ./ and
     * ../ folded and an #anchor dropped.
     */
    fun resolve(url: String, baseDir: String?): String {
        val u = url.trim()
        if (baseDir == null || u.isEmpty() || u.startsWith("/") || u.startsWith("#") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) return u
        val parts = ArrayDeque(baseDir.trimEnd('/').split('/').filter { it.isNotEmpty() })
        for (piece in u.substringBefore('#').split('/')) when (piece) {
            "", "." -> {}
            ".." -> parts.removeLastOrNull()
            else -> parts.addLast(piece)
        }
        return "/" + parts.joinToString("/")
    }

    /** Every picture or video a block shows. */
    fun imagesIn(block: Block): List<Inline.Image> {
        fun walk(list: List<Inline>): List<Inline.Image> = list.flatMap {
            when (it) {
                is Inline.Image -> listOf(it)
                is Inline.Bold -> walk(it.children)
                is Inline.Italic -> walk(it.children)
                is Inline.Link -> walk(it.children)
                else -> emptyList()
            }
        }
        return when (block) {
            is Block.Heading -> walk(block.inlines)
            is Block.Paragraph -> walk(block.inlines)
            is Block.ListItem -> walk(block.inlines)
            is Block.Quote -> walk(block.inlines)
            is Block.Table -> (block.header + block.rows.flatten()).flatMap { walk(it) }
            else -> emptyList()
        }
    }

    fun parse(source: String): List<Block> {
        val lines = source.replace("\r\n", "\n")
            .replace(htmlMedia) { m -> "![](${m.groupValues[1]})" }
            .split('\n')
        val blocks = mutableListOf<Block>()
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(inline(paragraph.joinToString(" ") { it.trim() }))
                paragraph.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fence.matchEntire(line)
            when {
                fenceMatch != null -> {
                    flushParagraph()
                    val marker = fenceMatch.groupValues[1]
                    val language = fenceMatch.groupValues[2]
                    val body = mutableListOf<String>()
                    i++
                    // An unclosed fence runs to the end, as a half-streamed reply does.
                    while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                        body += lines[i]
                        i++
                    }
                    blocks += Block.Code(language, body.joinToString("\n"))
                }
                line.isBlank() -> flushParagraph()
                rule.matches(line) && paragraph.isEmpty() -> blocks += Block.Rule
                heading.matchEntire(line) != null -> {
                    flushParagraph()
                    val m = heading.matchEntire(line)!!
                    blocks += Block.Heading(m.groupValues[1].length, inline(m.groupValues[2].trim()))
                }
                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quoted += lines[i].trimStart().removePrefix(">").trim()
                        i++
                    }
                    blocks += Block.Quote(inline(quoted.joinToString(" ")))
                    continue
                }
                line.contains('|') && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flushParagraph()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<List<Inline>>>()
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                        rows += cells(lines[i])
                        i++
                    }
                    blocks += Block.Table(header, rows)
                    continue
                }
                bullet.matchEntire(line) != null -> {
                    flushParagraph()
                    val m = bullet.matchEntire(line)!!
                    blocks += Block.ListItem(false, 0, depthOf(m.groupValues[1]), inline(m.groupValues[2]))
                }
                numbered.matchEntire(line) != null -> {
                    flushParagraph()
                    val m = numbered.matchEntire(line)!!
                    blocks += Block.ListItem(true, m.groupValues[2].toInt(), depthOf(m.groupValues[1]), inline(m.groupValues[3]))
                }
                // A list item's continuation line belongs to the item, not a new paragraph.
                line.startsWith("  ") && blocks.lastOrNull() is Block.ListItem && paragraph.isEmpty() -> {
                    val last = blocks.removeAt(blocks.size - 1) as Block.ListItem
                    blocks += last.copy(inlines = last.inlines + Inline.Text(" ") + inline(line.trim()))
                }
                else -> paragraph += line
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun depthOf(indent: String): Int = indent.replace("\t", "    ").length / 2

    private fun cells(row: String): List<List<Inline>> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { inline(it.trim()) }

    /** Bold, italic, code and links, nested as far as Claude nests them. */
    fun inline(text: String): List<Inline> {
        val out = mutableListOf<Inline>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out += Inline.Text(plain.toString())
                plain.clear()
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    plain.append(text[i + 1])
                    i += 2
                    continue
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out += Inline.Code(text.substring(i + 1, end))
                        i = end + 1
                        continue
                    }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i + 2) {
                        flush()
                        out += Inline.Bold(inline(text.substring(i + 2, end)))
                        i = end + 2
                        continue
                    }
                }
                (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                    val end = text.indexOf(c, i + 1)
                    // `snake_case_names` are not italics: an underscore needs a word boundary.
                    val wordInside = c == '_' && i > 0 && text[i - 1].isLetterOrDigit()
                    if (end > i + 1 && !wordInside && !text[end - 1].isWhitespace()) {
                        flush()
                        out += Inline.Italic(inline(text.substring(i + 1, end)))
                        i = end + 1
                        continue
                    }
                }
                c == '!' && text.startsWith("![", i) -> {
                    val close = text.indexOf(']', i + 2)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val end = text.indexOf(')', close + 2)
                        if (end > close) {
                            flush()
                            out += Inline.Image(text.substring(i + 2, close), text.substring(close + 2, end).trim().substringBefore(' '))
                            i = end + 1
                            continue
                        }
                    }
                }
                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val end = text.indexOf(')', close + 2)
                        if (end > close) {
                            flush()
                            out += Inline.Link(inline(text.substring(i + 1, close)), text.substring(close + 2, end))
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            plain.append(c)
            i++
        }
        flush()
        return out
    }

    /** Plain text of some inlines, for previews and accessibility. */
    fun plain(inlines: List<Inline>): String = buildString {
        for (node in inlines) when (node) {
            is Inline.Text -> append(node.text)
            is Inline.Code -> append(node.text)
            is Inline.Bold -> append(plain(node.children))
            is Inline.Italic -> append(plain(node.children))
            is Inline.Link -> append(plain(node.children))
            is Inline.Image -> append(node.alt)
        }
    }
}
