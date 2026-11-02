package me.jagajaga.persist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.jagajaga.persist.data.ServerStats
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.processes.ProcessesSection
import java.util.Locale

/** The server's CPU, memory, swap, load and disk, refreshed every two seconds while open. */
@Composable
fun StatsScreen(store: Store) {
    var stats by remember { mutableStateOf<ServerStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            // Only while the app is on screen: off screen this asked the server every 2 s.
            if (me.jagajaga.persist.notify.AppVisibility.visible) {
                runCatching { store.stats() }
                    .onSuccess { stats = it; error = null }
                    .onFailure { error = it.message }
            }
            delay(2000)
        }
    }
    Column(
        Modifier.fillMaxSize().background(Palette.page).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The process list comes regardless: on an overloaded host the stats
        // can time out while killing the culprit is exactly what is needed.
        val s = stats
        if (s == null) {
            Text(error ?: "Loading…", style = Type.small)
        } else {
            val sessions by store.sessions.collectAsState()
            Gauges(s, error, remember(sessions) { sessions.associate { it.id to it.title } })
        }
        ProcessesSection(store)
    }
}

@Composable
private fun Gauges(s: ServerStats, error: String?, titles: Map<String, String> = emptyMap()) {
    error?.let { Text("Last update failed: $it", style = Type.tiny.copy(color = Palette.warn)) }
    val cpu = s.cpuPercent
    Meter("CPU", cpu?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "…", "${s.cores} cores", (cpu ?: 0.0) / 100)
    Meter(
        "Load",
        s.load.joinToString("  ") { String.format(Locale.ROOT, "%.2f", it) },
        "1, 5, 15 min",
        if (s.cores > 0) s.load.firstOrNull()?.div(s.cores) ?: 0.0 else 0.0,
    )
    val usedMem = s.memory.totalBytes - s.memory.availableBytes
    Meter("Memory", "${bytes(usedMem)} of ${bytes(s.memory.totalBytes)}", "${bytes(s.memory.availableBytes)} available", ratio(usedMem, s.memory.totalBytes))
    if (s.swap.totalBytes > 0) {
        val usedSwap = s.swap.totalBytes - s.swap.freeBytes
        Meter("Swap", "${bytes(usedSwap)} of ${bytes(s.swap.totalBytes)}", "", ratio(usedSwap, s.swap.totalBytes))
    }
    s.disk?.let { d ->
        val used = d.totalBytes - d.freeBytes
        Meter("Disk", "${bytes(used)} of ${bytes(d.totalBytes)}", d.path, ratio(used, d.totalBytes))
    }
    s.tokens?.let { TokensCard(it, titles) }
    Card {
        Text("Uptime ${uptime(s.uptimeSeconds)}", style = Type.body)
        s.processes?.let { Text("$it processes", style = Type.small) }
    }
}

private fun ratio(part: Long, whole: Long): Double = if (whole > 0) part.toDouble() / whole else 0.0

private fun uptime(seconds: Double): String {
    val s = seconds.toLong()
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return if (d > 0) "${d}d ${h}h" else "${h}h ${m}m"
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Palette.border, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Meter(title: String, value: String, note: String, fraction: Double) {
    val f = fraction.coerceIn(0.0, 1.0).toFloat()
    val color = when {
        f > 0.9f -> Palette.error
        f > 0.75f -> Palette.warn
        else -> Palette.accent
    }
    Card {
        Row {
            Text(title, style = Type.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text(note, style = Type.tiny)
        }
        Text(value, style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFEDEDED), RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth(f).height(6.dp).background(color, RoundedCornerShape(3.dp)))
        }
    }
}

/**
 * Tokens through Claude, every chat together. "Processed" is the new work:
 * fresh input, cache writes and output. Cache reads -- the conversation read
 * again from cache at each step, typically nineteen in twenty of all tokens
 * -- are shown beside it, not folded in.
 */
@Composable
private fun TokensCard(t: me.jagajaga.persist.data.TokenFlow, titles: Map<String, String>) {
    Card {
        Row {
            Text("Tokens", style = Type.small.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text("${t.calls.lastMinute} calls/min · ${t.calls.lastHour} in the hour", style = Type.tiny)
        }
        Text("${compactCount(t.lastMinute.processed)}/min processed", style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium))
        TokenRow("Last minute", t.lastMinute)
        TokenRow("Last hour", t.lastHour)
        if (t.bySession.isNotEmpty()) {
            Text("Busiest chats, last 5 min", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold))
            for (s in t.bySession) {
                Row {
                    Text(titles[s.sessionId] ?: s.sessionId.take(8), style = Type.small.copy(color = Palette.text), maxLines = 1, modifier = Modifier.weight(1f))
                    Text(compactCount(s.processed), style = Type.small)
                }
            }
        }
    }
}

@Composable
private fun TokenRow(label: String, c: me.jagajaga.persist.data.TokenCounts) {
    Column {
        Text(label, style = Type.tiny)
        Text(
            "in ${compactCount(c.input)} · cache write ${compactCount(c.cacheWrite)} · out ${compactCount(c.output)} · cache read ${compactCount(c.cacheRead)}",
            style = Type.small.copy(color = Palette.text),
        )
    }
}
