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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.longOrNull
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

    private val _agents = MutableStateFlow<Map<String, List<ActiveAgent>>>(emptyMap())
    /** Each chat's working subagents, as the daemon last said. */
    val agents: StateFlow<Map<String, List<ActiveAgent>>> = _agents

    private val _usage = MutableStateFlow<UsageSnapshot?>(null)
    /** Plan limits, account-wide, live. */
    val usage: StateFlow<UsageSnapshot?> = _usage

    private val viewedSerializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), Long.serializer())
    private val viewedFile = File(dir, "viewed.json")
    private val _viewed = MutableStateFlow(
        runCatching { json.decodeFromString(viewedSerializer, viewedFile.readText()) }.getOrNull() ?: emptyMap(),
    )
    /** When each chat was last on screen here: newer activity is unread. */
    val viewed: StateFlow<Map<String, Long>> = _viewed


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
        runCatching { _usage.value = json.decodeFromJsonElement(UsageSnapshot.serializer(), gw.request("listRateLimits")!!) }
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
            "agents" -> {
                val sessionId = message["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
                val list = runCatching { json.decodeFromJsonElement(ListSerializer(ActiveAgent.serializer()), message["agents"]!!) }.getOrNull() ?: return
                _agents.value = _agents.value + (sessionId to list)
            }
            "rateLimits" -> runCatching { _usage.value = json.decodeFromJsonElement(UsageSnapshot.serializer(), message["usage"]!!) }
        }
    }

    // ---------------------------------------------------------------- chats

    fun markViewed(sessionId: String) {
        _viewed.value = _viewed.value + (sessionId to System.currentTimeMillis())
        save("viewed.json", viewedSerializer, _viewed.value)
    }

    suspend fun rename(sessionId: String, title: String) {
        gw.request("renameSession", mapOf("sessionId" to JsonPrimitive(sessionId), "title" to JsonPrimitive(title)))
        refreshSessions()
    }

    suspend fun delete(sessionId: String) {
        gw.request("deleteSession", mapOf("sessionId" to JsonPrimitive(sessionId)))
        refreshSessions()
    }

    suspend fun stopAgent(sessionId: String, taskId: String) {
        gw.request("stopAgent", mapOf("sessionId" to JsonPrimitive(sessionId), "taskId" to JsonPrimitive(taskId)))
    }

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
        markViewed(sessionId)
        scope.launch { runCatching { attach(sessionId) } }
    }

    fun closeChat(sessionId: String) {
        open.remove(sessionId)
        markViewed(sessionId)
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
    fun send(sessionId: String, text: String, images: List<OutgoingImage> = emptyList(), files: List<OutgoingFile> = emptyList()) {
        outbox.add(sessionId, text, images, files)
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
                val ready = uploadFiles(message)
                gw.request("sendMessage", sendParams(ready))
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

    /**
     * Put every file of [message] on the server, resuming each from wherever
     * the server says it got to; returns the message with every path filled.
     */
    private suspend fun uploadFiles(message: OutgoingMessage): OutgoingMessage {
        var current = message
        for (file in message.files) {
            if (file.uploadedPath != null) continue
            // Its copy is gone (storage cleared): send the rest rather than block the queue forever.
            if (!File(file.localPath).exists()) continue
            val path = uploadFile(message.sessionId, file)
            outbox.fileUploaded(message.clientMessageId, file.uploadId, path)
            current = current.copy(files = current.files.map { if (it.uploadId == file.uploadId) it.copy(uploadedPath = path) else it })
        }
        return current
    }

    private suspend fun uploadFile(sessionId: String, file: OutgoingFile): String {
        val local = File(file.localPath)
        val size = local.length()
        var offset = 0L
        var stalls = 0
        java.io.RandomAccessFile(local, "r").use { raf ->
            while (true) {
                val length = minOf(UPLOAD_CHUNK.toLong(), size - offset).toInt().coerceAtLeast(0)
                val bytes = ByteArray(length)
                raf.seek(offset)
                raf.readFully(bytes)
                val done = offset + length >= size
                val answer = gw.request(
                    "uploadChunk",
                    mapOf(
                        "sessionId" to JsonPrimitive(sessionId),
                        "uploadId" to JsonPrimitive(file.uploadId),
                        "name" to JsonPrimitive(file.name),
                        "offset" to JsonPrimitive(offset),
                        "data" to JsonPrimitive(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)),
                        "done" to JsonPrimitive(done),
                    ),
                    timeoutMs = 60_000,
                )?.jsonObject ?: throw IllegalStateException("no answer to an upload")
                answer["path"]?.jsonPrimitive?.contentOrNull?.let { return it }
                val received = answer["received"]?.jsonPrimitive?.longOrNull ?: throw IllegalStateException("bad upload answer")
                // The server says where to continue: after this chunk, or back
                // where it actually got to if an earlier one was lost.
                if (received <= offset) stalls++ else stalls = 0
                // Not a refusal, so not a GatewayException (which drops the message): tried again on the next connect.
                if (stalls > 3) throw IllegalStateException("upload of ${file.name} is not moving")
                offset = received
            }
        }
    }

    private fun sendParams(message: OutgoingMessage): Map<String, JsonElement> = buildMap {
        put("sessionId", JsonPrimitive(message.sessionId))
        put("text", JsonPrimitive(message.text))
        put("clientMessageId", JsonPrimitive(message.clientMessageId))
        val attachments = message.images.map { image ->
            buildJsonObject {
                put("kind", "image")
                put("name", image.name)
                put("mediaType", image.mediaType)
                put("data", image.base64)
            }
        } + message.files.mapNotNull { file ->
            file.uploadedPath?.let { path -> buildJsonObject { put("kind", "file"); put("path", path) } }
        }
        if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments))
    }

    /** Where a picked file is copied before it is sent; the outbox points here. */
    val outgoingFilesDir: File get() = File(dir, "outgoing-files").apply { mkdirs() }

    // ---------------------------------------------------------------- model, effort, account

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models
    private val _accounts = MutableStateFlow<List<AccountInfo>>(emptyList())
    val accounts: StateFlow<List<AccountInfo>> = _accounts

    /** What the model menu needs; called when it opens. */
    suspend fun refreshOptions() {
        runCatching {
            _models.value = json.decodeFromJsonElement(ListSerializer(ModelDescriptor.serializer()), gw.request("listModels", waitForConnectionMs = 5_000)!!)
        }
        runCatching {
            _accounts.value = json.decodeFromJsonElement(ListSerializer(AccountInfo.serializer()), gw.request("listAccounts", waitForConnectionMs = 5_000)!!)
        }
    }

    /** null leaves a setting alone; "" resets it to the default. */
    suspend fun setSessionOptions(sessionId: String, model: String? = null, effort: String? = null) {
        val params = buildMap<String, JsonElement> {
            put("sessionId", JsonPrimitive(sessionId))
            model?.let { put("model", if (it.isEmpty()) JsonNull else JsonPrimitive(it)) }
            effort?.let { put("effort", if (it.isEmpty()) JsonNull else JsonPrimitive(it)) }
        }
        gw.request("setSessionOptions", params)
        refreshSessions()
    }

    suspend fun setPermissionMode(sessionId: String, mode: String) {
        gw.request("setPermissionMode", mapOf("sessionId" to JsonPrimitive(sessionId), "mode" to JsonPrimitive(mode)))
        refreshSessions()
    }

    suspend fun setAccount(configDir: String?) {
        val result = gw.request("setAccount", mapOf("configDir" to (configDir?.let { JsonPrimitive(it) } ?: JsonNull)))
        result?.let { _accounts.value = json.decodeFromJsonElement(ListSerializer(AccountInfo.serializer()), it) }
    }

    suspend fun startLogin(name: String): LoginStarted =
        json.decodeFromJsonElement(LoginStarted.serializer(), gw.request("startLogin", mapOf("name" to JsonPrimitive(name)))!!)

    suspend fun submitLoginCode(loginId: String, code: String): LoginResult {
        val result = json.decodeFromJsonElement(
            LoginResult.serializer(),
            gw.request("submitLoginCode", mapOf("loginId" to JsonPrimitive(loginId), "code" to JsonPrimitive(code)), timeoutMs = 60_000)!!,
        )
        refreshOptions()
        return result
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

    /** The newest app release; with [download], fetched to the server first so the phone can take it. */
    suspend fun appRelease(download: Boolean): AppRelease = json.decodeFromJsonElement(
        AppRelease.serializer(),
        gw.request("appRelease", mapOf("download" to JsonPrimitive(download)), timeoutMs = 180_000, waitForConnectionMs = 10_000)!!,
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
        /** Raw bytes per upload chunk: a few seconds each on a poor link, so a drop costs little. */
        private const val UPLOAD_CHUNK = 256 * 1024
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
