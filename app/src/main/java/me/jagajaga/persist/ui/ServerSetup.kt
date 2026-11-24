package me.jagajaga.persist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.jagajaga.persist.Container
import me.jagajaga.persist.net.ServerAddress
import me.jagajaga.persist.net.ServerCheck

/**
 * The first screen when no server is set: a fresh install, or an update from
 * a version that had one built in. The app has no address of its own.
 */
@Composable
fun ServerSetupScreen(c: Container, onDone: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.page)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Connect to your server", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
            Text(
                "The address of your code-server, as you open it in the browser. " +
                    "You log in there next; the app reaches Claude Persist through it.",
                style = Type.small,
            )
            ServerForm(c, buttonLabel = "Connect", onSaved = onDone)
        }
    }
}

/**
 * The address and port, checked before they are kept: the setup screen and
 * Settings both use this one form, so they behave and look the same.
 */
@Composable
fun ServerForm(c: Container, buttonLabel: String, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val current = c.settings.server
    var address by rememberSaveable { mutableStateOf(current.orEmpty()) }
    var port by rememberSaveable { mutableStateOf(c.settings.gatewayPort.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    // A check that failed for this address: offer to keep it anyway (the server may just be down).
    var unreachable by remember { mutableStateOf<String?>(null) }

    fun save(url: String) {
        c.settings.server = url
        port.toIntOrNull()?.let { c.settings.gatewayPort = it }
        onSaved()
    }

    fun submit() {
        if (checking) return
        error = null
        unreachable = null
        // Unchanged: an address saved by an older version is kept as it is.
        if (current != null && address.trim().trimEnd('/') == current) return save(current)
        when (val parsed = ServerAddress.parse(address)) {
            is ServerAddress.Parsed.Bad -> error = parsed.reason
            is ServerAddress.Parsed.Ok -> {
                address = parsed.url
                checking = true
                scope.launch {
                    when (val result = ServerCheck().check(parsed.url)) {
                        ServerCheck.Result.Ok -> save(parsed.url)
                        is ServerCheck.Result.Failed -> {
                            error = result.reason
                            unreachable = parsed.url
                        }
                    }
                    checking = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; error = null; unreachable = null },
            label = { Text("code-server address") },
            placeholder = { Text("https://code.example.com", color = Palette.faint) },
            isError = error != null,
            singleLine = true,
            enabled = !checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text("Gateway port") },
            singleLine = true,
            enabled = !checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        val preview = (ServerAddress.parse(address) as? ServerAddress.Parsed.Ok)?.url
            ?: address.trim().trimEnd('/').takeIf { it.isNotEmpty() && it == current }
        if (preview != null) {
            Text("Connects to ${preview.replaceFirst(Regex("^http"), "ws")}/proxy/$port/ws", style = Type.tiny)
        }
        error?.let { Text(it, style = Type.small.copy(color = Palette.error)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { submit() },
                enabled = !checking && address.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Text("Checking…", modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text(buttonLabel)
                }
            }
            unreachable?.let { url ->
                OutlinedButton(onClick = { save(url) }) { Text("Use it anyway") }
            }
        }
    }
}
