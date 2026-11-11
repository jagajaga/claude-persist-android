package me.jagajaga.persist.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.jagajaga.persist.ui.Mono
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.viewer.LinkRouter
import me.jagajaga.persist.ui.viewer.Links

private val linkStyle = TextLinkStyles(SpanStyle(color = Palette.accent, textDecoration = TextDecoration.Underline))

fun annotate(inlines: List<Inline>, resolve: (String) -> String = { it }): AnnotatedString =
    buildAnnotatedString { appendInlines(inlines, resolve) }

private fun AnnotatedString.Builder.appendInlines(inlines: List<Inline>, resolve: (String) -> String) {
    for (node in inlines) when (node) {
        // Paths and URLs written as plain text are tappable too.
        is Inline.Text -> for ((piece, link) in Links.segments(node.text)) {
            if (link == null) append(piece) else withLink(LinkRouter.link(link, linkStyle)) { append(piece) }
        }
        is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlines(node.children, resolve) }
        is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node.children, resolve) }
        is Inline.Code -> {
            val code = SpanStyle(fontFamily = Mono, background = Palette.codeBg, fontSize = 13.5.sp)
            val link = Links.wholeLink(node.text)
            if (link == null) {
                withStyle(code) { append(node.text) }
            } else {
                withLink(LinkRouter.link(link, TextLinkStyles(code.copy(color = Palette.accent)))) { append(node.text) }
            }
        }
        is Inline.Link -> withLink(LinkRouter.link(resolve(node.url), linkStyle)) { appendInlines(node.children, resolve) }
        // Drawn as the picture itself, under its block (see MarkdownText); nothing in the text.
        is Inline.Image -> Unit
    }
}

/** Claude's reply, laid out the way the panel lays it out. */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier, baseDir: String? = null, tone: androidx.compose.ui.graphics.Color? = null) {
    val blocks = remember(source) { Markdown.parse(source) }
    val resolve: (String) -> String = remember(baseDir) { { url -> Markdown.resolve(url, baseDir) } }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                // A block that is only a picture has no text to show.
                val textless = block is Block.Paragraph && block.inlines.all { it is Inline.Image || (it is Inline.Text && it.text.isBlank()) }
                if (!textless) MarkdownBlock(block, resolve, tone)
                // Its pictures and videos, shown as themselves, right under it.
                for (image in Markdown.imagesIn(block)) {
                    me.jagajaga.persist.ui.viewer.MediaPreview(resolve(image.url), maxHeight = 360.dp, hideOnFailure = false)
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: Block, resolve: (String) -> String, tone: androidx.compose.ui.graphics.Color? = null) {
    // Body text in the given tone (thinking is quieter than an answer).
    val body = tone?.let { Type.body.copy(color = it) } ?: Type.body
    when (block) {
        is Block.Heading -> Text(
            annotate(block.inlines, resolve),
            style = body.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = when (block.level) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp },
            ),
        )
        is Block.Paragraph -> Text(annotate(block.inlines, resolve), style = body)
        is Block.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            Text(if (block.ordered) "${block.number}." else "•", style = body, modifier = Modifier.width(22.dp))
            Text(annotate(block.inlines, resolve), style = body)
        }
        is Block.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.accentSoft, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Text(annotate(block.inlines, resolve), style = Type.body.copy(color = Palette.muted)) }
        is Block.Code -> Text(
            block.text,
            style = Type.code,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.codeBg, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        )
        is Block.Table -> Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .border(1.dp, Palette.border, RoundedCornerShape(6.dp)),
        ) {
            TableRow(block.header, header = true, resolve)
            for (row in block.rows) {
                HorizontalDivider(color = Palette.border)
                TableRow(row, header = false, resolve)
            }
        }
        Block.Rule -> HorizontalDivider(color = Palette.border)
    }
}

@Composable
private fun TableRow(cells: List<List<Inline>>, header: Boolean, resolve: (String) -> String) {
    Row {
        for (cell in cells) {
            Text(
                annotate(cell, resolve),
                style = Type.small.copy(
                    color = Palette.text,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .width(140.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
