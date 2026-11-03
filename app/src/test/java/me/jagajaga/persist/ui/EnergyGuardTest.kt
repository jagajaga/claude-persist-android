package me.jagajaga.persist.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An endless smooth animation redraws the screen at the display's rate for as
 * long as it runs: one pulsing dot kept the app drawing 60 to 120 frames a
 * second whenever any agent worked. Anything that should move for as long as
 * something runs steps through [Pulse] instead.
 */
class EnergyGuardTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `no endless smooth animation anywhere in the app`() {
        assertTrue("sources not found from ${File(".").absolutePath}", sources.size > 20)
        val offenders = sources.filter { f ->
            val text = f.readText()
            "rememberInfiniteTransition" in text || "infiniteRepeatable" in text
        }.map { it.path }
        assertEquals("use Pulse for things that move while something runs", emptyList<String>(), offenders)
    }
}
