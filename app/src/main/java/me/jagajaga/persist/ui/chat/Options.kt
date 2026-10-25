package me.jagajaga.persist.ui.chat

import me.jagajaga.persist.data.AccountInfo
import me.jagajaga.persist.data.ModelDescriptor
import kotlin.math.roundToInt

/** The model menu's wording and choices, as the editor panel words them. */
object Options {
    val ALL_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
    private val WINDOW_ORDER = listOf("five_hour", "seven_day", "seven_day_opus", "seven_day_sonnet", "seven_day_overage_included", "overage")
    private val WINDOW_LABELS = mapOf(
        "five_hour" to "5h",
        "seven_day" to "7d",
        "seven_day_opus" to "7d opus",
        "seven_day_sonnet" to "7d sonnet",
        "seven_day_overage_included" to "7d overage",
        "overage" to "overage",
    )

    /** A resolved model id as the SDK names it, else the id itself. */
    fun nameFor(resolved: String, models: List<ModelDescriptor>): String =
        models.firstOrNull { it.resolvedModel == resolved || it.value == resolved }?.displayName ?: resolved

    /**
     * No model chosen is not "the default model": a resumed chat carries on with
     * whatever its transcript recorded. When the SDK has said what is really
     * answering, that is the name shown.
     */
    fun modelLabel(chosen: String?, active: String?, models: List<ModelDescriptor>): String {
        if (chosen.isNullOrEmpty()) return active?.let { nameFor(it, models) } ?: "default"
        return models.firstOrNull { it.value == chosen }?.displayName ?: chosen
    }

    fun pill(chosen: String?, active: String?, effort: String?, models: List<ModelDescriptor>): String =
        modelLabel(chosen, active, models) + (effort?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")

    /** value to label; "" is the default. A chosen model the SDK no longer lists stays visible. */
    fun modelChoices(models: List<ModelDescriptor>, chosen: String?, active: String?): List<Pair<String, String>> {
        val defaultLabel = if (chosen.isNullOrEmpty() && active != null) "default (${nameFor(active, models)})" else "default"
        val list = listOf("" to defaultLabel) + models.filter { it.value != "default" }.map { it.value to it.displayName }
        return if (!chosen.isNullOrEmpty() && list.none { it.first == chosen }) list + (chosen to chosen) else list
    }

    /** The levels the chosen model supports, the full set when unknown; "" is the default. */
    fun effortChoices(models: List<ModelDescriptor>, chosenModel: String?, effort: String?): List<String> {
        val levels = models.firstOrNull { it.value == (chosenModel ?: "") }?.effortLevels?.takeIf { it.isNotEmpty() } ?: ALL_EFFORTS
        val withCurrent = if (!effort.isNullOrEmpty() && effort !in levels) levels + effort else levels
        return listOf("") + withCurrent
    }

    /** "5h 42%": the window nearest its limit. Another account's reading says how old it is. */
    fun usage(account: AccountInfo, now: Long): String? {
        val reading = account.lastUsage ?: return null
        var best: Pair<String, Double>? = null
        for (kind in WINDOW_ORDER) {
            val u = reading.windows[kind]?.utilization ?: continue
            if (best == null || u > best.second) best = kind to u
        }
        val (kind, utilization) = best ?: return null
        val label = "${WINDOW_LABELS[kind] ?: kind} ${utilization.roundToInt()}%"
        return if (account.active) label else "$label · ${age(reading.at, now)}"
    }

    /** "5h 42% · 7d 21%": every window with a reading, as the panel's status bar tooltip lists them. */
    fun usageLine(windows: Map<String, me.jagajaga.persist.data.RateWindow>): String? =
        WINDOW_ORDER.mapNotNull { kind -> windows[kind]?.utilization?.let { "${WINDOW_LABELS[kind]} ${it.roundToInt()}%" } }
            .takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** What an account row says when it cannot be used as it stands. */
    fun accountLabel(account: AccountInfo): String = when {
        !account.signedIn -> "${account.name} — not signed in"
        account.loginExpired -> "${account.name} — login expired"
        else -> account.name
    }

    fun age(at: Long, now: Long): String {
        val minutes = ((now - at).coerceAtLeast(0) / 60_000.0).roundToInt()
        if (minutes < 2) return "just now"
        if (minutes < 60) return "${minutes}m ago"
        val hours = (minutes / 60.0).roundToInt()
        if (hours < 24) return "${hours}h ago"
        return "${(hours / 24.0).roundToInt()}d ago"
    }
}
