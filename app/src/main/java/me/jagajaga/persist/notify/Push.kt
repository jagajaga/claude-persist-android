package me.jagajaga.persist.notify

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import me.jagajaga.persist.BuildConfig
import me.jagajaga.persist.container

/**
 * Notifications through Google. The server sends a data message for what is
 * worth a buzz; it wakes the app even when Android has stopped it, so no
 * background connection is needed. A build without Firebase settings, or a
 * phone without Google Play services, keeps the background connection.
 */
object Push {
    private const val TAG = "Push"

    /** Firebase set up from the build's settings; false when this build has none. */
    fun init(context: Context): Boolean {
        if (BuildConfig.FCM_APP_ID.isBlank()) return false
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        return runCatching {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build(),
            )
            true
        }.onFailure { Log.w(TAG, "firebase init failed", it) }.getOrDefault(false)
    }

    /** Ask Google for this phone's token; it arrives in [onToken]. */
    fun fetchToken(context: Context, onToken: (String) -> Unit) {
        if (!init(context)) return
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> if (!token.isNullOrBlank()) onToken(token) }
                .addOnFailureListener { Log.w(TAG, "no push token (Google Play services?)", it) }
        }.onFailure { Log.w(TAG, "no push token", it) }
    }
}

/** Where Google hands over a message from the server, and a new token when it changes one. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        container.pushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val sessionId = d["sessionId"] ?: return
        when (d["kind"]) {
            "resolved" -> Notifications.answered(this, sessionId, d["requestId"])
            "attention" -> Notifications.post(this, sessionId, NotifyRules.Notice(NotifyRules.ATTENTION, d["title"].orEmpty(), d["text"].orEmpty()), d["requestId"])
            "done" -> Notifications.post(this, sessionId, NotifyRules.Notice(NotifyRules.DONE, d["title"].orEmpty(), d["text"].orEmpty()))
        }
    }
}
