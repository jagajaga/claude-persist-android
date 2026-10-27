package me.jagajaga.persist.ui.viewer

import androidx.compose.ui.graphics.Color
import me.jagajaga.persist.data.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceViewerTest {
    @Test
    fun `a line keeps its text and gets each span's colour`() {
        val line = colour(listOf(Span("import", "#AF00DB", 0), Span(" ", null, 0), Span("net", "#001080", 2)))
        assertEquals("import net", line.text)
        assertEquals(listOf(Color(0xFFAF00DB), Color(0xFF001080)), line.spanStyles.map { it.item.color })
        assertEquals(2, line.spanStyles.size)
    }

    @Test
    fun `colours parse, and nonsense is black rather than a crash`() {
        assertEquals(Color(0xFF001080), hex("#001080"))
        assertEquals(Color.Black, hex("nope"))
    }
}
