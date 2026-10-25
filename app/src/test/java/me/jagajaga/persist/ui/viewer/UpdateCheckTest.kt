package me.jagajaga.persist.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun `newer by number, not by text`() {
        assertTrue(UpdateCheck.isNewer("0.1.20", "0.1.9"))
        assertTrue(UpdateCheck.isNewer("v0.2.0", "0.1.99"))
        assertFalse(UpdateCheck.isNewer("0.1.13", "0.1.13"))
        assertFalse(UpdateCheck.isNewer("0.1.12", "0.1.13"))
    }

    @Test
    fun `a dev build takes any release`() {
        assertTrue(UpdateCheck.isNewer("0.1.13", "0.1.0-dev"))
    }

    @Test
    fun `nonsense from the server never prompts an install`() {
        assertFalse(UpdateCheck.isNewer("latest", "0.1.13"))
    }
}
