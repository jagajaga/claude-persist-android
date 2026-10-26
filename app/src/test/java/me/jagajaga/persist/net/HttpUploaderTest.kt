package me.jagajaga.persist.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpUploaderTest {
    private lateinit var server: MockWebServer

    @Before fun up() { server = MockWebServer(); server.start() }
    @After fun down() { server.shutdown() }

    private fun uploader() = HttpUploader(baseUrl = { server.url("/proxy/7790").toString().trimEnd('/') }, cookie = { "authelia_session=abc" })

    @Test
    fun `raw bytes go up with their place, and the answer says where to go on`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"received":7}}"""))
        val a = uploader().put("s1", "u1", "my photo.jpg", 0, "JPEGJPG".toByteArray(), done = false)
        assertEquals(HttpUploader.Answer(7, null), a)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!, req.path!!.startsWith("/proxy/7790/upload?sessionId=s1&uploadId=u1&name=my+photo.jpg&offset=0"))
        assertEquals("JPEGJPG", req.body.readUtf8())
        assertEquals("authelia_session=abc", req.getHeader("Cookie"))
    }

    @Test
    fun `the last piece names the file`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"received":3,"path":"/up/s1/u1-a.jpg"}}"""))
        assertEquals("/up/s1/u1-a.jpg", uploader().put("s1", "u1", "a.jpg", 0, "abc".toByteArray(), done = true).path)
        assertTrue(server.takeRequest().path!!.endsWith("&done=1"))
    }

    /** An older daemon answers with code-server's own 404 page or a 405: use the socket instead. */
    @Test
    fun `a server without the route says so, so the socket is used`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(405).setBody("Method Not Allowed"))
        assertTrue(runCatching { uploader().put("s", "u", "n", 0, ByteArray(1), false) }.exceptionOrNull() is UploadRouteMissing)
    }

    @Test
    fun `a refusal comes back as one, with the reason`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"unknown session s"}"""))
        val e = runCatching { uploader().put("s", "u", "n", 0, ByteArray(1), false) }.exceptionOrNull()
        assertTrue("$e", e is GatewayException && e.message == "unknown session s")
    }

    @Test
    fun `Authelia answering means log in again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/authelia"))
        assertTrue(runCatching { uploader().put("s", "u", "n", 0, ByteArray(1), false) }.exceptionOrNull() is NeedsLoginException)
    }
}
