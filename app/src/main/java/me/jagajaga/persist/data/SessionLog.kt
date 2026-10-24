package me.jagajaga.persist.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * One chat's events as this phone knows them, ordered by sequence number.
 *
 * The daemon numbers every event, and a reconnecting client asks for
 * everything from the last number it has. On a bad network that means the
 * same event can arrive twice -- once live, once again in the replay after a
 * drop -- and a replay can arrive while live pushes are still landing. Merging
 * by sequence number makes all of that harmless: an event is kept once, in
 * its place, however many times and in whatever order it shows up.
 */
class SessionLog(initial: List<JsonObject> = emptyList()) {
    private val bySeq = java.util.TreeMap<Int, JsonObject>()

    init {
        merge(initial)
    }

    /** Every event, oldest first. */
    val events: List<JsonObject> get() = bySeq.values.toList()

    /** The newest sequence number held, or -1 when nothing is. */
    val lastSeq: Int get() = if (bySeq.isEmpty()) -1 else bySeq.lastKey()

    /** The oldest held, for "load earlier". */
    val firstSeq: Int get() = if (bySeq.isEmpty()) -1 else bySeq.firstKey()

    /**
     * Where to resume from: the first number not yet held. A replay asks the
     * daemon for events at or after this.
     */
    val resumeFrom: Int get() = lastSeq + 1

    /** Add events; returns true when anything new arrived. */
    fun merge(incoming: List<JsonObject>): Boolean {
        var changed = false
        for (event in incoming) {
            val seq = seqOf(event) ?: continue
            if (!bySeq.containsKey(seq)) {
                bySeq[seq] = event
                changed = true
            }
        }
        return changed
    }

    /** Drop all but the newest [keep]; bounds what is cached on the phone. */
    fun trimTo(keep: Int) {
        while (bySeq.size > keep) bySeq.pollFirstEntry()
    }

    companion object {
        fun seqOf(event: JsonObject): Int? =
            runCatching { event["seq"]?.jsonPrimitive?.int }.getOrNull()
    }
}
