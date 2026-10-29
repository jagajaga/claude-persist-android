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
    /** Any other file: copied into the app's storage, uploaded before the message is sent. */
    val files: List<OutgoingFile> = emptyList(),
    val attempts: Int = 0,
    /**
     * Why the server refused it, when it did. A refused message is kept, not
     * dropped: it is shown as not sent, with Retry and Discard, and it holds
     * back the chat's later messages so they never arrive out of order.
     */
    val failed: String? = null,
)

@Serializable
data class OutgoingImage(
    val name: String,
    val mediaType: String,
    /** Inline bytes, as messages queued by older versions hold them; emptied once uploaded. */
    val base64: String = "",
    /**
     * The picture's bytes in the app's storage. New photos wait here rather
     * than as base64 in the outbox, which is rewritten whole on every change:
     * a few photos made each of those writes megabytes.
     */
    val localPath: String? = null,
    /** Set once the image is on the server: then it is sent by path, not by bytes. */
    val uploadedPath: String? = null,
    val uploadId: String = java.util.UUID.randomUUID().toString(),
)

/**
 * A file waiting to go. [localPath] is the app's own copy, so the original
 * can move or vanish; [uploadedPath] is set, and saved, once the server holds
 * it -- so a send that dies after the upload never uploads it again.
 */
@Serializable
data class OutgoingFile(
    val name: String,
    val localPath: String,
    val size: Long,
    val uploadId: String,
    val uploadedPath: String? = null,
)

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
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val items = LinkedHashMap<String, OutgoingMessage>()

    init {
        load()
    }

    /**
     * Read message by message. Reading the whole list at once meant one entry
     * this version could not parse made the queue look empty -- and the next
     * save then wrote that emptiness over every message still waiting. An
     * entry that will not parse is set aside in a file of its own, never
     * overwritten; a file that is not a list at all is kept the same way.
     */
    private fun load() {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val array = runCatching { json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray }.getOrNull()
        if (array == null) {
            keepAside(text)
            return
        }
        val unreadable = mutableListOf<kotlinx.serialization.json.JsonElement>()
        for (element in array) {
            runCatching { json.decodeFromJsonElement(OutgoingMessage.serializer(), element) }
                .onSuccess { items[it.clientMessageId] = it }
                .onFailure { unreadable += element }
        }
        if (unreadable.isNotEmpty()) keepAside(kotlinx.serialization.json.JsonArray(unreadable).toString())
    }

    private fun keepAside(text: String) {
        runCatching { File(file.parentFile, "${file.name}.unreadable-${now()}").writeText(text) }
    }

    /** Everything still waiting, oldest first. */
    @Synchronized
    fun pending(): List<OutgoingMessage> = items.values.toList()

    /** Pending for one chat, for showing them as "sending" in the thread. */
    @Synchronized
    fun pendingFor(sessionId: String): List<OutgoingMessage> = items.values.filter { it.sessionId == sessionId }

    /** Write a new message; it is on disk before this returns. */
    @Synchronized
    fun add(
        sessionId: String,
        text: String,
        images: List<OutgoingImage> = emptyList(),
        files: List<OutgoingFile> = emptyList(),
    ): OutgoingMessage {
        val message = OutgoingMessage(
            clientMessageId = "phone-" + UUID.randomUUID().toString(),
            sessionId = sessionId,
            text = text,
            createdAt = now(),
            images = images,
            files = files,
        )
        items[message.clientMessageId] = message
        persist()
        return message
    }

    /** The server has it, as new or as a duplicate; either way it is done. */
    @Synchronized
    fun confirm(clientMessageId: String) {
        val done = items.remove(clientMessageId) ?: return
        persist()
        // The server has its own copy of every file now.
        done.files.forEach { runCatching { File(it.localPath).delete() } }
        done.images.forEach { image -> image.localPath?.let { runCatching { File(it).delete() } } }
    }

    /** A file of this message is on the server now, at [path]. */
    @Synchronized
    fun fileUploaded(clientMessageId: String, uploadId: String, path: String) {
        val item = items[clientMessageId] ?: return
        items[clientMessageId] = item.copy(files = item.files.map { if (it.uploadId == uploadId) it.copy(uploadedPath = path) else it })
        persist()
    }

    /** The server refused it: kept, marked, shown as not sent. */
    @Synchronized
    fun fail(clientMessageId: String, reason: String) {
        val item = items[clientMessageId] ?: return
        items[clientMessageId] = item.copy(failed = reason)
        persist()
    }

    /** Try a refused message again. */
    @Synchronized
    fun retry(clientMessageId: String) {
        val item = items[clientMessageId] ?: return
        items[clientMessageId] = item.copy(failed = null, attempts = 0)
        persist()
    }

    /** Give up on it, by your choice; its file copies go too. */
    @Synchronized
    fun discard(clientMessageId: String) = confirm(clientMessageId)

    /** An image of this message is on the server now; its inline bytes are no longer kept. */
    @Synchronized
    fun imageUploaded(clientMessageId: String, uploadId: String, path: String) {
        val item = items[clientMessageId] ?: return
        items[clientMessageId] = item.copy(images = item.images.map { if (it.uploadId == uploadId) it.copy(uploadedPath = path, base64 = "") else it })
        persist()
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
