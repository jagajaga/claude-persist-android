package me.jagajaga.persist.net

import kotlin.math.min
import kotlin.math.pow

/**
 * How long to wait before the next reconnect attempt.
 *
 * Exponential so a dead server is not hammered, capped so a phone that
 * regains signal reconnects within half a minute rather than after an ever
 * longer sleep, and jittered so a server restart is not followed by every
 * client reconnecting in the same instant. Any sign the network changed
 * (see GatewayClient.reconnectNow) skips the wait altogether: backoff is for
 * when nothing has changed, not for when the phone just walked out of a lift.
 */
class Backoff(
    private val baseMs: Long = 500,
    private val maxMs: Long = 30_000,
    private val random: () -> Double = Math::random,
) {
    private var attempt = 0

    /** The next delay; each call doubles the ceiling until the cap. */
    fun next(): Long {
        val ceiling = min(maxMs.toDouble(), baseMs * 2.0.pow(attempt))
        attempt = min(attempt + 1, 30)
        // Between half and all of the ceiling: never zero, never in lockstep.
        return (ceiling * (0.5 + 0.5 * random())).toLong()
    }

    /** A connection worked; the next failure starts from the bottom again. */
    fun reset() {
        attempt = 0
    }
}
