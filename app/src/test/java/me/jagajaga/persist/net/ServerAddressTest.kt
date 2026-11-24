package me.jagajaga.persist.net

import me.jagajaga.persist.net.ServerAddress.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    private fun ok(input: String) = (ServerAddress.parse(input) as? Parsed.Ok)?.url
    private fun bad(input: String) = (ServerAddress.parse(input) as? Parsed.Bad)?.reason

    @Test
    fun `an https address is kept, without its trailing slashes`() {
        assertEquals("https://code.example.com", ok("https://code.example.com"))
        assertEquals("https://code.example.com", ok("https://code.example.com/"))
        assertEquals("https://code.example.com", ok("https://code.example.com///"))
        assertEquals("https://example.com/code", ok("https://example.com/code/"))
    }

    @Test
    fun `spaces around it and capitals in the name don't matter`() {
        assertEquals("https://code.example.com", ok("  HTTPS://Code.Example.COM/ \n"))
    }

    @Test
    fun `a bare name means https`() {
        assertEquals("https://code.example.com", ok("code.example.com"))
        assertEquals("https://code.example.com:8443", ok("code.example.com:8443/"))
    }

    @Test
    fun `the https port is left out, another one kept`() {
        assertEquals("https://code.example.com", ok("https://code.example.com:443"))
        assertEquals("https://code.example.com:8443", ok("https://code.example.com:8443"))
    }

    @Test
    fun `plain http is refused, saying why`() {
        assertTrue(bad("http://code.example.com")!!.contains("https://"))
    }

    @Test
    fun `other schemes, blanks and junk are refused`() {
        assertEquals("Enter your server's address", bad(""))
        assertEquals("Enter your server's address", bad("   "))
        assertEquals("Use an https:// address", bad("ftp://code.example.com"))
        assertEquals("Use an https:// address", bad("wss://code.example.com"))
        assertEquals("An address has no spaces", bad("code example.com"))
        assertEquals("That isn't a web address", bad("https://"))
        assertEquals("That address has no server name", bad("https:///path"))
        assertEquals("That isn't a web address", bad("https://[bad"))
    }

    @Test
    fun `a login, query or fragment in the address is refused`() {
        assertTrue(bad("https://me:secret@code.example.com")!!.contains("login"))
        assertTrue(bad("https://code.example.com/?folder=/home")!!.contains("?"))
        assertTrue(bad("https://code.example.com/#x")!!.contains("#"))
    }

    // -- What a phone already has saved: the migration from a built-in default.

    @Test
    fun `nothing saved means no server -- there is no default to fall back on`() {
        assertNull(ServerAddress.stored(null))
        assertNull(ServerAddress.stored(""))
        assertNull(ServerAddress.stored("   "))
    }

    @Test
    fun `an address saved by an older version is kept as it is`() {
        assertEquals("https://code.example.com", ServerAddress.stored("https://code.example.com"))
        assertEquals("https://code.example.com", ServerAddress.stored("https://code.example.com/"))
        // Older versions saved whatever was typed; an http one still connects as before.
        assertEquals("http://10.0.2.2:8080", ServerAddress.stored("http://10.0.2.2:8080"))
    }

    @Test
    fun `a saved value that is no address asks again`() {
        assertNull(ServerAddress.stored("code.example.com"))
        assertNull(ServerAddress.stored("ftp://code.example.com"))
        assertNull(ServerAddress.stored("https://"))
        assertNull(ServerAddress.stored("not an address"))
    }

    // -- Which links carry the login cookie.

    @Test
    fun `links on the server are ours`() {
        val base = "https://code.example.com"
        assertTrue(ServerAddress.isUnder("https://code.example.com", base))
        assertTrue(ServerAddress.isUnder("https://code.example.com/proxy/7790/file?path=%2Fa", base))
        assertTrue(ServerAddress.isUnder("https://code.example.com?x=1", base))
    }

    @Test
    fun `a host that only starts the same is not ours`() {
        val base = "https://code.example.com"
        assertFalse(ServerAddress.isUnder("https://code.example.com.evil.net/x", base))
        assertFalse(ServerAddress.isUnder("https://code.example.community/x", base))
        assertFalse(ServerAddress.isUnder("https://other.example.com/x", base))
    }

    @Test
    fun `with no server set, nothing is ours`() {
        assertFalse(ServerAddress.isUnder("https://code.example.com/x", null))
        assertFalse(ServerAddress.isUnder("https://code.example.com/x", ""))
        assertFalse(ServerAddress.isUnder("/proxy/7790/file", ""))
        assertFalse(ServerAddress.isUnder("", ""))
    }
}
