package me.jagajaga.persist.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.jagajaga.persist.MainActivity
import me.jagajaga.persist.R
import me.jagajaga.persist.container

/**
 * Keeps the connection while the app is in the background and turns what
 * matters -- Claude waiting on you, a turn finished -- into notifications.
 *
 * Off unless switched on in Settings: it holds a connection open, and the
 * system shows a small persistent notification for that, which is the honest
 * price of hearing about a permission request within seconds.
 */
class NotifyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.channels(this)
        val ongoing = NotificationCompat.Builder(this, Notifications.CONNECTION)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Following your chats")
            .setContentText("You'll hear when Claude needs you or finishes")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifications.openApp(this, null))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ONGOING_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(Notifications.ONGOING_ID, ongoing)
        }
        val c = container
        c.store.wantFeed(true)
        c.gateway.start()
        scope.launch {
            c.store.incoming.collect { (sessionId, event) -> Notifications.maybePost(this@NotifyService, sessionId, event) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        container.store.wantFeed(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, NotifyService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotifyService::class.java))
        }
    }
}

object Notifications {
    const val CONNECTION = "connection"
    const val ONGOING_ID = 1
    const val EXTRA_CHAT = "openChat"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NotifyRules.ATTENTION, "Claude needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Permission requests and questions"
        })
        nm.createNotificationChannel(NotificationChannel(NotifyRules.DONE, "Turn finished", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A chat finished what it was doing"
        })
        nm.createNotificationChannel(NotificationChannel(CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while the app follows your chats in the background"
        })
    }

    fun openApp(context: Context, sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { sessionId?.let { putExtra(EXTRA_CHAT, it) } }
        return PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Post it, unless you are looking at that very chat right now. */
    fun maybePost(context: Context, sessionId: String, event: kotlinx.serialization.json.JsonObject) {
        val c = context.container
        if (AppVisibility.visible && c.store.isOpen(sessionId)) return
        val title = c.store.sessions.value.firstOrNull { it.id == sessionId }?.title ?: ""
        val notice = NotifyRules.notice(title, event) ?: return
        // Each kind has its own switch in Settings.
        val wanted = if (notice.channel == NotifyRules.ATTENTION) c.settings.notifyNeedsMe else c.settings.notifyDone
        if (!wanted) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        channels(context)
        val n: Notification = NotificationCompat.Builder(context, notice.channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setAutoCancel(true)
            .setPriority(if (notice.channel == NotifyRules.ATTENTION) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (notice.channel == NotifyRules.ATTENTION) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context, sessionId))
            .build()
        // One per chat and kind: a newer one replaces the last rather than piling up.
        val id = (sessionId + notice.channel).hashCode()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}

/** Whether the app is on screen; set by the activity. */
object AppVisibility {
    @Volatile
    var visible = false
}
