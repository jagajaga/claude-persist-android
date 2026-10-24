package me.jagajaga.persist.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A chat, as the daemon describes it (SessionInfo in shared/src/protocol.ts). */
@Serializable
data class SessionInfo(
    val id: String,
    val title: String,
    val cwd: String,
    val status: String = "idle",
    val permissionMode: String = "default",
    val model: String? = null,
    val activeModel: String? = null,
    val effort: String? = null,
    val createdAt: Long = 0,
    val lastActivityAt: Long = 0,
    val eventCount: Int = 0,
    val busy: Boolean = false,
    val retryAt: Long? = null,
) {
    /** Working, whatever `status` says: a turn, or subagents still going. */
    val working: Boolean get() = status == "running" || busy

    /** The folder name, as the editor's sidebar groups by. */
    val folder: String get() = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
}

@Serializable
data class AttachResult(
    val info: SessionInfo? = null,
    val events: List<JsonObject> = emptyList(),
    val hasEarlier: Boolean = false,
)

@Serializable
data class FeedItem(val sessionId: String, val title: String, val event: JsonObject)

@Serializable
data class FsEntry(
    val name: String,
    val kind: String,
    val size: Long = 0,
    val mtimeMs: Double = 0.0,
    val target: String? = null,
    val targetKind: String? = null,
) {
    val isDir: Boolean get() = kind == "dir" || (kind == "link" && targetKind == "dir")
}

@Serializable
data class FsListing(
    val path: String,
    val parent: String? = null,
    val entries: List<FsEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class FsRead(
    val path: String,
    val size: Long,
    val offset: Long,
    val encoding: String,
    val data: String,
    val length: Long,
    val eof: Boolean,
)

@Serializable
data class Memory(val totalBytes: Long = 0, val availableBytes: Long = 0)

@Serializable
data class Swap(val totalBytes: Long = 0, val freeBytes: Long = 0)

@Serializable
data class Disk(val path: String = "", val totalBytes: Long = 0, val freeBytes: Long = 0)

@Serializable
data class ServerStats(
    val cpuPercent: Double? = null,
    val cores: Int = 0,
    val load: List<Double> = listOf(0.0, 0.0, 0.0),
    val memory: Memory = Memory(),
    val swap: Swap = Swap(),
    val disk: Disk? = null,
    val uptimeSeconds: Double = 0.0,
    val processes: Int? = null,
    val sampledAt: Long = 0,
)
