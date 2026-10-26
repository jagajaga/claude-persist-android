package me.jagajaga.persist.ui.list

import org.junit.Assert.assertEquals
import org.junit.Test

class TabsTest {
    @Test
    fun `opening adds to the end once`() {
        assertEquals(listOf("a", "b"), Tabs.open(listOf("a"), "b"))
        assertEquals(listOf("a", "b"), Tabs.open(listOf("a", "b"), "a"))
    }

    @Test
    fun `closing the tab in view moves to its right, or left at the end`() {
        assertEquals(listOf("a", "c") to "c", Tabs.close(listOf("a", "b", "c"), "b", current = "b"))
        assertEquals(listOf("a", "b") to "b", Tabs.close(listOf("a", "b", "c"), "c", current = "c"))
        assertEquals(emptyList<String>() to null, Tabs.close(listOf("a"), "a", current = "a"))
    }

    @Test
    fun `closing another tab leaves you where you are`() {
        assertEquals(listOf("b", "c") to "c", Tabs.close(listOf("a", "b", "c"), "a", current = "c"))
    }

    @Test
    fun `a deleted chat drops out of the tabs`() {
        assertEquals(listOf("a"), Tabs.existing(listOf("a", "gone"), setOf("a", "b")))
        assertEquals(listOf("a", "gone"), Tabs.existing(listOf("a", "gone"), emptySet()))
    }

    /** The bug: the chat a screen was opened on came straight back when you closed its tab. */
    @Test
    fun `the tab a screen was opened on stays closed once closed`() {
        val start = "c"
        var saved = Tabs.open(listOf("a", "b"), start)
        val (left, next) = Tabs.close(saved, start, current = start)
        saved = left
        assertEquals(listOf("a", "b"), Tabs.visible(saved, start, startSaved = true, known = setOf("a", "b", "c")))
        assertEquals("b", next)
    }

    @Test
    fun `before it is saved, the chat opened on is shown already`() {
        assertEquals(listOf("a", "c"), Tabs.visible(listOf("a"), "c", startSaved = false, known = emptySet()))
    }
}
