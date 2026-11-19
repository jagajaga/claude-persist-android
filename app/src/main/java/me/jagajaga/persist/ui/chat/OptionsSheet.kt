package me.jagajaga.persist.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.jagajaga.persist.data.AccountInfo
import me.jagajaga.persist.data.SessionInfo
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.Type

/**
 * What the model pill opens, as in the editor panel: model, effort, account,
 * and signing in to another. Plus the permission mode, which the panel keeps
 * on a toggle beside the pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(store: Store, session: SessionInfo, onDismiss: () -> Unit) {
    val models by store.models.collectAsState()
    val accounts by store.accounts.collectAsState()
    val sessions by store.sessions.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var switchTo by remember { mutableStateOf<AccountInfo?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    // An account whose login failed or lapsed: tapping it signs it in again rather than switching to a dead login.
    var signInAgain by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { store.refreshOptions() }

    fun act(block: suspend () -> Unit) {
        scope.launch { runCatching { block() }.onFailure { error = it.message ?: "failed" } }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding(),
        ) {
            error?.let { Text(it, style = Type.small.copy(color = Palette.error), modifier = Modifier.padding(horizontal = 20.dp)) }

            Section("Model")
            if (models.isEmpty()) Text("Loading…", style = Type.small, modifier = Modifier.padding(horizontal = 20.dp))
            for ((value, label) in Options.modelChoices(models, session.model, session.activeModel)) {
                Choice(label, selected = value == (session.model ?: "")) {
                    act { store.setSessionOptions(session.id, model = value) }
                }
            }

            Section("Effort")
            for (level in Options.effortChoices(models, session.model, session.effort)) {
                Choice(level.ifEmpty { "default" }, selected = level == (session.effort ?: "")) {
                    act { store.setSessionOptions(session.id, effort = level) }
                }
            }

            Section("Permissions")
            val level = me.jagajaga.persist.data.PermissionLevel.of(session)
            for (choice in me.jagajaga.persist.data.PermissionLevel.entries) {
                Choice(choice.label, selected = level == choice, note = choice.explain) {
                    act {
                        when (choice) {
                            me.jagajaga.persist.data.PermissionLevel.Ask -> store.setPermissionMode(session.id, "default")
                            me.jagajaga.persist.data.PermissionLevel.Bypass ->
                                if (level == me.jagajaga.persist.data.PermissionLevel.SuperBypass) store.setAutoApprove(session.id, false)
                                else store.setPermissionMode(session.id, "bypassPermissions")
                            me.jagajaga.persist.data.PermissionLevel.SuperBypass -> store.setAutoApprove(session.id, true)
                        }
                    }
                }
            }

            Section("Account")
            val now = System.currentTimeMillis()
            for (account in accounts) {
                Choice(
                    Options.accountLabel(account),
                    selected = account.active,
                    note = Options.usage(account, now),
                    warn = !account.signedIn || account.loginExpired,
                ) {
                    when {
                        !account.signedIn || account.loginExpired -> signInAgain = if (account.configDir == null) "default" else account.name
                        !account.active -> switchTo = account
                    }
                }
            }
            val signedInAlready = accounts.any { it.signedIn }
            Choice(if (signedInAlready) "Log in to another account…" else "Sign in to Claude…", selected = false) { signingIn = true }
        }
    }

    switchTo?.let { target ->
        val busy = sessions.count { it.working }
        AlertDialog(
            onDismissRequest = { switchTo = null },
            title = { Text("Switch to ${target.name}?") },
            text = {
                Text(
                    if (busy > 0) "Every chat moves to this account. $busy chat${if (busy == 1) " is" else "s are"} working and will stop mid-turn."
                    else "Every chat moves to this account.",
                    style = Type.body,
                )
            },
            confirmButton = { TextButton(onClick = { act { store.setAccount(target.configDir) }; switchTo = null }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { switchTo = null }) { Text("Cancel") } },
        )
    }

    if (signingIn) SignInDialog(store, onDone = { signingIn = false })
    signInAgain?.let { name -> SignInDialog(store, account = name, onDone = { signInAgain = null; scope.launch { store.refreshOptions() } }) }
}

/**
 * Start a sign-in on the server, open its page here, paste the code back.
 * With [account] given (a login that failed, an expired account tapped), it
 * signs that one in again; otherwise it asks which.
 */
@Composable
internal fun SignInDialog(store: Store, onDone: () -> Unit, account: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    // The panel's two choices: the account Claude Code itself uses here, or a new named one.
    var useDefault by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf("") }
    var loginId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Text(
                when {
                    loginId != null -> "Paste the code"
                    account == null || account == "default" -> "Sign in to Claude"
                    else -> "Sign in to $account"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loginId == null && account != null) {
                    Text("A page opens to sign in. Then paste the code it shows here.", style = Type.small)
                } else if (loginId == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useDefault = true }) {
                        androidx.compose.material3.RadioButton(selected = useDefault, onClick = { useDefault = true })
                        Column {
                            Text("Default account", style = Type.body)
                            Text("The one Claude Code itself uses on the server", style = Type.tiny)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useDefault = false }) {
                        androidx.compose.material3.RadioButton(selected = !useDefault, onClick = { useDefault = false })
                        Column {
                            Text("New named account", style = Type.body)
                            Text("A separate login under ~/.claude-accounts", style = Type.tiny)
                        }
                    }
                    if (!useDefault) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.lowercase() },
                            label = { Text("Name, e.g. work") },
                            singleLine = true,
                            isError = name.isNotEmpty() && !Regex("^[a-z0-9-]+$").matches(name) || name == "default",
                        )
                    }
                } else {
                    Text("Sign in on the page that opened, then paste the code it shows.", style = Type.small)
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") }, singleLine = true)
                }
                message?.let { Text(it, style = Type.small.copy(color = Palette.error)) }
            }
        },
        confirmButton = {
            val nameOk = account != null || useDefault || (Regex("^[a-z0-9-]+$").matches(name) && name != "default")
            TextButton(enabled = !busy && (loginId != null || nameOk), onClick = {
                busy = true
                message = null
                scope.launch {
                    runCatching {
                        val id = loginId
                        if (id == null) {
                            val started = store.startLogin(account ?: if (useDefault) "default" else name.trim())
                            loginId = started.loginId
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(started.url)))
                        } else {
                            val result = store.submitLoginCode(id, code)
                            if (result.ok) onDone() else message = result.error ?: "That didn't work"
                        }
                    }.onFailure { message = it.message }
                    busy = false
                }
            }) { Text(if (loginId == null) "Open sign-in page" else "Sign in") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = Type.tiny.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, note: String? = null, warn: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Palette.accentSoft else Palette.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "✓" else "", style = Type.body.copy(color = Palette.accent), modifier = Modifier.width(22.dp))
        Text(label, style = Type.body.copy(color = if (warn) Palette.warn else Palette.text), modifier = Modifier.weight(1f))
        note?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = Type.tiny)
        }
    }
}
