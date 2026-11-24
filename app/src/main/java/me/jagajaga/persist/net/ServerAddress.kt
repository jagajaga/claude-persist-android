package me.jagajaga.persist.net

import java.net.URI

/**
 * The server is whatever the user types: the app carries no address of its
 * own. This is the one place that decides what a typed address means and
 * whether a stored one is still usable.
 */
object ServerAddress {
    sealed interface Parsed {
        /** Normalised: https, lower-case host, no trailing slash. */
        data class Ok(val url: String) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    /**
     * What the user typed, as the address to use. A bare host means https;
     * anything but https is refused, since the login cookie travels over it.
     */
    fun parse(input: String): Parsed {
        val text = input.trim()
        if (text.isEmpty()) return Parsed.Bad("Enter your server's address")
        if (text.any { it.isWhitespace() }) return Parsed.Bad("An address has no spaces")
        val withScheme = if (text.contains("://")) text else "https://$text"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return Parsed.Bad("That isn't a web address")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http") return Parsed.Bad("Use https:// — your login travels over this address")
        if (scheme != "https") return Parsed.Bad("Use an https:// address")
        val host = uri.host?.lowercase()
        if (host.isNullOrEmpty()) return Parsed.Bad("That address has no server name")
        if (uri.rawUserInfo != null) return Parsed.Bad("Leave the login out of the address; the app asks for it next")
        if (uri.rawQuery != null || uri.rawFragment != null) return Parsed.Bad("Just the server's address, without ? or #")
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        val path = (uri.rawPath ?: "").trimEnd('/')
        return Parsed.Ok("https://$host$port$path")
    }

    /**
     * The address saved on this phone, if there is one to use. Nothing saved
     * means the app asks for one: there is no built-in default. An address
     * saved by an older version is kept as it is (an http one included), so
     * an update never changes where an existing install connects.
     */
    fun stored(saved: String?): String? {
        val text = saved?.trim()?.trimEnd('/') ?: return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return text
    }

    /**
     * Is [url] on the server at [base]? Only then does it get the login
     * cookie. Not a bare prefix match: https://code.example.com.evil.invalid
     * starts with https://code.example.com too. No server set: nothing is.
     */
    fun isUnder(url: String, base: String?): Boolean {
        if (base.isNullOrEmpty()) return false
        if (!url.startsWith(base)) return false
        return url.length == base.length || url[base.length] in "/?#"
    }
}
