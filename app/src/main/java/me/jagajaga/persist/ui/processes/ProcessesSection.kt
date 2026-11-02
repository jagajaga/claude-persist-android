package me.jagajaga.persist.ui.processes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.jagajaga.persist.data.ProcInfo
import me.jagajaga.persist.data.ProcessSnapshot
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.bytes
import java.util.Locale

private const val POLL_MS = 3_000L
private const val SHOWN = 25

/**
 * What is eating the server, and the means to stop it.
 *
 * Built for a machine on its knees: the list keeps showing the last good
 * reading, labelled with its age, rather than blanking while a request times
 * out; and every kill is confirmed, then followed by a fresh look so the
 * screen shows what actually happened.
 */
@Composable
fun ProcessesSection(store: Store) {
    var snapshot by remember { mutableStateOf<ProcessSnapshot?>(null) }
    var lastOkAt by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(ProcessSort.Cpu) }
    var showAll by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ProcInfo?>(null) }
    var confirmReap by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refreshTick by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        runCatching { store.processes() }
            .onSuccess { snapshot = it; lastOkAt = System.currentTimeMillis(); error = null }
            .onFailure { error = it.message ?: it.javaClass.simpleName }
    }
    LaunchedEffect(refreshTick) {
        while (true) {
            if (me.jagajaga.persist.notify.AppVisibility.visible) refresh()
            delay(POLL_MS)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Processes", style = Type.title.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Chip("CPU", sort == ProcessSort.Cpu) { sort = ProcessSort.Cpu }
            Spacer(Modifier.width(6.dp))
            Chip("Memory", sort == ProcessSort.Memory) { sort = ProcessSort.Memory }
        }
        val snap = snapshot
        val staleSeconds = if (lastOkAt > 0) (now - lastOkAt) / 1000 else 0
        when {
            snap == null -> Text(error?.let { "Could not list processes: $it" } ?: "Loading…", style = Type.small)
            staleSeconds > 2 * POLL_MS / 1000 -> Text(
                "As of ${staleSeconds}s ago${error?.let { " — $it" } ?: ""}",
                style = Type.tiny.copy(color = Palette.warn),
            )
            else -> Text("${snap.total} running", style = Type.tiny)
        }
        message?.let { Text(it, style = Type.small.copy(color = Palette.text)) }

        if (snap != null && snap.orphans.count > 0) {
            Card(highlight = true) {
                Text(
                    "${snap.orphans.count} orphaned process${if (snap.orphans.count == 1) "" else "es"} holding ${bytes(snap.orphans.rssBytes)}",
                    style = Type.body.copy(fontWeight = FontWeight.Medium),
                )
                Text("Their working directory was deleted — usually dev servers from a removed worktree.", style = Type.small)
                Button(
                    onClick = { confirmReap = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.warn),
                ) { Text("Kill orphans (frees ${bytes(snap.orphans.rssBytes)})") }
            }
        }

        if (snap != null) {
            val rows = ProcessList.sorted(snap.processes, sort)
            Card {
                (if (showAll) rows else rows.take(SHOWN)).forEachIndexed { i, p ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
                    ProcessRow(p) { selected = p }
                }
                if (rows.size > SHOWN) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show fewer" else "Show all ${rows.size}", style = Type.small.copy(color = Palette.accent))
                    }
                }
            }
        }
    }

    selected?.let { chosen ->
        // Follow the live reading of the same pid, so the dialog is not frozen in time.
        val p = snapshot?.processes?.firstOrNull { it.pid == chosen.pid } ?: chosen
        KillDialog(
            p = p,
            busy = busy,
            onDismiss = { selected = null },
            onKill = { force, tree ->
                busy = true
                scope.launch {
                    message = runCatching { store.killProcess(p.pid, force, tree) }
                        .fold({ ProcessList.describe(it) }, { "Kill not confirmed: ${it.message} — check the list before trying again" })
                    busy = false
                    selected = null
                    refreshTick++
                }
            },
        )
    }

    if (confirmReap) {
        val snap = snapshot
        AlertDialog(
            onDismissRequest = { confirmReap = false },
            title = { Text("Kill orphaned processes?") },
            text = {
                Text(
                    "Sends SIGTERM to ${snap?.orphans?.count ?: 0} processes whose working directory no longer exists, " +
                        "freeing about ${bytes(snap?.orphans?.rssBytes ?: 0)}.",
                    style = Type.body,
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        message = runCatching { store.reapOrphans() }.fold(
                            { r -> "Stopped ${r.reaped.sumOf { it.signalled.size }} processes, about ${bytes(r.freedBytes)} freed" },
                            { "Not confirmed: ${it.message} — check the list before trying again" },
                        )
                        busy = false
                        confirmReap = false
                        refreshTick++
                    }
                }) { Text("Kill them", color = Palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReap = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProcessRow(p: ProcInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    ProcessList.name(p),
                    style = Type.body.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (p.orphaned) Badge("orphan", Palette.warn)
                if (p.isProtected) Badge("protected", Palette.muted)
                else if (p.role == "claude-session") Badge("chat", Palette.accent)
            }
            Text(
                buildString {
                    append(p.user).append(" · ").append(p.pid).append(" · ").append(ProcessList.age(p.ageSeconds))
                    if (p.descendants > 0) append(" · +").append(p.descendants).append(" children")
                },
                style = Type.tiny,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(p.cpuPercent?.let { String.format(Locale.ROOT, "%.0f%%", it) } ?: "…", style = Type.body.copy(color = cpuColor(p.cpuPercent)))
            Text(bytes(p.rssBytes + p.swapBytes), style = Type.tiny)
        }
    }
}

@Composable
private fun KillDialog(p: ProcInfo, busy: Boolean, onDismiss: () -> Unit, onKill: (force: Boolean, tree: Boolean) -> Unit) {
    var tree by remember(p.pid) { mutableStateOf(p.descendants > 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${ProcessList.name(p)}  ·  pid ${p.pid}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectionContainer { Text(p.command, style = Type.code, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                Detail("CPU", p.cpuPercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "measuring…")
                Detail("Memory", "${bytes(p.rssBytes)}${if (p.swapBytes > 0) " + ${bytes(p.swapBytes)} swap" else ""}")
                if (p.descendants > 0) Detail("With children", "${bytes(p.treeRssBytes)} in ${p.descendants + 1} processes")
                Detail("User", p.user)
                Detail("Running for", ProcessList.age(p.ageSeconds))
                p.cwd?.let { Detail("Directory", if (p.orphaned) "$it (deleted)" else it) }
                if (p.isProtected) {
                    Text("Can't be killed from here: ${p.protectedReason ?: "protected"}.", style = Type.small.copy(color = Palette.warn))
                } else if (p.descendants > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { tree = !tree }) {
                        Checkbox(checked = tree, onCheckedChange = { tree = it })
                        Text("Include its ${p.descendants} children", style = Type.body)
                    }
                }
            }
        },
        confirmButton = {
            if (!p.isProtected) {
                Row {
                    TextButton(enabled = !busy, onClick = { onKill(false, tree) }) { Text("Stop") }
                    TextButton(enabled = !busy, onClick = { onKill(true, tree) }) { Text("Force kill", color = Palette.error) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (p.isProtected) "Close" else "Cancel") } },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, style = Type.small, modifier = Modifier.width(110.dp))
        Text(value, style = Type.small.copy(color = Palette.text))
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        style = Type.tiny.copy(color = color, fontWeight = FontWeight.Medium),
        modifier = Modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = Type.small.copy(color = if (on) Palette.surface else Palette.muted, fontWeight = FontWeight.Medium),
        modifier = Modifier
            .background(if (on) Palette.accent else Palette.surface, RoundedCornerShape(12.dp))
            .border(1.dp, if (on) Palette.accent else Palette.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Card(highlight: Boolean = false, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (highlight) Palette.warn else Palette.border, RoundedCornerShape(12.dp))
            .background(Palette.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

private fun cpuColor(cpu: Double?): Color = when {
    cpu == null -> Palette.faint
    cpu >= 90 -> Palette.error
    cpu >= 40 -> Palette.warn
    else -> Palette.text
}
