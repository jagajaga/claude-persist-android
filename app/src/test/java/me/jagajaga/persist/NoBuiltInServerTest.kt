package me.jagajaga.persist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The app carries no server address of its own: the user enters theirs.
 * Example hosts (example.com, .invalid) and Android's XML namespace are all
 * a source file may name; any other http(s):// literal in the app's code is
 * a server someone built in.
 */
class NoBuiltInServerTest {
    private val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()

    @Test
    fun `the app's sources are where this test looks`() {
        assertEquals(true, sources.any { it.name == "PersistApp.kt" })
    }

    @Test
    fun `no source file names a real server`() {
        val literal = Regex("""https?://([A-Za-z0-9.-]+)""")
        val allowed = Regex("""(^|\.)(example\.com|invalid)$|^schemas\.android\.com$""")
        val found = sources.flatMap { f ->
            literal.findAll(f.readText()).map { it.groupValues[1] }.filterNot { allowed.containsMatchIn(it) }.map { "${f.name}: $it" }.toList()
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `settings have no default address`() {
        val settings = File("src/main/java/me/jagajaga/persist/PersistApp.kt").readText()
        assertEquals(false, settings.contains("DEFAULT_BASE_URL"))
        assertEquals(false, Regex("""getString\("baseUrl",\s*"[^"]""").containsMatchIn(settings))
    }
}
