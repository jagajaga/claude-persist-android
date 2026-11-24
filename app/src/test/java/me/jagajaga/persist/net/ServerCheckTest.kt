package me.jagajaga.persist.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerCheckTest {
    private lateinit var server: MockWebServer

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }

    // A client that would follow redirects: the check must not.
    private fun check(url: String = server.url("/").toString().trimEnd('/')) =
        runBlocking { ServerCheck(OkHttpClient.Builder().followRedirects(true).build(), timeoutS = 1).check(url) }

    private fun failure(result: ServerCheck.Result) = (result as ServerCheck.Result.Failed).reason

    @Test
    fun `code-server itself answering is fine`() {
        server.enqueue(MockResponse().setBody("<html>code-server</html>"))
        assertEquals(ServerCheck.Result.Ok, check())
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `the login portal's redirect is fine, and not followed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/portal").toString()))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ServerCheck.Result.Ok, check())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a login wall is fine`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ServerCheck.Result.Ok, check())
    }

    @Test
    fun `a server answering only errors fails, with its code`() {
        server.enqueue(MockResponse().setResponseCode(502))
        val reason = failure(check())
        assertTrue(reason, reason.contains("502"))
        assertTrue(reason, reason.contains(server.hostName))
    }

    @Test
    fun `nothing listening fails plainly`() {
        val url = server.url("/").toString().trimEnd('/')
        server.shutdown()
        val reason = failure(check(url))
        assertTrue(reason, reason.startsWith("Nothing is answering at"))
    }

    @Test
    fun `a server that never answers fails with the wait`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val reason = failure(check())
        assertEquals("${server.hostName} didn't answer within 1 seconds.", reason)
    }

    @Test
    fun `an unknown name says so`() {
        val reason = failure(check("https://no-such-host.invalid"))
        assertTrue(reason, reason.startsWith("Couldn't find no-such-host.invalid"))
    }

    @Test
    fun `plain http on an https address is a certificate or connection failure, not a crash`() {
        // The mock speaks plain HTTP; asking it for TLS fails the handshake.
        server.enqueue(MockResponse().setBody("hello"))
        val reason = failure(check(server.url("/").toString().replaceFirst("http://", "https://").trimEnd('/')))
        assertTrue(reason, reason.isNotBlank())
    }
}
