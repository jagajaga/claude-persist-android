package me.jagajaga.persist.notify

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRequestsTest {
    @Test
    fun `the notification stays while anything in that chat still waits`() {
        val p = PendingRequests()
        p.asked("s", "r1")
        p.asked("s", "r2")
        assertFalse(p.answered("s", "r1"))
        assertTrue(p.answered("s", "r2"))
    }

    @Test
    fun `chats are counted apart`() {
        val p = PendingRequests()
        p.asked("a", "r")
        p.asked("b", "r")
        assertTrue(p.answered("a", "r"))
        assertFalse("b still waits", p.answered("b", "other"))
    }

    @Test
    fun `an answer to something never seen clears what is up`() {
        assertTrue(PendingRequests().answered("s", "unknown"))
    }
}
