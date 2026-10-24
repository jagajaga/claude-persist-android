package me.jagajaga.persist.ui.processes

import me.jagajaga.persist.data.KillFailure
import me.jagajaga.persist.data.KillResult
import me.jagajaga.persist.data.ProcInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessListTest {
    private fun p(pid: Int, cpu: Double?, rssMb: Long, cmd: String = "x", role: String? = null) =
        ProcInfo(pid = pid, cpuPercent = cpu, rssBytes = rssMb shl 20, command = cmd, role = role)

    @Test
    fun `busiest first, and an unmeasured process sorts below a measured idle one`() {
        val list = listOf(p(1, null, 900), p(2, 0.0, 10), p(3, 150.0, 5))
        assertEquals(listOf(3, 2, 1), ProcessList.sorted(list, ProcessSort.Cpu).map { it.pid })
    }

    @Test
    fun `biggest first counts swap too`() {
        val swapped = ProcInfo(pid = 4, rssBytes = 1L shl 20, swapBytes = 2000L shl 20)
        val list = listOf(p(1, 90.0, 900), swapped)
        assertEquals(listOf(4, 1), ProcessList.sorted(list, ProcessSort.Memory).map { it.pid })
    }

    @Test
    fun `a name is the script, not the interpreter`() {
        assertEquals("vite", ProcessList.name(p(1, 0.0, 1, "node /home/me/app/node_modules/.bin/vite --port 5199")))
        assertEquals("main.js", ProcessList.name(p(1, 0.0, 1, "/usr/bin/node --max-old-space-size=4096 /x/dist/main.js")))
        assertEquals("esbuild", ProcessList.name(p(1, 0.0, 1, "/x/esbuild --service=0.21")))
        assertEquals("[kworker/0:1]", ProcessList.name(p(1, 0.0, 1, "[kworker/0:1]")))
        assertEquals("Claude session", ProcessList.name(p(1, 0.0, 1, "/x/claude --resume=a", role = "claude-session")))
        assertEquals("pid 7", ProcessList.name(p(7, 0.0, 1, "")))
    }

    @Test
    fun `a kill is described in words`() {
        assertEquals("Signalled 2 processes", ProcessList.describe(KillResult(signalled = listOf(1, 2))))
        assertEquals(
            "Signalled 1 process, 1 already gone",
            ProcessList.describe(KillResult(signalled = listOf(1), failed = listOf(KillFailure(2, "ESRCH")))),
        )
        assertEquals("Not killing pid 38: code-server", ProcessList.describe(KillResult(refused = "Not killing pid 38: code-server")))
    }

    @Test
    fun `ages read like a person would say them`() {
        assertEquals("42s", ProcessList.age(42.0))
        assertEquals("5m", ProcessList.age(300.0))
        assertEquals("2h 3m", ProcessList.age(7380.0))
        assertEquals("3d 1h", ProcessList.age(3 * 86400.0 + 3600))
    }
}
