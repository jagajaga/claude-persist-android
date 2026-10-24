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

fun annotate(inlines: List<Inline>): AnnotatedString = buildAnnotatedString { appendInlines(inlines) }

private fun AnnotatedString.Builder.appendInlines(inlines: List<Inline>) {
    for (node in inlines) when (node) {
        is Inline.Text -> append(node.text)
        is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlines(node.children) }
        is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node.children) }
        is Inline.Code -> withStyle(SpanStyle(fontFamily = Mono, background = Palette.codeBg, fontSize = 13.5.sp)) {
            append(node.text)
        }
        is Inline.Link -> withLink(
            LinkAnnotation.Url(
                node.url,
                TextLinkStyles(SpanStyle(color = Palette.accent, textDecoration = TextDecoration.Underline)),
            ),
        ) { appendInlines(node.children) }
    }
}

/** Claude's reply, laid out the way the panel lays it out. */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { Markdown.parse(source) }
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) MarkdownBlock(block)
        }
    }
}

@Composable
private fun MarkdownBlock(block: Block) {
    when (block) {
        is Block.Heading -> Text(
            annotate(block.inlines),
            style = Type.body.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = when (block.level) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp },
            ),
        )
        is Block.Paragraph -> Text(annotate(block.inlines), style = Type.body)
        is Block.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            Text(if (block.ordered) "${block.number}." else "•", style = Type.body, modifier = Modifier.width(22.dp))
            Text(annotate(block.inlines), style = Type.body)
        }
        is Block.Quote -> Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.accentSoft, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Text(annotate(block.inlines), style = Type.body.copy(color = Palette.muted)) }
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
            TableRow(block.header, header = true)
            for (row in block.rows) {
                HorizontalDivider(color = Palette.border)
                TableRow(row, header = false)
            }
        }
        Block.Rule -> HorizontalDivider(color = Palette.border)
    }
}

@Composable
private fun TableRow(cells: List<List<Inline>>, header: Boolean) {
    Row {
        for (cell in cells) {
            Text(
                annotate(cell),
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
