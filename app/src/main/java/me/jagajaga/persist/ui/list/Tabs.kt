package me.jagajaga.persist.ui.list

/**
 * The chats you have open, like editor tabs: opened by you, closed by you,
 * in the order you opened them. What the strip shows and a swipe walks.
 */
object Tabs {
    /** Opening one already open just goes to it; a new one goes on the end. */
    fun open(tabs: List<String>, id: String): List<String> = if (id in tabs) tabs else tabs + id

    /**
     * Close [id]. Returns the tabs left and the one to show: closing the tab
     * in view moves to its right-hand neighbour, or the left one at the end,
     * or none when it was the last.
     */
    fun close(tabs: List<String>, id: String, current: String): Pair<List<String>, String?> {
        val at = tabs.indexOf(id)
        if (at < 0) return tabs to current
        val left = tabs - id
        if (id != current) return left to current
        return left to (left.getOrNull(at) ?: left.lastOrNull())
    }

    /** Chats deleted elsewhere drop out. */
    fun existing(tabs: List<String>, known: Set<String>): List<String> =
        if (known.isEmpty()) tabs else tabs.filter { it in known }
}
