package me.jagajaga.persist.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Online : ConnectionState
    /** Down; the next attempt is at [retryAtMs] unless something wakes it sooner. */
    data class Offline(val reason: String, val retryAtMs: Long) : ConnectionState
    /** Authelia sent us to its login page: the session cookie has expired. */
    data object NeedsLogin : ConnectionState
}

/** The daemon answered, with an error. */
class GatewayException(message: String) : Exception(message)

/** There was no connection to send on, or it dropped before the answer came. */
class DisconnectedException : Exception("Not connected to the server")

/**
 * The request went out and no answer came in time. Whether it ran is
 * unknown -- which is exactly why a send carries its own id: sending it
 * again is safe. Not a CancellationException (what withTimeout throws), so
 * no caller mistakes a lost answer for being cancelled.
 */
class AnswerLostException(op: String, timeoutMs: Long) : Exception("No answer to $op within ${timeoutMs / 1000}s")

/**
 * The one connection to the daemon, kept up for as long as the app runs.
 *
 * Everything about it is shaped by the network a phone actually has:
 *  - It reconnects forever, with capped, jittered backoff, and skips the wait
 *    the moment anything suggests the network changed ([reconnectNow]).
 *  - OkHttp pings every ten seconds and fails the socket if a pong does not
 *    come back, so a link that died silently -- the usual way on mobile -- is
 *    noticed in seconds, not after TCP's own timeout many minutes later.
 *  - A redirect or 401/403 on the handshake is Authelia saying the login has
 *    expired. That is [ConnectionState.NeedsLogin], not a failure to retry:
 *    retrying with the same dead cookie would spin forever.
 *  - Requests made while offline wait for the connection rather than failing
 *    at once; requests in flight when it drops fail with
 *    [DisconnectedException], and the callers that matter (the outbox, the
 *    replay) simply run again after the next connect.
 */
class GatewayClient(
    private val scope: CoroutineScope,
    private val wsUrl: () -> String,
    private val cookie: () -> String?,
    http: OkHttpClient? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val backoff: Backoff = Backoff(),
) {
    private val client: OkHttpClient = http ?: OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // A redirect is Authelia's answer; following it would only fetch its login page.
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = _state

    private val _pushes = MutableSharedFlow<JsonObject>(extraBufferCapacity = 1024)
    /** Everything the daemon sends that is not an answer to a request. */
    val pushes: SharedFlow<JsonObject> = _pushes

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Fires after every successful (re)connect: the moment to resync. */
    val connected: SharedFlow<Unit> = _connected

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val ids = AtomicInteger(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var socket: WebSocket? = null
    private var loop: Job? = null

    fun start() {
        if (loop == null) loop = scope.launch { run() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        socket?.cancel()
        socket = null
        failPending()
        // Not Online any more: a request made now waits for the next start.
        _state.value = ConnectionState.Connecting
    }

    /** The network came back, the app came to the front, or login finished: try now. */
    fun reconnectNow() {
        backoff.reset()
        wake.trySend(Unit)
    }

    private sealed interface Ended {
        data class Closed(val reason: String) : Ended
        data object Login : Ended
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            _state.value = ConnectionState.Connecting
            val ended = connectOnce()
            socket = null
            failPending()
            when (ended) {
                is Ended.Login -> {
                    _state.value = ConnectionState.NeedsLogin
                    wake.receive()
                }
                is Ended.Closed -> {
                    val wait = backoff.next()
                    _state.value = ConnectionState.Offline(ended.reason, now() + wait)
                    withTimeoutOrNull(wait) { wake.receive() }
                }
            }
        }
    }

    private suspend fun connectOnce(): Ended = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)
        fun finish(ended: Ended) {
            if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(ended)
        }
        val builder = Request.Builder().url(wsUrl())
        cookie()?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        val ws = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                backoff.reset()
                _state.value = ConnectionState.Online
                _connected.tryEmit(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Dying now: nothing more may be sent on it, whatever the state says.
                if (socket === webSocket) socket = null
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(Ended.Closed("closed ($code)"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                val code = response?.code
                response?.close()
                val loginExpired = code != null && (code in 300..399 || code == 401 || code == 403)
                finish(if (loginExpired) Ended.Login else Ended.Closed(t.message ?: t.javaClass.simpleName))
            }
        })
        cont.invokeOnCancellation { ws.cancel() }
    }

    private fun handle(text: String) {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if (message["kind"]?.jsonPrimitive?.contentOrNull == "response") {
            val id = message["id"]?.jsonPrimitive?.intOrNull ?: return
            val waiting = pending.remove(id) ?: return
            if (message["ok"]?.jsonPrimitive?.booleanOrNull == true) {
                waiting.complete(message["result"])
            } else {
                waiting.completeExceptionally(
                    GatewayException(message["error"]?.jsonPrimitive?.contentOrNull ?: "The server refused the request"),
                )
            }
        } else {
            _pushes.tryEmit(message)
        }
    }

    private fun failPending() {
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(DisconnectedException()) }
    }

    /**
     * One request; its result, or an exception.
     *
     * Waits up to [waitForConnectionMs] for a connection if there is none --
     * on a bad network "offline" is usually "offline for a few seconds".
     */
    suspend fun request(
        op: String,
        params: Map<String, JsonElement> = emptyMap(),
        timeoutMs: Long = 20_000,
        waitForConnectionMs: Long = 30_000,
    ): JsonElement? {
        val id = ids.getAndIncrement()
        val body = buildJsonObject {
            put("id", id)
            put("op", op)
            params.forEach { (key, value) -> put(key, value) }
        }.toString()
        val answer = CompletableDeferred<JsonElement?>()
        val deadline = now() + waitForConnectionMs
        // A request that was never sent is always safe to send later, so it
        // waits through a reconnect -- including the moment where the old link
        // is already closing but the state has not caught up. Only a request
        // that did go out and lost its answer fails: whether it ran is unknown,
        // and the callers that care (outbox, replay) are built to run again.
        while (true) {
            val remaining = deadline - now()
            if (remaining <= 0) throw DisconnectedException()
            withTimeoutOrNull(remaining) { state.first { it is ConnectionState.Online } }
                ?: throw DisconnectedException()
            val ws = socket
            pending[id] = answer
            if (ws != null && ws.send(body)) break
            pending.remove(id)
            delay(50)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { answer.await() } ?: if (answer.isCompleted) answer.await() else throw AnswerLostException(op, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }
}
