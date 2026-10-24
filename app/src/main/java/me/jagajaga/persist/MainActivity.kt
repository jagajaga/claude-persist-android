package me.jagajaga.persist

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
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
import me.jagajaga.persist.ui.StatsScreen
import me.jagajaga.persist.ui.Type
import me.jagajaga.persist.ui.chat.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent { PersistTheme { App(c) } }
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

    // An expired Authelia session takes you to its login, once, by itself.
    LaunchedEffect(state) {
        if (state is ConnectionState.NeedsLogin && route != "login") nav.navigate("login")
    }

    val banner: @Composable () -> Unit = {
        ConnectionBanner(
            state = state,
            queued = outgoing.size,
            onLogin = { nav.navigate("login") },
            onRetry = { c.gateway.reconnectNow() },
        )
    }

    Box(Modifier.fillMaxSize().background(Palette.page).statusBarsPadding()) {
        NavHost(nav, startDestination = "main") {
            composable("main") { MainTabs(c, nav, banner) }
            composable("chat/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ChatScreen(c.store, id, onBack = { nav.popBackStack() }, banner = banner)
            }
            composable("new") { NewChat(c, nav) }
            composable("login") {
                LoginScreen(c.settings.baseUrl) {
                    c.gateway.reconnectNow()
                    nav.popBackStack()
                }
            }
            composable("settings") { SettingsScreen(c, nav) }
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
                IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
            }
            banner()
            when (Tab.entries[tab]) {
                Tab.Chats -> ChatListScreen(sessions, onOpen = { nav.navigate("chat/${it.id}") }, onNew = { nav.navigate("new") })
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
            Text("New chat", style = Type.title.copy(fontWeight = FontWeight.SemiBold))
        }
        if (chosen == null) {
            FilesScreen(c.store, startPath = homeGuess(c), pickFolder = { folder = it })
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
        OutlinedButton(onClick = {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            c.gateway.reconnectNow()
        }) { Text("Log out") }
    }
}
