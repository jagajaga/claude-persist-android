package me.jagajaga.persist.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** The server does not take uploads this way: an older daemon. Use the socket instead. */
class UploadRouteMissing : IOException("upload route not available")

/**
 * Uploads over plain HTTPS, raw bytes in separate requests, beside the chat
 * socket rather than on it. Sent as frames on the socket, a big chunk kept
 * the phone's heartbeat replies waiting behind it until the server dropped
 * the link mid-chunk -- 260 times in an evening, a 1 MB photo taking two
 * minutes on a 39 Mbit/s connection.
 */
class HttpUploader(private val baseUrl: () -> String, private val cookie: () -> String?) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Answer(val received: Long, val path: String?)

    suspend fun put(sessionId: String, uploadId: String, name: String, offset: Long, bytes: ByteArray, done: Boolean): Answer =
        withContext(Dispatchers.IO) {
            val q = "sessionId=${enc(sessionId)}&uploadId=${enc(uploadId)}&name=${enc(name)}&offset=$offset" + if (done) "&done=1" else ""
            val request = Request.Builder()
                .url("${baseUrl()}/upload?$q")
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .header("Cookie", cookie() ?: "")
                .build()
            client.newCall(request).execute().use { r ->
                if (r.code in 300..399 || r.code == 401 || r.code == 403) throw NeedsLoginException()
                // 404 with our own answer is "unknown session"; any other 404 or a 405 is a daemon without the route.
                val text = r.body?.string().orEmpty()
                val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (body == null) {
                    if (r.code == 404 || r.code == 405) throw UploadRouteMissing()
                    throw IOException("upload answered ${r.code}")
                }
                if (body["ok"]?.jsonPrimitive?.boolean != true) {
                    throw GatewayException(body["error"]?.jsonPrimitive?.contentOrNull ?: "upload refused (${r.code})")
                }
                val result = body["result"] as? JsonObject ?: throw IOException("bad upload answer")
                Answer(
                    received = result["received"]?.jsonPrimitive?.longOrNull ?: throw IOException("bad upload answer"),
                    path = result["path"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
