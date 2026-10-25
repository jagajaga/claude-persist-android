package me.jagajaga.persist.ui.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Where each chat was left, so reopening it lands there: at the bottom if it
 * was at the bottom, else on the message that was at the top of the screen.
 */
object ScrollMemory {
    data class Place(val pinned: Boolean, val key: String?, val offset: Int)

    private val places = ConcurrentHashMap<String, Place>()

    fun get(sessionId: String): Place? = places[sessionId]

    fun put(sessionId: String, place: Place) {
        places[sessionId] = place
    }
}
