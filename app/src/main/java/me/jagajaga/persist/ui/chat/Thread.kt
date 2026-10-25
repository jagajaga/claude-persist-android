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

    data class User(override val key: String, val text: String, val images: List<String>, val sending: Boolean) : ThreadItem
    data class Assistant(override val key: String, val text: String, val agent: String?) : ThreadItem
    data class Tool(
        override val key: String,
        val name: String,
        val label: String,
        val input: String,
        val result: String?,
        val isError: Boolean,
        val agent: String?,
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
                    val images = (event["attachments"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonObject)?.str("label")?.let(::attachmentName) }
                    items += ThreadItem.User(key, event.str("text") ?: "", images, sending = false)
                }
                "assistant_text" -> items += ThreadItem.Assistant(key, event.str("text") ?: "", agent)
                "tool_use" -> {
                    val name = event.str("toolName") ?: "tool"
                    val input = event["input"]
                    val item = ThreadItem.Tool(key, name, toolLabel(name, input), input?.toString() ?: "", null, false, agent)
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

    /** The newest thing you asked, for the prompt bar at the top. */
    fun lastPrompt(items: List<ThreadItem>): String? =
        items.lastOrNull { it is ThreadItem.User && it.text.isNotBlank() }?.let { (it as ThreadItem.User).text }
}

/** "/…/uploads/<chat>/<uuid>-report.pdf" -> "report.pdf". */
internal fun attachmentName(label: String): String =
    label.substringAfterLast('/').replace(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-"), "")

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
