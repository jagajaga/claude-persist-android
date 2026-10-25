package me.jagajaga.persist.ui.chat

import java.util.concurrent.ConcurrentHashMap

/** What was typed into each chat and not sent, so swiping away and back keeps it. */
object Drafts {
    private val drafts = ConcurrentHashMap<String, String>()

    fun get(sessionId: String): String = drafts[sessionId] ?: ""

    fun put(sessionId: String, text: String) {
        if (text.isEmpty()) drafts.remove(sessionId) else drafts[sessionId] = text
    }
}
