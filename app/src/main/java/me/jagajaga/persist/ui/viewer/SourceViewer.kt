package me.jagajaga.persist.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.jagajaga.persist.Container
import me.jagajaga.persist.data.Highlighted
import me.jagajaga.persist.data.Span
import me.jagajaga.persist.ui.Mono
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.TextFileViewer
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.markdown.MarkdownText

/**
 * A source file as the editor shows it: VS Code's own grammars and Light+
 * colours (coloured on the server by Shiki), line numbers, wrap on or off,
 * text size, and the line a link pointed at scrolled to and marked.
 * Markdown opens rendered, pictures and all, with its source a tap away.
 */
@Composable
fun SourceViewer(c: Container, path: String, line: Int?, onClose: () -> Unit) {
    val isMarkdown = path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "mdx")
    var showSource by rememberSaveable(path) { mutableStateOf(!isMarkdown) }
    var plain by remember(path) { mutableStateOf(false) }
    if (plain) {
        TextFileViewer(c.store, path, onClose)
        return
    }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var size by rememberSaveable { mutableIntStateOf(13) }
    var result by remember(path) { mutableStateOf<Highlighted?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        runCatching { c.store.highlight(path) }
            .onSuccess { result = it }
            // Whatever stopped the colouring (an older server, a file it can't read as
            // text), the plain viewer still shows the file, or says what is wrong with it.
            .onFailure { plain = true }
    }
    val h = result
    Column(Modifier.fillMaxSize().background(h?.let { hex(it.bg) } ?: Palette.surface)) {
        Row(Modifier.fillMaxWidth().background(Palette.surface), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column(Modifier.weight(1f)) {
                Text(path.substringAfterLast('/'), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(h?.language, h?.lines?.size?.let { "$it lines" }).joinToString(" · "), style = Type.tiny)
            }
            if (isMarkdown) {
                Text(
                    if (showSource) "Rendered" else "Source",
                    style = Type.small.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { showSource = !showSource }.padding(8.dp),
                )
            }
            if (showSource) {
                Text("A−", style = Type.small, modifier = Modifier.clickable { size = (size - 1).coerceAtLeast(9) }.padding(6.dp))
                Text("A+", style = Type.small, modifier = Modifier.clickable { size = (size + 1).coerceAtMost(22) }.padding(6.dp))
                IconButton(onClick = { wrap = !wrap }) {
                    Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = if (wrap) "Don't wrap lines" else "Wrap lines", tint = if (wrap) Palette.accent else Palette.muted)
                }
            }
        }
        when {
            error != null -> Text(error!!, style = Type.body.copy(color = Palette.error), modifier = Modifier.padding(16.dp))
            h == null -> Text("Loading…", style = Type.small, modifier = Modifier.padding(16.dp))
            !showSource -> RenderedMarkdown(h, path)
            else -> Lines(h, line, wrap, size) { plain = true }
        }
    }
}

@Composable
private fun RenderedMarkdown(h: Highlighted, path: String) {
    val text = remember(h) { h.lines.joinToString("\n") { l -> l.joinToString("") { it.text } } }
    Box(Modifier.fillMaxSize().background(Palette.surface).verticalScroll(rememberScrollState()).padding(16.dp)) {
        // Relative pictures and links resolve against the file's own folder.
        MarkdownText(text, baseDir = path.substringBeforeLast('/'))
    }
}

@Composable
private fun Lines(h: Highlighted, line: Int?, wrap: Boolean, size: Int, onPlain: () -> Unit) {
    val state = rememberLazyListState()
    // A few lines of what leads up to it, then the line itself.
    LaunchedEffect(h, line) { if (line != null && line > 0) state.scrollToItem((line - 4).coerceAtLeast(0)) }
    val gutter = h.lines.size.toString().length
    val fg = hex(h.fg)
    // Shared by every line, so unwrapped code scrolls sideways as one page.
    val sideways = rememberScrollState()
    val style = Type.code.copy(fontFamily = Mono, fontSize = size.sp, lineHeight = (size * 1.5).sp, color = fg)
    SelectionContainer {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            itemsIndexed(h.lines) { index, spans ->
                val number = index + 1
                Row(Modifier.fillMaxWidth().background(if (number == line) Color(0x33FFD54F) else Color.Transparent)) {
                    Text(
                        number.toString(),
                        style = style.copy(color = Color(0xFF9E9E9E), textAlign = TextAlign.End),
                        modifier = Modifier.width((gutter * size * 0.62 + 14).dp).padding(end = 10.dp),
                    )
                    val text = remember(spans) { colour(spans) }
                    if (wrap) {
                        Text(text, style = style, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    } else {
                        Text(text, style = style, softWrap = false, modifier = Modifier.weight(1f).horizontalScroll(sideways).padding(end = 8.dp))
                    }
                }
            }
            if (h.truncated) {
                item {
                    Text(
                        "Only the first 1 MB is coloured. Read the whole file as plain text",
                        style = Type.small.copy(color = Palette.accent),
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlain).padding(16.dp),
                    )
                }
            }
        }
    }
}

/** One line's spans, in their colours and styles. */
internal fun colour(spans: List<Span>): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val styled = s.color != null || s.style != 0
        if (!styled) {
            append(s.text)
            continue
        }
        withStyle(
            SpanStyle(
                color = s.color?.let { hex(it) } ?: Color.Unspecified,
                fontStyle = if (s.style and 1 != 0) FontStyle.Italic else null,
                fontWeight = if (s.style and 2 != 0) FontWeight.Bold else null,
                textDecoration = if (s.style and 4 != 0) TextDecoration.Underline else null,
            ),
        ) { append(s.text) }
    }
}

/** "#rrggbb" to a colour; black for anything else. */
internal fun hex(s: String): Color = runCatching { Color(0xFF000000 or s.removePrefix("#").take(6).toLong(16)) }.getOrDefault(Color.Black)
