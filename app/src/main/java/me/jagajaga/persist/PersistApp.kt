package me.jagajaga.persist

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.jagajaga.persist.net.ConnectionState
import me.jagajaga.persist.data.Store
import me.jagajaga.persist.net.GatewayClient
import java.io.File

/** Where the server is. Editable in the app; these are this server's values. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("baseUrl", DEFAULT_BASE_URL)!!.trimEnd('/')
        set(value) = prefs.edit().putString("baseUrl", value.trim().trimEnd('/')).apply()

    var gatewayPort: Int
        get() = prefs.getInt("gatewayPort", DEFAULT_PORT)
        set(value) = prefs.edit().putInt("gatewayPort", value).apply()

    /** code-server proxies /proxy/<port>/ to localhost:<port>, behind its login. */
    val wsUrl: String
        get() = baseUrl.replaceFirst(Regex("^http"), "ws") + "/proxy/$gatewayPort/ws"

    /** Notify when a chat needs you: a permission to allow, a question to answer. */
    var notifyNeedsMe: Boolean
        get() = prefs.getBoolean("notifyNeedsMe", prefs.getBoolean("notify", false))
        set(value) = prefs.edit().putBoolean("notifyNeedsMe", value).apply()

    /** Notify when a turn finishes. */
    var notifyDone: Boolean
        get() = prefs.getBoolean("notifyDone", prefs.getBoolean("notify", false))
        set(value) = prefs.edit().putBoolean("notifyDone", value).apply()

    /** The background connection runs while either kind of notification is on (see NotifyService). */
    val notify: Boolean get() = notifyNeedsMe || notifyDone

    /** This phone's Firebase token, once Google has given one. */
    var pushToken: String?
        get() = prefs.getString("pushToken", null)
        set(value) = prefs.edit().putString("pushToken", value).apply()

    /** The server took the token and can send through Google: no background connection needed. */
    var pushActive: Boolean
        get() = prefs.getBoolean("pushActive", false)
        set(value) = prefs.edit().putBoolean("pushActive", value).apply()

    /** The gateway over plain HTTP, through the same proxy and login. */
    val httpBase: String get() = "$baseUrl/proxy/$gatewayPort"

    /** A server file, for viewing, playing or downloading. */
    fun fileUrl(path: String): String = "$httpBase/file?path=" + java.net.URLEncoder.encode(path, "UTF-8")

    companion object {
        const val DEFAULT_BASE_URL = "https://code.example.com"
        const val DEFAULT_PORT = 7790
    }
}

/** The app's long-lived parts: one connection and one store for the process. */
class Container(context: Context) {
    private val app: Context = context.applicationContext
    val settings = Settings(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val gateway = GatewayClient(
        scope = scope,
        wsUrl = { settings.wsUrl },
        // The Authelia session lives in the WebView's cookie jar after login.
        cookie = { CookieManager.getInstance().getCookie(settings.baseUrl) },
    )

    val store = Store(
        gateway,
        File(context.filesDir, "persist"),
        scope,
        http = me.jagajaga.persist.net.HttpUploader(
            baseUrl = { settings.httpBase },
            cookie = { CookieManager.getInstance().getCookie(settings.baseUrl) },
        ),
    )

    /** A chat a tapped notification asked for, until the UI has opened it. */
    val pendingOpen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val downloads = me.jagajaga.persist.net.Downloads(cookie = { CookieManager.getInstance().getCookie(settings.baseUrl) })

    init {
        watchNetwork(context)
        // Not started here: a push from Google starts the process too, and
        // must not open a connection that then streams every chat to a phone
        // in a pocket. The screen, or the background service, starts it.
        // Every connect: the server may be new, or may have lost the token.
        scope.launch { gateway.connected.collect { registerPush() } }
    }

    @Volatile
    private var onScreen = false
    private var pauseJob: kotlinx.coroutines.Job? = null

    /** The app came to the front: connect now. */
    fun onForeground() {
        onScreen = true
        pauseJob?.cancel()
        gateway.start()
        gateway.reconnectNow()
    }

    /**
     * The app left the screen. Unless the background service is what brings
     * notifications, the connection closes: open, it carried every word of
     * every open chat to a phone nobody was looking at, and kept its radio
     * awake for hours. It waits a little first -- switching apps for a moment
     * should not reconnect -- and for anything still being sent.
     */
    fun onBackground() {
        onScreen = false
        pauseJob?.cancel()
        pauseJob = scope.launch {
            kotlinx.coroutines.delay(BACKGROUND_GRACE_MS)
            kotlinx.coroutines.withTimeoutOrNull(SEND_GRACE_MS) { store.outgoing.first { list -> list.none { it.failed == null } } }
            pauseIfIdle()
        }
    }

    /** Close the connection if neither the screen nor the background service needs it. */
    fun pauseIfIdle() {
        if (!onScreen && !me.jagajaga.persist.notify.NotifyService.running) gateway.stop()
    }

    /** Google gave this phone a token (first launch, or a new one): tell the server. */
    fun pushToken(token: String) {
        if (settings.pushToken == token && settings.pushActive) return
        settings.pushToken = token
        scope.launch { registerPush() }
    }

    /**
     * Hand the token to the server. It answers whether it can send through
     * Google; an older server does not know the request at all. Either way
     * the background connection runs exactly when Google will not do.
     */
    private suspend fun registerPush() {
        val token = settings.pushToken ?: return
        val active = try {
            val answer = gateway.request("registerPush", mapOf("token" to kotlinx.serialization.json.JsonPrimitive(token)))
            ((answer as? kotlinx.serialization.json.JsonObject)?.get("enabled") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        } catch (e: me.jagajaga.persist.net.GatewayException) {
            false
        } catch (e: Exception) {
            return // no answer: try again on the next connect
        }
        if (active == settings.pushActive) return
        settings.pushActive = active
        // Starting a foreground service from the background can be refused; the next launch sorts it out.
        runCatching { me.jagajaga.persist.notify.Notifications.follow(app) }
    }

    /**
     * Any change of network is a reason to reconnect now rather than wait out
     * the backoff: Wi-Fi to mobile, mobile coming back after a tunnel, a VPN
     * starting. This is most of what "survives a bad network" means in practice.
     */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = gateway.reconnectNow()
            // Coming back to the front, the app's network is unblocked a moment
            // after it starts: that moment is when to connect, not a backoff later.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!blocked) gateway.reconnectNow()
            }
            // Capabilities change all the time (signal, bandwidth estimates): only
            // a link that is down is worth a retry. Resetting the backoff at every
            // change kept a phone with no route to the server retrying nonstop.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && gateway.state.value !is ConnectionState.Online) gateway.reconnectNow(eager = false)
            }
        })
    }
}

private const val BACKGROUND_GRACE_MS = 30_000L
private const val SEND_GRACE_MS = 5 * 60_000L

class PersistApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

val Context.container: Container get() = (applicationContext as PersistApp).container
