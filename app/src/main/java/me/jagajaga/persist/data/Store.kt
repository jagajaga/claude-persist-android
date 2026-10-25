package me.jagajaga.persist.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.jagajaga.persist.net.DisconnectedException
import me.jagajaga.persist.net.GatewayClient
import me.jagajaga.persist.net.GatewayException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What the app knows, and how it stays in step with the server.
 *
 * Offline-first: everything shown comes from here, and here is filled from
 * disk before any network is involved, so the app opens on the last known
 * state instantly -- in a lift, on a plane, on two bars of 3G. The network
 * only ever adds to it.
 *
 * Every (re)connect does the same thing, in the same order: refresh the chat
 * list, replay each open chat from the first event not yet held, send
 * whatever is waiting in the outbox, and resubscribe to the feed. Because
 * replay is by sequence number and sends are idempotent, running that after
 * every one of a hundred reconnects is exactly as correct as running it once.
 */
class Store(
    private val gw: GatewayClient,
    private val dir: File,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _sessions = MutableStateFlow(readCached(SESSIONS_FILE, ListSerializer(SessionInfo.serializer())) ?: emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    private val logs = ConcurrentHashMap<String, SessionLog>()
    private val logFlows = ConcurrentHashMap<String, MutableStateFlow<List<JsonObject>>>()
    private val deltaFlows = ConcurrentHashMap<String, MutableStateFlow<String>>()

    private val outbox = Outbox(File(dir, "outbox.json"))
    private val _outgoing = MutableStateFlow(outbox.pending())
    /** Messages written here and not yet confirmed by the server. */
    val outgoing: StateFlow<List<OutgoingMessage>> = _outgoing

    /** Chats on screen right now; kept attached across reconnects. */
    private val open = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var feedWanted = false
    private val _feed = MutableStateFlow(readCached(FEED_FILE, ListSerializer(FeedItem.serializer())) ?: emptyList())
    val feed: StateFlow<List<FeedItem>> = _feed

    private val flushLock = Mutex()
    private val saveJobs = ConcurrentHashMap<String, Job>()

    init {
        scope.launch { gw.connected.collect { onConnected() } }
        scope.launch { gw.pushes.collect { onPush(it) } }
    }

    // ---------------------------------------------------------------- sync

    private suspend fun onConnected() {
        runCatching { refreshSessions() }
        for (id in open.toList()) runCatching { attach(id) }
        flushOutbox()
        if (feedWanted) runCatching { subscribeFeed() }
    }

    suspend fun refreshSessions() {
        val result = gw.request("listSessions") ?: return
        val list = json.decodeFromJsonElement(ListSerializer(SessionInfo.serializer()), result)
        _sessions.value = list.sortedByDescending { it.lastActivityAt }
        save(SESSIONS_FILE, ListSerializer(SessionInfo.serializer()), _sessions.value)
    }

    private fun onPush(message: JsonObject) {
        when (message["kind"]?.jsonPrimitive?.contentOrNull) {
            "event" -> {
                val sessionId = message["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val event = message["event"] as? JsonObject ?: return
                if (logFor(sessionId).merge(listOf(event))) publish(sessionId)
                val type = typeOf(event)
                if (type == "assistant_text") deltaFor(sessionId).value = ""
                if (type == "user_message") {
                    // The server has it: whatever the outbox still holds for it is done.
                    clientIdOf(event)?.let { confirmSent(it) }
                }
                if (feedWanted && type in FEED_TYPES) {
                    val title = _sessions.value.firstOrNull { it.id == sessionId }?.title ?: ""
                    _feed.value = (listOf(FeedItem(sessionId, title, event)) + _feed.value).take(FEED_KEEP)
                    save(FEED_FILE, ListSerializer(FeedItem.serializer()), _feed.value)
                }
            }
            "delta" -> {
                val sessionId = message["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val text = message["text"]?.jsonPrimitive?.contentOrNull ?: return
                deltaFor(sessionId).value += text
            }
            "sessions_changed" -> scope.launch { runCatching { refreshSessions() } }
        }
    }

    // ---------------------------------------------------------------- chats

    fun events(sessionId: String): StateFlow<List<JsonObject>> = flowFor(sessionId)
    fun streaming(sessionId: String): StateFlow<String> = deltaFor(sessionId)

    /**
     * Scroll-back: the page before the oldest event held. Returns whether
     * there is still more before that.
     */
    suspend fun loadEarlier(sessionId: String): Boolean {
        val log = logFor(sessionId)
        if (!log.hasEarlier) return false
        val result = gw.request(
            "history",
            mapOf(
                "sessionId" to JsonPrimitive(sessionId),
                "beforeSeq" to JsonPrimitive(log.firstSeq),
                "limit" to JsonPrimitive(HISTORY_PAGE),
            ),
            timeoutMs = 20_000,
            waitForConnectionMs = 10_000,
        ) ?: return log.hasEarlier
        val page = json.decodeFromJsonElement(AttachResult.serializer(), result)
        if (log.merge(page.events)) publish(sessionId)
        return log.hasEarlier
    }

    /** A chat came on screen: attach, and stay attached across reconnects. */
    fun openChat(sessionId: String) {
        open.add(sessionId)
        scope.launch { runCatching { attach(sessionId) } }
    }

    fun closeChat(sessionId: String) {
        open.remove(sessionId)
        scope.launch { runCatching { gw.request("detach", mapOf("sessionId" to JsonPrimitive(sessionId))) } }
    }

    private suspend fun attach(sessionId: String) {
        val log = logFor(sessionId)
        val result = gw.request(
            "attach",
            mapOf(
                "sessionId" to JsonPrimitive(sessionId),
                "sinceSeq" to JsonPrimitive(log.resumeFrom),
                "limit" to JsonPrimitive(REPLAY_LIMIT),
            ),
        ) ?: return
        val attached = json.decodeFromJsonElement(AttachResult.serializer(), result)
        if (log.applyReplay(attached.events, skippedSome = attached.hasEarlier)) publish(sessionId)
        attached.info?.let { info ->
            _sessions.value = _sessions.value.map { if (it.id == info.id) info else it }
        }
        // Anything the server now holds needs no resending.
        attached.events.mapNotNull { clientIdOf(it) }.forEach { confirmSent(it) }
    }

    suspend fun createSession(cwd: String, title: String?): SessionInfo {
        val params = buildMap<String, JsonElement> {
            put("cwd", JsonPrimitive(cwd))
            if (!title.isNullOrBlank()) put("title", JsonPrimitive(title))
        }
        val info = json.decodeFromJsonElement(SessionInfo.serializer(), gw.request("createSession", params)!!)
        _sessions.value = listOf(info) + _sessions.value.filter { it.id != info.id }
        return info
    }

    suspend fun interrupt(sessionId: String) {
        gw.request("interrupt", mapOf("sessionId" to JsonPrimitive(sessionId)))
    }

    suspend fun answerPermission(sessionId: String, requestId: String, allow: Boolean, answers: Map<String, String>? = null) {
        val params = buildMap<String, JsonElement> {
            put("sessionId", JsonPrimitive(sessionId))
            put("requestId", JsonPrimitive(requestId))
            put("allow", JsonPrimitive(allow))
            if (answers != null) put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
        }
        gw.request("permission", params)
    }

    // ---------------------------------------------------------------- sending

    /** Queue a message; it is on disk before this returns, and goes out when it can. */
    fun send(sessionId: String, text: String, images: List<OutgoingImage> = emptyList()) {
        outbox.add(sessionId, text, images)
        _outgoing.value = outbox.pending()
        scope.launch { flushOutbox() }
    }

    private fun confirmSent(clientMessageId: String) {
        outbox.confirm(clientMessageId)
        _outgoing.value = outbox.pending()
    }

    /**
     * Send everything waiting, oldest first, stopping at the first sign the
     * link is down -- the next connect starts over from the same place.
     */
    suspend fun flushOutbox() = flushLock.withLock {
        for (message in outbox.pending()) {
            outbox.attempted(message.clientMessageId)
            try {
                gw.request("sendMessage", sendParams(message))
                confirmSent(message.clientMessageId)
            } catch (e: DisconnectedException) {
                break
            } catch (e: GatewayException) {
                // Refused outright -- the chat was deleted, say. Resending would
                // be refused forever; let it go rather than block the queue.
                confirmSent(message.clientMessageId)
            } catch (e: Exception) {
                break
            }
        }
        _outgoing.value = outbox.pending()
    }

    private fun sendParams(message: OutgoingMessage): Map<String, JsonElement> = buildMap {
        put("sessionId", JsonPrimitive(message.sessionId))
        put("text", JsonPrimitive(message.text))
        put("clientMessageId", JsonPrimitive(message.clientMessageId))
        if (message.images.isNotEmpty()) {
            put(
                "attachments",
                JsonArray(
                    message.images.map { image ->
                        buildJsonObject {
                            put("kind", "image")
                            put("name", image.name)
                            put("mediaType", image.mediaType)
                            put("data", image.base64)
                        }
                    },
                ),
            )
        }
    }

    // ---------------------------------------------------------------- feed, files, stats

    fun wantFeed(wanted: Boolean) {
        feedWanted = wanted
        if (wanted) scope.launch { runCatching { subscribeFeed() } }
    }

    private suspend fun subscribeFeed() {
        val result = gw.request("feed", mapOf("limit" to JsonPrimitive(FEED_KEEP))) ?: return
        _feed.value = json.decodeFromJsonElement(ListSerializer(FeedItem.serializer()), result)
        save(FEED_FILE, ListSerializer(FeedItem.serializer()), _feed.value)
    }

    suspend fun list(path: String): FsListing =
        json.decodeFromJsonElement(FsListing.serializer(), gw.request("fsList", mapOf("path" to JsonPrimitive(path)))!!)

    suspend fun read(path: String, offset: Long = 0): FsRead = json.decodeFromJsonElement(
        FsRead.serializer(),
        gw.request("fsRead", mapOf("path" to JsonPrimitive(path), "offset" to JsonPrimitive(offset)))!!,
    )

    suspend fun stats(): ServerStats =
        json.decodeFromJsonElement(ServerStats.serializer(), gw.request("serverStats", waitForConnectionMs = 5_000)!!)

    // ---------------------------------------------------------------- rescue
    //
    // These are for a machine on its knees, so they wait briefly and fail
    // loudly: a stale list the screen labels with its age beats a spinner.
    // A kill is never retried here -- the pid could be reused -- the user
    // looks at the fresh list and decides again.

    suspend fun processes(limit: Int = 40): ProcessSnapshot = json.decodeFromJsonElement(
        ProcessSnapshot.serializer(),
        gw.request("processes", mapOf("limit" to JsonPrimitive(limit)), timeoutMs = 10_000, waitForConnectionMs = 5_000)!!,
    )

    suspend fun killProcess(pid: Int, force: Boolean, tree: Boolean): KillResult = json.decodeFromJsonElement(
        KillResult.serializer(),
        gw.request(
            "killProcess",
            mapOf(
                "pid" to JsonPrimitive(pid),
                "signal" to JsonPrimitive(if (force) "SIGKILL" else "SIGTERM"),
                "tree" to JsonPrimitive(tree),
            ),
            timeoutMs = 15_000,
            waitForConnectionMs = 10_000,
        )!!,
    )

    suspend fun reapOrphans(force: Boolean = false): ReapResult = json.decodeFromJsonElement(
        ReapResult.serializer(),
        gw.request(
            "reapOrphans",
            mapOf("signal" to JsonPrimitive(if (force) "SIGKILL" else "SIGTERM")),
            timeoutMs = 15_000,
            waitForConnectionMs = 10_000,
        )!!,
    )

    // ---------------------------------------------------------------- internals

    private fun logFor(sessionId: String): SessionLog = logs.getOrPut(sessionId) {
        SessionLog(readCached(cacheName(sessionId), ListSerializer(JsonObject.serializer())) ?: emptyList())
    }

    private fun flowFor(sessionId: String): MutableStateFlow<List<JsonObject>> =
        logFlows.getOrPut(sessionId) { MutableStateFlow(logFor(sessionId).events) }

    private fun deltaFor(sessionId: String): MutableStateFlow<String> =
        deltaFlows.getOrPut(sessionId) { MutableStateFlow("") }

    private fun publish(sessionId: String) {
        val log = logFor(sessionId)
        // Everything scrolled back through stays on screen; only the copy on
        // disk is bounded. Reopened later, the cache starts above seq 0, and
        // that alone says there is more to load.
        flowFor(sessionId).value = log.events
        save(cacheName(sessionId), ListSerializer(JsonObject.serializer()), log.newest(CACHE_KEEP))
    }

    private fun cacheName(sessionId: String) = "cache/$sessionId.json"

    private fun <T> readCached(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, File(dir, name).readText()) }.getOrNull()

    /** Debounced, atomic: a burst of events is one write, and a crash mid-write loses nothing. */
    private fun <T> save(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        saveJobs[name]?.cancel()
        saveJobs[name] = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            runCatching {
                val file = File(dir, name)
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(serializer, value))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    companion object {
        private const val SESSIONS_FILE = "sessions.json"
        private const val FEED_FILE = "feed.json"
        private const val REPLAY_LIMIT = 300
        private const val CACHE_KEEP = 500
        private const val FEED_KEEP = 600
        private const val HISTORY_PAGE = 200
        private const val SAVE_DEBOUNCE_MS = 400L
        val FEED_TYPES = setOf(
            "user_message", "assistant_text", "tool_use", "tool_result",
            "permission_request", "question_request", "result",
        )

        fun typeOf(stored: JsonObject): String? =
            (stored["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull

        fun clientIdOf(stored: JsonObject): String? =
            (stored["event"] as? JsonObject)?.get("clientMessageId")?.jsonPrimitive?.contentOrNull
    }
}
