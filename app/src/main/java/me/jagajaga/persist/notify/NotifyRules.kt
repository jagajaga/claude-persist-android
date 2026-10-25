package me.jagajaga.persist.notify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.jagajaga.persist.ui.chat.ThreadModel

/** Which events are worth a notification, and what it says. Kept apart from Android so it can be tested. */
object NotifyRules {
    const val ATTENTION = "attention"
    const val DONE = "done"

    data class Notice(val channel: String, val title: String, val text: String)

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * Claude waiting on you is loud; a turn finishing is quiet. Nothing else
     * notifies -- a phone buzzing at every tool call would be switched off
     * within the hour.
     */
    fun notice(chatTitle: String, stored: JsonObject): Notice? {
        val event = stored["event"] as? JsonObject ?: return null
        val title = chatTitle.ifBlank { "Claude" }
        return when (event.str("type")) {
            "permission_request" -> {
                val tool = event.str("toolName") ?: "a tool"
                val detail = ThreadModel.toolLabel(tool, event["input"]).take(140)
                Notice(ATTENTION, title, "Allow $tool?" + if (detail.isNotBlank()) " $detail" else "")
            }
            "question_request" -> {
                val q = ((event["questions"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("question")
                Notice(ATTENTION, title, q ?: "Claude asked you a question")
            }
            "result" -> {
                val summary = event.str("summary")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200)
                Notice(DONE, title, summary ?: "Finished")
            }
            else -> null
        }
    }
}
