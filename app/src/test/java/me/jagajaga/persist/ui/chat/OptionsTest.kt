package me.jagajaga.persist.ui.chat

import me.jagajaga.persist.data.AccountInfo
import me.jagajaga.persist.data.AccountUsage
import me.jagajaga.persist.data.ModelDescriptor
import me.jagajaga.persist.data.RateWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionsTest {
    private val models = listOf(
        ModelDescriptor("default", "Default", resolvedModel = "claude-opus-5-5[1m]"),
        ModelDescriptor("opus", "Opus 5.5", resolvedModel = "claude-opus-5-5[1m]", effortLevels = listOf("low", "high", "max")),
        ModelDescriptor("sonnet", "Sonnet 5", resolvedModel = "claude-sonnet-5"),
    )

    @Test
    fun `nothing chosen names what is actually answering, not the word default`() {
        assertEquals("Default", Options.modelLabel(null, "claude-opus-5-5[1m]", models))
        assertEquals("claude-x", Options.modelLabel("", "claude-x", models))
        assertEquals("default", Options.modelLabel(null, null, models))
        assertEquals("Sonnet 5", Options.modelLabel("sonnet", "claude-opus-5-5[1m]", models))
    }

    @Test
    fun `the pill adds the effort when one is set`() {
        assertEquals("Sonnet 5 · high", Options.pill("sonnet", null, "high", models))
        assertEquals("Sonnet 5", Options.pill("sonnet", null, "", models))
    }

    @Test
    fun `model choices start with default and keep a chosen model the SDK dropped`() {
        val choices = Options.modelChoices(models, "retired-model", null)
        assertEquals(listOf("", "opus", "sonnet", "retired-model"), choices.map { it.first })
        assertEquals("default (Default)", Options.modelChoices(models, null, "claude-opus-5-5[1m]").first().second)
    }

    @Test
    fun `effort levels follow the model, the full set when it says nothing`() {
        assertEquals(listOf("", "low", "high", "max"), Options.effortChoices(models, "opus", null))
        assertEquals(listOf("") + Options.ALL_EFFORTS, Options.effortChoices(models, "sonnet", null))
        assertEquals(listOf("", "low", "high", "max", "xhigh"), Options.effortChoices(models, "opus", "xhigh"))
    }

    @Test
    fun `usage shows the window nearest its limit, and another account's age`() {
        val usage = AccountUsage(mapOf("five_hour" to RateWindow(42.4), "seven_day" to RateWindow(77.6)), at = 0)
        assertEquals("7d 78%", Options.usage(AccountInfo("work", active = true, lastUsage = usage), now = 0))
        assertEquals("7d 78% · 3h ago", Options.usage(AccountInfo("home", lastUsage = usage), now = 3 * 3_600_000L))
        assertNull(Options.usage(AccountInfo("new"), now = 0))
    }

    @Test
    fun `an account that cannot be used says why`() {
        assertEquals("a — not signed in, tap to sign in", Options.accountLabel(AccountInfo("a", signedIn = false)))
        assertEquals("b — login expired, tap to sign in", Options.accountLabel(AccountInfo("b", loginExpired = true)))
        assertEquals("c", Options.accountLabel(AccountInfo("c")))
    }

    @Test
    fun `the usage line lists every window that has a reading, in order`() {
        val windows = mapOf("seven_day" to RateWindow(21.2), "five_hour" to RateWindow(42.6), "overage" to RateWindow(null))
        assertEquals("5h 43% · 7d 21%", Options.usageLine(windows))
        assertNull(Options.usageLine(emptyMap()))
    }

    @Test
    fun `the top bar's Claude reading is the window nearest its limit`() {
        val windows = mapOf("five_hour" to RateWindow(42.0), "seven_day" to RateWindow(77.0))
        assertEquals("7d" to 77.0, Options.nearest(windows))
        assertNull(Options.nearest(emptyMap()))
    }

    @Test
    fun `each window shows its use and when it resets, a past reset dropped`() {
        val utc = java.time.ZoneId.of("UTC")
        val now = java.time.Instant.parse("2026-11-25T17:30:00Z").toEpochMilli()
        val acct = AccountInfo(
            "serokell",
            lastUsage = me.jagajaga.persist.data.AccountUsage(
                mapOf(
                    "five_hour" to me.jagajaga.persist.data.RateWindow(100.0, resetsAt = java.time.Instant.parse("2026-11-25T21:59:00Z").toEpochMilli()),
                    "seven_day" to me.jagajaga.persist.data.RateWindow(33.4, resetsAt = java.time.Instant.parse("2026-11-26T10:59:00Z").toEpochMilli()),
                    "seven_day_opus" to me.jagajaga.persist.data.RateWindow(80.0, resetsAt = java.time.Instant.parse("2026-11-28T19:00:00Z").toEpochMilli()),
                    "seven_day_sonnet" to me.jagajaga.persist.data.RateWindow(10.0, resetsAt = now - 1),
                ),
                at = now,
            ),
        )
        val lines = Options.limitLines(acct, now, utc)
        assertEquals(
            listOf(
                Options.LimitLine("5h", 100, "resets today 21:59", true),
                Options.LimitLine("7d", 33, "resets tomorrow 10:59", false),
                Options.LimitLine("7d opus", 80, "resets Sat 28 Nov, 19:00", false),
                Options.LimitLine("7d sonnet", 10, null, false),
            ),
            lines,
        )
    }
}
