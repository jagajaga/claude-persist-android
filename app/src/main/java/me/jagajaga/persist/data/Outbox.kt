package me.jagajaga.persist.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A message written on the phone and not yet confirmed by the server. */
@Serializable
data class OutgoingMessage(
    val clientMessageId: String,
    val sessionId: String,
    val text: String,
    val createdAt: Long,
    /** Attached images, base64, with their media types. */
    val images: List<OutgoingImage> = emptyList(),
    val attempts: Int = 0,
)

@Serializable
data class OutgoingImage(val name: String, val mediaType: String, val base64: String)

/**
 * Messages are written here first and sent from here, never sent directly.
 *
 * On a bad network a send can vanish in either direction, and the phone cannot
 * tell "it never arrived" from "it arrived and the answer was lost". So every
 * message gets its own id when it is written, is kept on disk until the
 * server confirms it, and is resent -- with the same id -- after every
 * reconnect. The server records a given id once, so a resend of something
 * that did arrive is a no-op. Nothing is lost by closing the app, killing it,
 * or rebooting the phone with a message still queued.
 */
class Outbox(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val items = LinkedHashMap<String, OutgoingMessage>()

    init {
        if (file.exists()) {
            runCatching {
                json.decodeFromString<List<OutgoingMessage>>(file.readText())
            }.getOrNull()?.forEach { items[it.clientMessageId] = it }
        }
    }

    /** Everything still waiting, oldest first. */
    @Synchronized
    fun pending(): List<OutgoingMessage> = items.values.toList()

    /** Pending for one chat, for showing them as "sending" in the thread. */
    @Synchronized
    fun pendingFor(sessionId: String): List<OutgoingMessage> = items.values.filter { it.sessionId == sessionId }

    /** Write a new message; it is on disk before this returns. */
    @Synchronized
    fun add(sessionId: String, text: String, images: List<OutgoingImage> = emptyList()): OutgoingMessage {
        val message = OutgoingMessage(
            clientMessageId = "phone-" + UUID.randomUUID().toString(),
            sessionId = sessionId,
            text = text,
            createdAt = now(),
            images = images,
        )
        items[message.clientMessageId] = message
        persist()
        return message
    }

    /** The server has it, as new or as a duplicate; either way it is done. */
    @Synchronized
    fun confirm(clientMessageId: String) {
        if (items.remove(clientMessageId) != null) persist()
    }

    /** One more try made; kept for showing "retrying" and for diagnosis. */
    @Synchronized
    fun attempted(clientMessageId: String) {
        val item = items[clientMessageId] ?: return
        items[clientMessageId] = item.copy(attempts = item.attempts + 1)
        persist()
    }

    /** Written to a temporary file and renamed, so a crash mid-write loses nothing. */
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(items.values.toList()))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
