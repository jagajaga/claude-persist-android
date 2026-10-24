package me.jagajaga.persist.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val received = LinkedBlockingQueue<String>()

    /** A server that answers every request with {echo: op}, like a tiny daemon. */
    private fun echoingUpgrade(): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            val req = Json.parseToJsonElement(text).jsonObject
            val id = req["id"]!!.jsonPrimitive.int
            val op = req["op"]!!.jsonPrimitive.content
            val reply = if (op == "boom") {
                """{"kind":"response","id":$id,"ok":false,"error":"it broke"}"""
            } else if (op == "never") {
                return
            } else {
                """{"kind":"response","id":$id,"ok":true,"result":{"echo":"$op"}}"""
            }
            webSocket.send(reply)
        }
    })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    /** The server side of the n-th connection, or a failure that says what happened instead. */
    private fun serverSocket(gw: GatewayClient): WebSocket =
        serverSockets.poll(5, TimeUnit.SECONDS)
            ?: throw AssertionError("no server-side socket after 5s; requests=${server.requestCount}, client=${gw.state.value}")

    private fun client(cookie: String? = "authelia_session=abc") = GatewayClient(
        scope = scope,
        wsUrl = { server.url("/proxy/7790/ws").toString().replace("http", "ws") },
        cookie = { cookie },
        backoff = Backoff(baseMs = 50, maxMs = 200),
    )

    @Test
    fun `a request gets its answer`() = runBlocking {
        server.enqueue(echoingUpgrade())
        val gw = client()
        gw.start()
        val result = withTimeout(10_000) { gw.request("listSessions") }
        assertEquals("listSessions", result!!.jsonObject["echo"]!!.jsonPrimitive.content)
        gw.stop()
    }

    @Test
    fun `the session cookie is sent with the handshake`() = runBlocking {
        server.enqueue(echoingUpgrade())
        val gw = client(cookie = "authelia_session=xyz")
        gw.start()
        withTimeout(10_000) { gw.request("hello") }
        assertEquals("authelia_session=xyz", server.takeRequest().getHeader("Cookie"))
        gw.stop()
    }

    @Test
    fun `an error answer becomes an exception, and the connection stays up`() = runBlocking {
        server.enqueue(echoingUpgrade())
        val gw = client()
        gw.start()
        try {
            withTimeout(10_000) { gw.request("boom") }
            fail("expected an error")
        } catch (e: GatewayException) {
            assertEquals("it broke", e.message)
        }
        assertEquals("hello", withTimeout(10_000) { gw.request("hello") }!!.jsonObject["echo"]!!.jsonPrimitive.content)
        gw.stop()
    }

    @Test
    fun `parameters are sent alongside the op`() = runBlocking {
        server.enqueue(echoingUpgrade())
        val gw = client()
        gw.start()
        withTimeout(10_000) { gw.request("attach", mapOf("sessionId" to JsonPrimitive("s1"), "sinceSeq" to JsonPrimitive(41))) }
        val sent = Json.parseToJsonElement(received.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("s1", sent["sessionId"]!!.jsonPrimitive.content)
        assertEquals(41, sent["sinceSeq"]!!.jsonPrimitive.int)
        gw.stop()
    }

    /** Pushes -- events, deltas -- reach the app through their own stream. */
    @Test
    fun `pushes arrive on their own stream`() = runBlocking {
        server.enqueue(echoingUpgrade())
        val gw = client()
        val push = async { withTimeout(10_000) { gw.pushes.first() } }
        gw.start()
        withTimeout(10_000) { gw.request("hello") }
        serverSocket(gw).send("""{"kind":"event","sessionId":"s1","event":{"seq":3}}""")
        assertEquals("s1", push.await()["sessionId"]!!.jsonPrimitive.content)
        gw.stop()
    }

    /**
     * The everyday failure: the link drops with a request in flight. The request
     * must fail promptly rather than hang, so its caller can retry after reconnect.
     */
    @Test
    fun `a request in flight when the link drops fails instead of hanging`() = runBlocking {
        server.enqueue(echoingUpgrade())
        server.enqueue(echoingUpgrade())
        val gw = client()
        gw.start()
        withTimeout(10_000) { gw.request("hello") }
        val inFlight = async { runCatching { gw.request("never", timeoutMs = 30_000) } }
        Thread.sleep(200)
        serverSocket(gw).cancel()
        val outcome = withTimeout(10_000) { inFlight.await() }
        assertTrue("got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is DisconnectedException)
        gw.stop()
    }

    /** And it comes back by itself. */
    @Test
    fun `it reconnects after the server drops it`() = runBlocking {
        server.enqueue(echoingUpgrade())
        server.enqueue(echoingUpgrade())
        val gw = client()
        gw.start()
        withTimeout(10_000) { gw.request("hello") }
        serverSocket(gw).cancel()
        val again = withTimeout(10_000) { gw.request("listSessions") }
        assertEquals("listSessions", again!!.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals(2, server.requestCount)
        gw.stop()
    }

    /**
     * Authelia answers an expired session with a redirect to its login page.
     * That is a login to redo, not a network blip to retry with the same cookie.
     */
    @Test
    fun `a redirect on the handshake means log in again`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/authelia"))
        val gw = client()
        gw.start()
        withTimeout(10_000) { gw.state.first { it is ConnectionState.NeedsLogin } }
        assertEquals("no hammering while logged out", 1, server.requestCount)
        gw.stop()
    }

    @Test
    fun `after logging in again it connects at once`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302))
        server.enqueue(echoingUpgrade())
        val gw = client()
        gw.start()
        withTimeout(10_000) { gw.state.first { it is ConnectionState.NeedsLogin } }
        gw.reconnectNow()
        withTimeout(10_000) { gw.state.first { it is ConnectionState.Online } }
        gw.stop()
    }

    /** Offline is usually "offline for a few seconds": a request waits for the link. */
    @Test
    fun `a request made before the connection is up waits for it`() = runBlocking {
        server.enqueue(echoingUpgrade())
        val gw = client()
        val result = async { gw.request("hello") }
        Thread.sleep(100)
        gw.start()
        assertEquals("hello", withTimeout(10_000) { result.await() }!!.jsonObject["echo"]!!.jsonPrimitive.content)
        gw.stop()
    }
}
