package me.jagajaga.persist.ui.processes

import me.jagajaga.persist.data.KillResult
import me.jagajaga.persist.data.ProcInfo

enum class ProcessSort { Cpu, Memory }

/** What the list shows, kept out of the composables so it can be tested. */
object ProcessList {
    /** Busiest or biggest first. A process not yet measured sorts below every measured one. */
    fun sorted(processes: List<ProcInfo>, by: ProcessSort): List<ProcInfo> = when (by) {
        ProcessSort.Cpu -> processes.sortedWith(
            compareByDescending<ProcInfo> { it.cpuPercent ?: -1.0 }.thenByDescending { it.rssBytes },
        )
        ProcessSort.Memory -> processes.sortedWith(
            compareByDescending<ProcInfo> { it.rssBytes + it.swapBytes }.thenByDescending { it.cpuPercent ?: -1.0 },
        )
    }

    /**
     * A name a person recognises: "vite" rather than "/usr/bin/node". For an
     * interpreter, the script it runs is the interesting part.
     */
    fun name(p: ProcInfo): String {
        if (p.role == "claude-session") return "Claude session"
        val words = p.command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "pid ${p.pid}"
        // A kernel thread, "[kworker/0:1]": the slash is part of its name.
        if (words[0].startsWith("[")) return words.joinToString(" ")
        val first = words[0].substringAfterLast('/')
        if (first in INTERPRETERS) {
            val script = words.drop(1).firstOrNull { !it.startsWith("-") } ?: return first
            return script.trimEnd('/').substringAfterLast('/').let { if (it.isEmpty()) first else it }
        }
        return first
    }

    /** One line saying what a kill did, for a toast-like banner. */
    fun describe(r: KillResult): String {
        r.refused?.let { return it }
        val parts = mutableListOf<String>()
        if (r.signalled.isNotEmpty()) parts += "Signalled ${r.signalled.size} process${if (r.signalled.size == 1) "" else "es"}"
        val gone = r.failed.count { it.error == "ESRCH" }
        if (gone > 0) parts += "$gone already gone"
        val other = r.failed.filter { it.error != "ESRCH" }
        if (other.isNotEmpty()) parts += other.joinToString { "${it.pid}: ${it.error}" }
        return parts.joinToString(", ").ifEmpty { "Nothing to kill" }
    }

    fun age(seconds: Double): String {
        val s = seconds.toLong()
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86400}d ${(s % 86400) / 3600}h"
        }
    }

    private val INTERPRETERS = setOf("node", "python", "python3", "bun", "deno", "ruby", "perl", "sh", "bash", "npx", "npm")
}
