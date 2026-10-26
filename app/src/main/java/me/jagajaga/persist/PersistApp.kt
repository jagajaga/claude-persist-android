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
        gateway.start()
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
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) gateway.reconnectNow()
            }
        })
    }
}

class PersistApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

val Context.container: Container get() = (applicationContext as PersistApp).container
