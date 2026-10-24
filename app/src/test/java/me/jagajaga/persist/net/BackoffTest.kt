package me.jagajaga.persist.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {
    @Test
    fun `doubles until the cap, never above it`() {
        val b = Backoff(baseMs = 500, maxMs = 30_000, random = { 1.0 })
        val delays = List(10) { b.next() }
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L, 30000L), delays)
    }

    /** A server restart must not be answered by every phone in the same instant. */
    @Test
    fun `jitter keeps each delay between half and all of its ceiling`() {
        val low = Backoff(baseMs = 1000, random = { 0.0 })
        assertEquals(500L, low.next())
        val mid = Backoff(baseMs = 1000, random = { 0.5 })
        assertEquals(750L, mid.next())
    }

    @Test
    fun `a working connection starts the next failure from the bottom`() {
        val b = Backoff(baseMs = 500, random = { 1.0 })
        repeat(5) { b.next() }
        b.reset()
        assertEquals(500L, b.next())
    }

    @Test
    fun `never zero, even with the unluckiest jitter`() {
        val b = Backoff(baseMs = 500, random = { 0.0 })
        repeat(40) { assertTrue(b.next() > 0) }
    }
}
