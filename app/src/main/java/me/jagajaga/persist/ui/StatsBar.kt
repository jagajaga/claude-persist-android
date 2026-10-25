package me.jagajaga.persist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import me.jagajaga.persist.data.ServerStats
import me.jagajaga.persist.data.Store
import java.util.Locale

/**
 * The server's pulse, always in view: CPU, memory and load in one thin line
 * across the top of every screen. Tap for the full Server view.
 */
@Composable
fun StatsBar(store: Store, onOpen: () -> Unit) {
    var stats by remember { mutableStateOf<ServerStats?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            // Only while the app is on screen: no point waking the radio for a line nobody sees.
            if (me.jagajaga.persist.notify.AppVisibility.visible) runCatching { store.stats() }.onSuccess { stats = it }
            delay(5_000)
        }
    }
    val s = stats ?: return
    val usedMem = s.memory.totalBytes - s.memory.availableBytes
    val memFrac = if (s.memory.totalBytes > 0) usedMem.toDouble() / s.memory.totalBytes else 0.0
    val cpu = s.cpuPercent ?: 0.0
    val loadFrac = if (s.cores > 0) (s.load.firstOrNull() ?: 0.0) / s.cores else 0.0
    Row(
        Modifier.fillMaxWidth().background(Palette.page).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Reading("CPU", String.format(Locale.ROOT, "%.0f%%", cpu), cpu / 100)
        Reading("RAM", "${gib(usedMem)}/${gib(s.memory.totalBytes)} GB", memFrac)
        Reading("load", String.format(Locale.ROOT, "%.1f", s.load.firstOrNull() ?: 0.0), loadFrac)
        if (s.swap.totalBytes > 0 && s.swap.totalBytes - s.swap.freeBytes > 0) {
            val used = s.swap.totalBytes - s.swap.freeBytes
            Reading("swap", "${gib(used)} GB", used.toDouble() / s.swap.totalBytes)
        }
    }
}

@Composable
private fun Reading(label: String, value: String, fraction: Double) {
    val color = when {
        fraction > 0.9 -> Palette.error
        fraction > 0.75 -> Palette.warn
        else -> Palette.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = Type.tiny.copy(fontSize = 11.sp, color = Palette.faint))
        Text(value, style = Type.tiny.copy(fontSize = 11.sp, color = if (color == Palette.muted) Palette.text else color))
    }
}

private fun gib(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0)
