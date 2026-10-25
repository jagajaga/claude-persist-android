package me.jagajaga.persist.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsBarTest {
    @Test
    fun `rates are short enough for the bar`() {
        assertEquals("1.2M", rate(1.2 * 1_048_576))
        assertEquals("340K", rate(340.0 * 1024))
        assertEquals("12B", rate(12.0))
    }

    @Test
    fun `token counts are short`() {
        assertEquals("13k", compactCount(13_224))
        assertEquals("1.2k", compactCount(1_240))
        assertEquals("620", compactCount(620))
        assertEquals("28.4M", compactCount(28_375_694))
    }
}
