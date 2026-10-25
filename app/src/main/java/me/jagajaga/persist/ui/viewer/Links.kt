package me.jagajaga.persist.ui.viewer

import java.net.URLDecoder

/** Where a tapped link goes. */
sealed interface LinkTarget {
    /** A web page: opened in the app's own browser, logged in like the rest. */
    data class Web(val url: String) : LinkTarget
    /** A file on the server, by absolute path: opened in the app's viewer. */
    data class ServerFile(val path: String) : LinkTarget
    /** mailto:, tel: and the like: handed to the phone. */
    data class External(val uri: String) : LinkTarget
}

enum class FileKind { Image, Video, Audio, Pdf, Text, Other }

object Links {
    /** A preview tapped: always the viewer, even for a picture on the web. */
    const val MEDIA = "persist-media:"

    fun target(uri: String): LinkTarget {
        val u = uri.trim()
        return when {
            u.startsWith(MEDIA) -> LinkTarget.ServerFile(u.removePrefix(MEDIA))
            u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true) -> LinkTarget.Web(u)
            u.startsWith("file://") -> LinkTarget.ServerFile(cleanPath(URLDecoder.decode(u.removePrefix("file://"), "UTF-8")))
            u.startsWith("/") -> LinkTarget.ServerFile(cleanPath(u))
            else -> LinkTarget.External(u)
        }
    }

    /** "/a/b.kt:42:7" and "/a/b.md#heading" are the file /a/b.kt and /a/b.md. */
    fun cleanPath(path: String): String = path.substringBefore('#').replace(Regex(":\\d+(:\\d+)?$"), "")

    fun kind(path: String): FileKind = when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif" -> FileKind.Image
        "mp4", "m4v", "mov", "webm", "mkv", "3gp" -> FileKind.Video
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> FileKind.Audio
        "pdf" -> FileKind.Pdf
        "txt", "md", "log", "json", "yml", "yaml", "toml", "xml", "csv", "tsv", "ini", "conf", "env", "sh", "bash", "zsh",
        "kt", "kts", "java", "ts", "tsx", "js", "jsx", "mjs", "cjs", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs",
        "swift", "sql", "html", "htm", "css", "scss", "gradle", "properties", "lock", "diff", "patch", "svg", "" -> FileKind.Text
        else -> FileKind.Other
    }

    /**
     * URLs and absolute paths inside plain text. A path needs at least two
     * segments and does not take a sentence's closing full stop with it.
     */
    private val pattern = Regex(
        """https?://[^\s<>()\[\]"'`]*[^\s<>()\[\]"'`.,;:!?]""" +
            """|(?<![\w/.:~])/(?:[\w.@~+-]+/)+[\w.@~+-]*[\w@~+-](?::\d+(?::\d+)?)?""",
    )

    /** The text cut into pieces, each a link target or null for plain text. */
    fun segments(text: String): List<Pair<String, String?>> {
        val out = mutableListOf<Pair<String, String?>>()
        var at = 0
        for (m in pattern.findAll(text)) {
            if (m.range.first > at) out += text.substring(at, m.range.first) to null
            out += m.value to m.value
            at = m.range.last + 1
        }
        if (at < text.length) out += text.substring(at) to null
        return out
    }

    /**
     * The pictures and videos a message mentions, by path or URL, in order,
     * each once: what the chat shows previews of. Capped, so a listing of a
     * hundred screenshots doesn't become a hundred previews.
     */
    fun mediaIn(text: String, max: Int = 6): List<String> =
        pattern.findAll(text)
            .map { m ->
                when (val t = target(m.value)) {
                    is LinkTarget.ServerFile -> t.path
                    is LinkTarget.Web -> t.url
                    is LinkTarget.External -> t.uri
                }
            }
            .filter { kind(it.substringBefore('?')) == FileKind.Image || kind(it.substringBefore('?')) == FileKind.Video }
            .distinct()
            .take(max)
            .toList()

    /** Inline code that is nothing but a path or URL is tappable too. */
    fun wholeLink(text: String): String? = pattern.matchEntire(text.trim())?.value
}
