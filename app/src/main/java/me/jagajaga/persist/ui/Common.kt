package me.jagajaga.persist.ui

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import me.jagajaga.persist.net.ConnectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the connection is doing, said plainly, whenever it is not simply up.
 *
 * On a bad network the honest answer is the useful one: "reconnecting in 4s,
 * 2 messages queued" tells you your words are safe and when to look again;
 * a spinner tells you nothing.
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    queued: Int,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    sending: me.jagajaga.persist.data.Store.SendStatus = me.jagajaga.persist.data.Store.SendStatus(),
    onSendNow: () -> Unit = {},
) {
    if (state is ConnectionState.Online && queued == 0) return
    // Reconnecting on opening the app takes about a second: not worth a
    // banner flashing up every time. Shown if it takes longer.
    var patient by remember { mutableStateOf(state !is ConnectionState.Connecting) }
    LaunchedEffect(state is ConnectionState.Connecting) {
        if (state is ConnectionState.Connecting) {
            delay(2_000)
        }
        patient = true
    }
    if (!patient && queued == 0) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val queuedText = if (queued > 0) " · $queued queued" else ""
    val (text, color, action) = when (state) {
        // Online with messages waiting: say what the last try ran into, and offer to try now.
        is ConnectionState.Online -> {
            val last = when {
                sending.trying -> "sending now"
                sending.lastTryAt == 0L -> "waiting"
                else -> "last try ${((now - sending.lastTryAt) / 1000).coerceAtLeast(0)}s ago" + (sending.lastError?.let { ": $it" } ?: "")
            }
            Triple("$queued queued · $last", if (sending.lastError != null) Color(0xFFFFF4E5) else Palette.accentSoft, "Send now" to onSendNow)
        }
        is ConnectionState.Connecting -> Triple("Connecting…$queuedText", Palette.accentSoft, null)
        is ConnectionState.Offline -> {
            val secs = ((state.retryAtMs - now) / 1000).coerceAtLeast(0)
            Triple("Offline — retrying in ${secs}s$queuedText", Color(0xFFFFF4E5), "Retry now" to onRetry)
        }
        is ConnectionState.NeedsLogin -> Triple("Login expired$queuedText", Palette.errorSoft, "Log in" to onLogin)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(color)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = Type.small.copy(color = Palette.text), maxLines = 3, modifier = Modifier.weight(1f, fill = false))
        if (action != null) {
            Text(
                action.first,
                style = Type.small.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable { action.second() }.padding(start = 12.dp),
            )
        }
    }
}

/** Green when idle and done, pulsing blue while working, red on error. */
@Composable
fun StatusDot(working: Boolean, error: Boolean = false, modifier: Modifier = Modifier) {
    val color = when {
        error -> Palette.error
        working -> Palette.accent
        else -> Palette.ok
    }
    if (working) {
        DisposableEffect(Unit) {
            Pulse.users.intValue++
            onDispose { Pulse.users.intValue-- }
        }
    }
    Box(
        modifier
            .size(9.dp)
            // Read while drawing, not composing: a step redraws the dot, nothing else.
            .graphicsLayer { alpha = if (working) Pulse.alpha.floatValue else 1f }
            .background(color, CircleShape),
    )
}

/**
 * One pulse for every working dot, in a few steps a second.
 *
 * Each dot ran its own smooth animation, and a smooth animation redraws the
 * screen at the display's rate -- 60 to 120 times a second for as long as
 * anything worked, which with agents running was all the time the app was
 * open. Stepped and shared, the screen changes about three times a second,
 * and not at all while nothing works.
 */
object Pulse {
    val users = androidx.compose.runtime.mutableIntStateOf(0)
    val alpha = androidx.compose.runtime.mutableFloatStateOf(1f)
    private val steps = floatArrayOf(1f, 0.78f, 0.55f, 0.4f, 0.55f, 0.78f)
    private const val STEP_MS = 300L

    /** Runs where the app is composed; still while no dot is working. */
    @Composable
    fun Driver() {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { users.intValue > 0 }.collectLatest { active ->
                if (!active) {
                    alpha.floatValue = 1f
                    return@collectLatest
                }
                var i = 0
                while (true) {
                    alpha.floatValue = steps[i]
                    i = (i + 1) % steps.size
                    delay(STEP_MS)
                }
            }
        }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("d MMM", Locale.getDefault())

fun clock(ms: Long): String = clockFormat.format(Date(ms))

/** "now", "5m", "3h", "yesterday", "12 Oct" -- for lists, where space is short. */
fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return ""
    val minutes = (now - ms) / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h"
        minutes < 48 * 60 -> "yesterday"
        else -> dayFormat.format(Date(ms))
    }
}

fun bytes(n: Long): String = when {
    n >= 1L shl 40 -> String.format(Locale.ROOT, "%.1f TB", n / (1L shl 40).toDouble())
    n >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", n / (1L shl 10).toDouble())
    else -> "$n B"
}
