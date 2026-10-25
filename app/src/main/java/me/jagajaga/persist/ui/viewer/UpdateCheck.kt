package me.jagajaga.persist.ui.viewer

object UpdateCheck {
    /** "0.1.20" newer than "0.1.9"? By number, part by part; a dev build is older than any release. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(installed) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String): List<Int>? {
        val core = v.trim().removePrefix("v")
        if (core.contains('-')) return listOf(-1) // "0.1.0-dev": older than anything released
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }
}
