package me.jagajaga.persist.ui.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.jagajaga.persist.data.OutgoingMessage

/** What the chat screen draws, built from the raw events. */
sealed interface ThreadItem {
    val key: String

    /** [paths] parallels [images]: where each attachment is on the server, when known, so it can be opened. */
    data class User(
        override val key: String,
        val text: String,
        val images: List<String>,
        val sending: Boolean,
        val paths: List<String?> = emptyList(),
    ) : ThreadItem
    data class Assistant(override val key: String, val text: String, val agent: String?) : ThreadItem
    data class Tool(
        override val key: String,
        val name: String,
        val label: String,
        val input: String,
        val result: String?,
        val isError: Boolean,
        val agent: String?,
        /** Edit / MultiEdit / Write, as the panel's DIFF and NEW blocks. */
        val diff: List<DiffLine>? = null,
        /** TodoWrite's list, shown as a checklist. */
        val todos: List<Todo>? = null,
    ) : ThreadItem {
        val done: Boolean get() = result != null
    }
    data class Footer(override val key: String, val ts: Long, val durationMs: Long?, val tokens: Long?, val costUsd: Double?) : ThreadItem
    data class Permission(
        override val key: String,
        val requestId: String,
        val toolName: String,
        val detail: String,
        val resolved: Boolean,
        val allowed: Boolean?,
    ) : ThreadItem
    data class Question(
        override val key: String,
        val requestId: String,
        val questions: List<Q>,
        val resolved: Boolean,
        val answers: Map<String, String>,
    ) : ThreadItem
    data class Notice(override val key: String, val text: String, val error: Boolean) : ThreadItem
}

data class DiffLine(val sign: Char, val text: String)

data class Todo(val content: String, val status: String, val activeForm: String?)

data class Q(val question: String, val header: String, val multiSelect: Boolean, val options: List<Pair<String, String?>>)

object ThreadModel {
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The label a tool card shows after its name, as the panel shows it:
     * `Bash grep -rn ...`, `Read /path/to/file`, `Agent Map the app composer UI`.
     */
    fun toolLabel(name: String, input: JsonElement?): String {
        val obj = input as? JsonObject ?: return ""
        val preferred = when (name) {
            "Bash" -> obj.str("command")
            "Read", "Write", "Edit", "MultiEdit", "NotebookEdit" -> obj.str("file_path") ?: obj.str("notebook_path")
            "Grep" -> obj.str("pattern")
            "Glob" -> obj.str("pattern")
            "WebFetch" -> obj.str("url")
            "WebSearch" -> obj.str("query")
            "Agent", "Task" -> obj.str("description")
            "TodoWrite" -> "update todos"
            else -> null
        }
        val any = preferred ?: obj.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
        return (any ?: "").replace('\n', ' ').take(200)
    }

    /** What an edit changed, line by line: removals then additions, as the panel shows it. */
    fun diffOf(name: String, input: JsonElement?): List<DiffLine>? {
        val obj = input as? JsonObject ?: return null
        fun lines(text: String?, sign: Char) = text?.split('\n')?.map { DiffLine(sign, it) } ?: emptyList()
        return when (name) {
            "Edit" -> lines(obj.str("old_string"), '-') + lines(obj.str("new_string"), '+')
            "MultiEdit" -> (obj["edits"] as? JsonArray).orEmpty().flatMap { e ->
                val edit = e as? JsonObject ?: return@flatMap emptyList()
                lines(edit.str("old_string"), '-') + lines(edit.str("new_string"), '+') + DiffLine(' ', "")
            }.dropLast(1)
            "Write" -> lines(obj.str("content"), '+')
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    fun todosOf(name: String, input: JsonElement?): List<Todo>? {
        if (name != "TodoWrite") return null
        val list = ((input as? JsonObject)?.get("todos") as? JsonArray).orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            Todo(o.str("content") ?: return@mapNotNull null, o.str("status") ?: "pending", o.str("activeForm"))
        }
        return list.takeIf { it.isNotEmpty() }
    }

    /** When the turn now running began: your newest message. For the working row's clock. */
    fun turnStart(events: List<JsonObject>): Long? =
        events.lastOrNull { (it["event"] as? JsonObject)?.str("type") == "user_message" }
            ?.let { (it["ts"] as? JsonPrimitive)?.longOrNull }

    /** Permissions and questions still waiting on you, oldest first, with their place in the list. */
    fun waiting(items: List<ThreadItem>): List<Pair<Int, ThreadItem>> =
        items.withIndex().filter { (_, item) ->
            (item is ThreadItem.Permission && !item.resolved) || (item is ThreadItem.Question && !item.resolved)
        }.map { it.index to it.value }

    /** Every prompt you sent, newest first, with its place: the prompt bar's jump menu. */
    fun prompts(items: List<ThreadItem>): List<Pair<Int, String>> =
        items.withIndex().filter { it.value is ThreadItem.User && (it.value as ThreadItem.User).text.isNotBlank() }
            .map { it.index to (it.value as ThreadItem.User).text }
            .reversed()

    fun build(events: List<JsonObject>, outgoing: List<OutgoingMessage>): List<ThreadItem> {
        val items = mutableListOf<ThreadItem>()
        val toolIndex = HashMap<String, Int>()
        val permissionIndex = HashMap<String, Int>()
        val questionIndex = HashMap<String, Int>()
        val delivered = HashSet<String>()

        for (stored in events) {
            val event = stored["event"] as? JsonObject ?: continue
            val seq = (stored["seq"] as? JsonPrimitive)?.longOrNull ?: continue
            val ts = (stored["ts"] as? JsonPrimitive)?.longOrNull ?: 0
            val key = "e$seq"
            val agent = event.str("agent")
            when (event.str("type")) {
                "user_message" -> {
                    event.str("clientMessageId")?.let { delivered += it }
                    // A file's label is its path on the server; its name is what you picked.
                    val attachments = (event["attachments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    val images = attachments.mapNotNull { it.str("label")?.let(::attachmentName) }
                    val paths = attachments.filter { it.str("label") != null }.map { it.str("path") }
                    items += ThreadItem.User(key, event.str("text") ?: "", images, sending = false, paths = paths)
                }
                "assistant_text" -> items += ThreadItem.Assistant(key, event.str("text") ?: "", agent)
                "tool_use" -> {
                    val name = event.str("toolName") ?: "tool"
                    val input = event["input"]
                    val item = ThreadItem.Tool(
                        key, name, toolLabel(name, input), input?.toString() ?: "", null, false, agent,
                        diff = diffOf(name, input), todos = todosOf(name, input),
                    )
                    event.str("toolUseId")?.let { toolIndex[it] = items.size }
                    items += item
                }
                "tool_result" -> {
                    val id = event.str("toolUseId")
                    val at = id?.let { toolIndex[it] }
                    val summary = event.str("summary") ?: ""
                    val isError = (event["isError"] as? JsonPrimitive)?.booleanOrNull == true
                    if (at != null) {
                        val tool = items[at] as ThreadItem.Tool
                        items[at] = tool.copy(result = summary, isError = isError)
                    }
                }
                "result" -> items += ThreadItem.Footer(
                    key,
                    ts,
                    (event["durationMs"] as? JsonPrimitive)?.longOrNull,
                    (event["turnTokens"] as? JsonPrimitive)?.longOrNull,
                    (event["costUsd"] as? JsonPrimitive)?.doubleOrNull,
                )
                "permission_request" -> {
                    val requestId = event.str("requestId") ?: continue
                    val name = event.str("toolName") ?: "tool"
                    permissionIndex[requestId] = items.size
                    items += ThreadItem.Permission(key, requestId, name, toolLabel(name, event["input"]), false, null)
                }
                "question_request" -> {
                    val requestId = event.str("requestId") ?: continue
                    val questions = (event["questions"] as? JsonArray).orEmpty().mapNotNull { q ->
                        val o = q as? JsonObject ?: return@mapNotNull null
                        Q(
                            question = o.str("question") ?: "",
                            header = o.str("header") ?: "",
                            multiSelect = (o["multiSelect"] as? JsonPrimitive)?.booleanOrNull == true,
                            options = (o["options"] as? JsonArray).orEmpty().mapNotNull { opt ->
                                val oo = opt as? JsonObject ?: return@mapNotNull null
                                (oo.str("label") ?: return@mapNotNull null) to oo.str("description")
                            },
                        )
                    }
                    questionIndex[requestId] = items.size
                    items += ThreadItem.Question(key, requestId, questions, false, emptyMap())
                }
                "permission_resolved" -> {
                    val requestId = event.str("requestId") ?: continue
                    val allowed = (event["allowed"] as? JsonPrimitive)?.booleanOrNull
                    permissionIndex[requestId]?.let { at ->
                        items[at] = (items[at] as ThreadItem.Permission).copy(resolved = true, allowed = allowed)
                    }
                    questionIndex[requestId]?.let { at ->
                        val answers = (event["answers"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                        items[at] = (items[at] as ThreadItem.Question).copy(resolved = true, answers = answers)
                    }
                }
                "status" -> {
                    val status = event.str("status")
                    val detail = event.str("detail")
                    if (!detail.isNullOrBlank() && status == "error") items += ThreadItem.Notice(key, detail, true)
                    else if (!detail.isNullOrBlank()) items += ThreadItem.Notice(key, detail, false)
                }
            }
        }
        // Written on this phone, not yet confirmed: shown in place, marked as sending.
        for (message in outgoing) {
            if (message.clientMessageId in delivered) continue
            items += ThreadItem.User("o${message.clientMessageId}", message.text, message.images.map { it.name } + message.files.map { it.name }, sending = true)
        }
        return items
    }

    /** "01:18 · 8.4s · 1.2k tokens", the panel's turn footer. */
    fun footerText(item: ThreadItem.Footer, clock: (Long) -> String): String {
        val parts = mutableListOf<String>()
        if (item.ts > 0) parts += clock(item.ts)
        item.durationMs?.let { parts += String.format(java.util.Locale.ROOT, "%.1fs", it / 1000.0) }
        item.tokens?.let { parts += "${compact(it)} tokens" }
        return parts.joinToString(" · ")
    }

    fun compact(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0)
        else -> n.toString()
    }

    /** How full the context window is after the latest turn, 0..1; null before any turn says. */
    fun contextFill(events: List<JsonObject>): Double? {
        for (stored in events.asReversed()) {
            val event = stored["event"] as? JsonObject ?: continue
            if (event.str("type") != "result") continue
            val used = (event["contextTokens"] as? JsonPrimitive)?.doubleOrNull ?: continue
            val window = (event["contextWindow"] as? JsonPrimitive)?.doubleOrNull ?: continue
            if (window > 0) return (used / window).coerceIn(0.0, 1.0)
        }
        return null
    }

    /** The newest thing you asked, for the prompt bar at the top. */
    fun lastPrompt(items: List<ThreadItem>): String? =
        items.lastOrNull { it is ThreadItem.User && it.text.isNotBlank() }?.let { (it as ThreadItem.User).text }
}

/** "/…/uploads/<chat>/<uuid>-report.pdf" -> "report.pdf". */
internal fun attachmentName(label: String): String =
    label.substringAfterLast('/').replace(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-"), "")

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
