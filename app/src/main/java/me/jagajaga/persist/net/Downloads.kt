package me.jagajaga.persist.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Authelia answered instead of the server: the login has to be redone. */
class NeedsLoginException : IOException("Log in again to open this")

/**
 * Files from the server over HTTP, built for a bad network: what has arrived
 * is kept in a .part file, and a dropped download carries on from there with
 * a Range request instead of starting again.
 */
class Downloads(private val cookie: () -> String?) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false) // a redirect is Authelia's login page, not the file
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun cookieHeader(): String = cookie() ?: ""

    suspend fun download(url: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var failures = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            val request = Request.Builder().url(url).header("Cookie", cookieHeader())
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code in 300..399 || response.code == 401 || response.code == 403) throw NeedsLoginException()
                    if (response.code == 416 && have > 0) return@use // nothing left to fetch: it is all here
                    if (!response.isSuccessful) throw IOException("The server answered ${response.code}: ${response.body?.string()?.take(200)}")
                    val append = response.code == 206
                    val body = response.body ?: throw IOException("empty answer")
                    val total = (if (append) have else 0L) + body.contentLength().coerceAtLeast(0)
                    var done = if (append) have else 0L
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                onProgress(done, total)
                            }
                        }
                    }
                }
                target.delete()
                if (!part.renameTo(target)) throw IOException("could not save ${target.name}")
                return@withContext target
            } catch (e: NeedsLoginException) {
                throw e
            } catch (e: IOException) {
                if (++failures >= 8) throw e
                delay(minOf(30_000L, 1_000L shl failures))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        target
    }
}
