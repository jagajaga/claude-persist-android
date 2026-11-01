package me.jagajaga.persist

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavType
import androidx.navigation.navArgument
import me.jagajaga.persist.ui.viewer.AppUriHandler
import me.jagajaga.persist.ui.viewer.UpdateCheck
import me.jagajaga.persist.ui.viewer.ViewerScreen
import me.jagajaga.persist.ui.viewer.openExternally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import me.jagajaga.persist.net.ConnectionState
import me.jagajaga.persist.ui.ChatListScreen
import me.jagajaga.persist.ui.ConnectionBanner
import me.jagajaga.persist.ui.FeedScreen
import me.jagajaga.persist.ui.FilesScreen
import me.jagajaga.persist.ui.LoginScreen
import me.jagajaga.persist.ui.Palette
import me.jagajaga.persist.ui.PersistTheme
import me.jagajaga.persist.ui.StatsBar
import me.jagajaga.persist.ui.StatsScreen
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.chat.ChatPager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark icons, always: the app is light whatever the phone's own theme,
        // and the default follows the phone, so in dark mode the clock, battery
        // and signal were drawn white on this white bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        val c = container
        // Only a fresh launch: an activity recreated (rotation, back from recents)
        // still carries the old notification's intent and would jump there again.
        if (savedInstanceState == null) c.pendingOpen.value = intent?.getStringExtra(me.jagajaga.persist.notify.Notifications.EXTRA_CHAT)
        if (c.settings.notify) me.jagajaga.persist.notify.NotifyService.start(this)
        setContent { PersistTheme { App(c) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(me.jagajaga.persist.notify.Notifications.EXTRA_CHAT)?.let { container.pendingOpen.value = it }
    }

    override fun onStart() {
        super.onStart()
        me.jagajaga.persist.notify.AppVisibility.visible = true
    }

    override fun onStop() {
        me.jagajaga.persist.notify.AppVisibility.visible = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back from the background: the socket may well have died while we were
        // away, and there is no reason to wait out a backoff to find out.
        container.gateway.reconnectNow()
    }
}

@Composable
private fun App(c: Container) {
    val nav = rememberNavController()
    val state by c.gateway.state.collectAsState()
    val outgoing by c.store.outgoing.collectAsState()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route

    // A tapped notification: straight to its chat.
    val pending by c.pendingOpen.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { id ->
            // Over whatever is open, a chat included: back to the list, then
            // that chat. "Single top" kept a chat already on screen there,
            // so a notification for another chat opened the wrong one.
            nav.navigate("chat/$id") { popUpTo("main") }
            c.pendingOpen.value = null
        }
    }

    // An expired Authelia session takes you to its login, once, by itself.
    LaunchedEffect(state) {
        if (state is ConnectionState.NeedsLogin && route != "login") nav.navigate("login")
    }

    val sending by c.store.sendStatus.collectAsState()
    val banner: @Composable () -> Unit = {
        ConnectionBanner(
            state = state,
            queued = outgoing.size,
            onLogin = { nav.navigate("login") },
            onRetry = { c.gateway.reconnectNow() },
            sending = sending,
            onSendNow = { c.store.sendNow() },
        )
    }

    val context = LocalContext.current
    val uriHandler = remember(nav) {
        AppUriHandler(
            context,
            openFile = { path, line -> nav.navigate("view?path=" + Uri.encode(path) + (line?.let { "&line=$it" } ?: "")) },
        )
    }
    // Every link in the app goes through this, whatever Compose would do with it.
    val systemHandler = LocalUriHandler.current
    DisposableEffect(uriHandler) {
        me.jagajaga.persist.ui.viewer.LinkRouter.handler = uriHandler
        me.jagajaga.persist.ui.viewer.LinkRouter.fallback = { runCatching { systemHandler.openUri(it) } }
        onDispose { if (me.jagajaga.persist.ui.viewer.LinkRouter.handler === uriHandler) me.jagajaga.persist.ui.viewer.LinkRouter.handler = null }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
    Column(Modifier.fillMaxSize().background(Palette.page).statusBarsPadding()) {
        StatsBar(c.store, onOpen = { nav.navigate("server") { launchSingleTop = true } })
        Box(Modifier.weight(1f)) {
        NavHost(nav, startDestination = "main") {
            composable("server") {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                        Text("Server", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
                    }
                    StatsScreen(c.store)
                }
            }
            composable("move/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                MoveChat(c, id, nav)
            }
            composable(
                "view?path={path}&line={line}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("line") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { entry ->
                ViewerScreen(c, entry.arguments?.getString("path") ?: "/", line = entry.arguments?.getInt("line")?.takeIf { it > 0 }) { nav.popBackStack() }
            }
            composable("main") { MainTabs(c, nav, banner) }
            composable("chat/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ChatPager(c.store, id, onBack = { nav.popBackStack() }, onMove = { sid -> nav.navigate("move/$sid") }, banner = banner)
            }
            composable("new") { NewChat(c, nav) }
            composable("login") {
                LoginScreen(c.settings.baseUrl) {
                    c.gateway.reconnectNow()
                    nav.popBackStack()
                }
            }
            composable("settings") { SettingsScreen(c, nav) }
            composable("import") { ImportScreen(c, nav) }
        }
        }
    }
    }
}

private enum class Tab(val label: String) { Chats("Chats"), Feed("Feed"), Files("Files"), Server("Server") }

@Composable
private fun MainTabs(c: Container, nav: NavHostController, banner: @Composable () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val sessions by c.store.sessions.collectAsState()
    val feed by c.store.feed.collectAsState()
    Scaffold(
        containerColor = Palette.page,
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                Tab.entries.forEachIndexed { index, t ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(t.label) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Chats -> Icons.Filled.Chat
                                    Tab.Feed -> Icons.Filled.DynamicFeed
                                    Tab.Files -> Icons.Filled.Folder
                                    Tab.Server -> Icons.Filled.Memory
                                },
                                contentDescription = t.label,
                            )
                        },
                    )
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                Modifier.fillMaxWidth().background(Palette.surface).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Tab.entries[tab].label, style = Type.title.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                if (Tab.entries[tab] == Tab.Chats) {
                    IconButton(onClick = { nav.navigate("import") }) {
                        Icon(Icons.Filled.Download, contentDescription = "Import a Claude Code session")
                    }
                }
                IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
            banner()
            when (Tab.entries[tab]) {
                Tab.Chats -> {
                    val viewed by c.store.viewed.collectAsState()
                    val usage by c.store.usage.collectAsState()
                    val scope = rememberCoroutineScope()
                    ChatListScreen(
                        sessions,
                        onOpen = { nav.navigate("chat/${it.id}") },
                        onNew = { nav.navigate("new") },
                        viewed = viewed,
                        usage = usage?.let { u -> me.jagajaga.persist.ui.chat.Options.usageLine(u.windows) },
                        onRename = { s, title -> scope.launch { runCatching { c.store.rename(s.id, title) } } },
                        onDelete = { s -> scope.launch { runCatching { c.store.delete(s.id) } } },
                    )
                }
                Tab.Feed -> FeedScreen(c.store, feed) { id -> nav.navigate("chat/$id") }
                Tab.Files -> FilesScreen(c.store, startPath = homeGuess(c))
                Tab.Server -> StatsScreen(c.store)
            }
        }
    }
}

/** Where the work lives: the common parent of the chats' folders, else /. */
private fun homeGuess(c: Container): String {
    val cwds = c.store.sessions.value.map { it.cwd }
    if (cwds.isEmpty()) return "/"
    var prefix = cwds.first()
    for (cwd in cwds) while (!cwd.startsWith(prefix)) prefix = prefix.substringBeforeLast('/', "")
    return prefix.trimEnd('/').ifEmpty { "/" }
}

/** A new chat: choose its folder on the server, then name it if you like. */
@Composable
private fun NewChat(c: Container, nav: NavHostController) {
    var folder by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val chosen = folder
    Column(Modifier.fillMaxSize().background(Palette.page)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (chosen != null) folder = null else nav.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(if (chosen == null) "New chat: choose its folder" else "New chat", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
        }
        if (chosen == null) {
            // Named after its folder to start with, as the panel does: "blooper2.0-".
            FilesScreen(c.store, startPath = homeGuess(c), pickFolder = { folder = it; if (title.isBlank()) title = it.trimEnd('/').substringAfterLast('/') + "-" })
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("In $chosen", style = Type.small)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, style = Type.small.copy(color = Palette.error)) }
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { c.store.createSession(chosen, title) }
                                .onSuccess { info ->
                                    nav.popBackStack()
                                    nav.navigate("chat/${info.id}")
                                }
                                .onFailure { error = it.message }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                ) { Text("Start chat") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(c: Container, nav: NavHostController) {
    var base by remember { mutableStateOf(c.settings.baseUrl) }
    var port by remember { mutableStateOf(c.settings.gatewayPort.toString()) }
    Column(Modifier.fillMaxSize().background(Palette.page).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
        }
        OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("code-server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("Gateway port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Connects to ${base.trimEnd('/').replaceFirst(Regex("^http"), "ws")}/proxy/$port/ws", style = Type.tiny)
        Button(
            onClick = {
                c.settings.baseUrl = base
                port.toIntOrNull()?.let { c.settings.gatewayPort = it }
                c.gateway.reconnectNow()
                nav.popBackStack()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
        ) { Text("Save") }
        OutlinedButton(onClick = { nav.navigate("login") }) { Text("Log in again") }
        NotifySection(c)
        UpdateSection(c)
        OutlinedButton(onClick = {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            c.gateway.reconnectNow()
        }) { Text("Log out") }
    }
}

/**
 * "Check for updates": the server fetches the newest release (the repo is
 * private, the server can read it), the phone downloads it through the
 * gateway -- resuming if the link drops -- and Android's installer takes over.
 */
@Composable
private fun UpdateSection(c: Container) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var newer by remember { mutableStateOf<me.jagajaga.persist.data.AppRelease?>(null) }

    fun install(release: me.jagajaga.persist.data.AppRelease) {
        busy = true
        scope.launch {
            runCatching {
                status = "Fetching ${release.version} on the server…"
                val ready = c.store.appRelease(download = true)
                val path = ready.path ?: error("the server couldn't fetch it")
                val target = java.io.File(context.cacheDir, "updates/${ready.name}")
                c.downloads.download(c.settings.fileUrl(path), target) { done, total ->
                    status = "Downloading… ${me.jagajaga.persist.ui.bytes(done)} of ${me.jagajaga.persist.ui.bytes(if (total > 0) total else ready.size)}"
                }
                if (!context.packageManager.canRequestPackageInstalls()) {
                    status = "Allow this app to install updates, then tap Install again."
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    status = null
                    openExternally(context, target, "application/vnd.android.package-archive")
                }
            }.onFailure { status = "Update failed: ${it.message}" }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App version $installed", style = Type.small)
        val n = newer
        if (n != null) {
            Button(
                enabled = !busy,
                onClick = { install(n) },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
            ) { Text("Install ${n.version}") }
        } else {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                status = "Checking…"
                scope.launch {
                    runCatching { c.store.appRelease(download = false) }
                        .onSuccess { r ->
                            if (UpdateCheck.isNewer(r.version, installed)) { newer = r; status = "${r.version} is available" }
                            else status = "You have the newest version (${r.version})"
                        }
                        .onFailure { status = "Couldn't check: ${it.message}" }
                    busy = false
                }
            }) { Text("Check for updates") }
        }
        status?.let { Text(it, style = Type.small) }
    }
}

/**
 * Two switches: when a chat needs you (a permission like "Allow Bash?", or a
 * question), and when a turn finishes. Either keeps a background connection
 * so they arrive with the app closed.
 */
@Composable
private fun NotifySection(c: Container) {
    val context = LocalContext.current
    var needsMe by remember { mutableStateOf(c.settings.notifyNeedsMe) }
    var done by remember { mutableStateOf(c.settings.notifyDone) }
    fun apply() {
        c.settings.notifyNeedsMe = needsMe
        c.settings.notifyDone = done
        if (c.settings.notify) me.jagajaga.persist.notify.NotifyService.start(context) else me.jagajaga.persist.notify.NotifyService.stop(context)
    }
    // Asked for once, the first time either switch goes on.
    var pendingSwitch by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> pendingSwitch?.invoke(granted); pendingSwitch = null; apply() }
    fun switch(want: Boolean, set: (Boolean) -> Unit) {
        val needPermission = want && android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needPermission) {
            pendingSwitch = set
            ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            set(want)
            apply()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Notifications", style = Type.small.copy(fontWeight = FontWeight.SemiBold))
        NotifyRow("When Claude needs me", "A permission to allow (\"Allow Bash?\") or a question to answer", needsMe) { want -> switch(want) { needsMe = it } }
        NotifyRow("When a turn finishes", "With the first line of what Claude did", done) { want -> switch(want) { done = it } }
        if (needsMe || done) Text("Keeps a connection open while the app is closed; Android shows a small notification for that.", style = Type.tiny)
    }
}

@Composable
private fun NotifyRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body)
            Text(detail, style = Type.tiny)
        }
        androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Bring an existing Claude Code conversation on the server in as a chat, as the panel's Import does. */
@Composable
private fun ImportScreen(c: Container, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<me.jagajaga.persist.data.ClaudeSessionCandidate>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { c.store.claudeSessions() }.onSuccess { list = it.sortedByDescending { s -> s.mtimeMs } }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize().background(Palette.page)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Import a Claude Code session", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
        }
        error?.let { Text(it, style = Type.small.copy(color = Palette.error), modifier = Modifier.padding(16.dp)) }
        val items = list
        if (items == null && error == null) Text("Looking…", style = Type.small, modifier = Modifier.padding(16.dp))
        if (items != null && items.isEmpty()) Text("No Claude Code sessions on the server", style = Type.small, modifier = Modifier.padding(16.dp))
        androidx.compose.foundation.lazy.LazyColumn {
            items(items.orEmpty().size) { i ->
                val s = items!![i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.surface)
                        .clickable(enabled = !busy) {
                            busy = true
                            scope.launch {
                                runCatching { c.store.importClaudeSession(s.file) }
                                    .onSuccess { info -> nav.popBackStack(); nav.navigate("chat/${info.id}") }
                                    .onFailure { error = it.message }
                                busy = false
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(s.title.ifBlank { s.sdkSessionId }, style = Type.body, maxLines = 2)
                    Text("${me.jagajaga.persist.ui.ago(s.mtimeMs.toLong())} · ${s.cwd}", style = Type.tiny, maxLines = 1)
                }
                androidx.compose.material3.HorizontalDivider(color = Palette.border)
            }
        }
    }
}

/** Pick a new folder for a chat; its next turn runs there, conversation intact. */
@Composable
private fun MoveChat(c: Container, sessionId: String, nav: NavHostController) {
    val sessions by c.store.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Palette.page)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column {
                Text("Move chat to another folder", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
                Text("Now in ${session?.cwd ?: "…"}", style = Type.tiny)
            }
        }
        error?.let { Text(it, style = Type.small.copy(color = Palette.error), modifier = Modifier.padding(horizontal = 16.dp)) }
        FilesScreen(c.store, startPath = session?.cwd ?: homeGuess(c), pickFolder = { target = it })
    }
    target?.let { to ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { target = null },
            title = { Text("Move this chat?") },
            text = { Text("The next message runs in $to, with the whole conversation so far.", style = Type.body) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    target = null
                    scope.launch {
                        runCatching { c.store.setSessionCwd(sessionId, to) }
                            .onSuccess { nav.popBackStack() }
                            .onFailure { error = it.message }
                    }
                }) { Text("Move") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { target = null }) { Text("Cancel") } },
        )
    }
}
