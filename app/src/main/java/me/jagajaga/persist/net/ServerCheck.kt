package me.jagajaga.persist.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Before an address is saved: does a web server answer there at all?
 *
 * Only that. Its login page, a redirect to it, or code-server itself all
 * count -- the login comes next, in the app. What fails is what would make
 * the login screen a blank page: no such name, nothing listening, a
 * certificate the phone does not trust, or a server that answers only errors.
 */
class ServerCheck(base: OkHttpClient = OkHttpClient(), private val timeoutS: Long = TIMEOUT_S) {
    private val client: OkHttpClient = base.newBuilder()
        .connectTimeout(timeoutS, TimeUnit.SECONDS)
        .readTimeout(timeoutS, TimeUnit.SECONDS)
        .callTimeout(timeoutS * 2, TimeUnit.SECONDS)
        // The login portal's redirect is an answer; following it proves nothing more.
        .followRedirects(false)
        .build()

    sealed interface Result {
        data object Ok : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(url: String): Result = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code >= 500) {
                    Result.Failed("$host answered with an error (${response.code}). Is code-server running?")
                } else {
                    Result.Ok
                }
            }
        } catch (e: UnknownHostException) {
            Result.Failed("Couldn't find $host. Check the address, and that the phone is online.")
        } catch (e: SSLException) {
            Result.Failed("Couldn't make a secure connection to $host: its certificate isn't trusted by this phone, or it doesn't serve HTTPS there.")
        } catch (e: SocketTimeoutException) {
            Result.Failed("$host didn't answer within $timeoutS seconds.")
        } catch (e: ConnectException) {
            Result.Failed("Nothing is answering at $host.")
        } catch (e: IOException) {
            Result.Failed("Couldn't reach $host: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            Result.Failed("That isn't a web address")
        }
    }

    companion object {
        const val TIMEOUT_S = 10L
    }
}
