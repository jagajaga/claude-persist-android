package me.jagajaga.persist.notify

/**
 * Which permission requests and questions are still waiting, per chat: a
 * chat's "needs you" notification stays up exactly while one does.
 */
class PendingRequests {
    private val waiting = HashMap<String, MutableSet<String>>()

    @Synchronized
    fun asked(sessionId: String, requestId: String) {
        waiting.getOrPut(sessionId) { mutableSetOf() } += requestId
    }

    /** True when this answer leaves the chat with nothing waiting: time to take its notification down. */
    @Synchronized
    fun answered(sessionId: String, requestId: String): Boolean {
        val set = waiting[sessionId] ?: return true
        set -= requestId
        if (set.isEmpty()) waiting -= sessionId
        return set.isEmpty()
    }

    @Synchronized
    fun forget(sessionId: String) {
        waiting -= sessionId
    }
}
