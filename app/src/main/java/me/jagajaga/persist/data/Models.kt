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
    /** Super bypass: what the chat still asks for in bypass mode is allowed by the server. */
    val autoApprove: Boolean = false,
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
    /** Download and upload, bytes per second; null until the server has two readings. */
    val network: Network? = null,
    /** Tokens through Claude across every chat (TokenFlow in daemon/src/tokenFlow.ts). */
    val tokens: TokenFlow? = null,
    val sampledAt: Long = 0,
)

@Serializable
data class TokenCounts(
    val input: Long = 0,
    val cacheWrite: Long = 0,
    val cacheRead: Long = 0,
    val output: Long = 0,
    /** input + cacheWrite + output: the new work. */
    val processed: Long = 0,
)

@Serializable
data class TokenCalls(val lastMinute: Int = 0, val lastHour: Int = 0)

@Serializable
data class SessionTokens(val sessionId: String, val processed: Long = 0)

@Serializable
data class TokenFlow(
    val lastMinute: TokenCounts = TokenCounts(),
    val lastHour: TokenCounts = TokenCounts(),
    val calls: TokenCalls = TokenCalls(),
    val bySession: List<SessionTokens> = emptyList(),
)

@Serializable
data class Network(val rxBytesPerSec: Double = 0.0, val txBytesPerSec: Double = 0.0)

/** One process on the server (ProcInfo in daemon/src/processes.ts). */
@Serializable
data class ProcInfo(
    val pid: Int,
    val ppid: Int = 0,
    val user: String = "",
    /** Null on the server's first look: there is nothing yet to measure against. */
    val cpuPercent: Double? = null,
    val rssBytes: Long = 0,
    val swapBytes: Long = 0,
    val ageSeconds: Double = 0.0,
    val command: String = "",
    val cwd: String? = null,
    /** Its working directory is gone: a dev server left behind by a deleted worktree. */
    val orphaned: Boolean = false,
    val descendants: Int = 0,
    val treeRssBytes: Long = 0,
    /** pid 1, code-server or the daemon: killing it would cut this very connection. */
    @kotlinx.serialization.SerialName("protected") val isProtected: Boolean = false,
    val protectedReason: String? = null,
    val role: String? = null,
)

@Serializable
data class ProcessSnapshot(
    val processes: List<ProcInfo> = emptyList(),
    val total: Int = 0,
    /** Every orphan on the server, listed or not: what reapOrphans would free. */
    val orphans: OrphanSummary = OrphanSummary(),
    val sampledAt: Long = 0,
)

@Serializable
data class OrphanSummary(val count: Int = 0, val rssBytes: Long = 0)

@Serializable
data class KillFailure(val pid: Int, val error: String)

@Serializable
data class KillResult(
    val signalled: List<Int> = emptyList(),
    val failed: List<KillFailure> = emptyList(),
    val refused: String? = null,
)

@Serializable
data class ReapResult(val reaped: List<KillResult> = emptyList(), val freedBytes: Long = 0)

/** A model the daemon learned from the SDK (ModelDescriptor in protocol.ts). */
@Serializable
data class ModelDescriptor(
    val value: String,
    val displayName: String = value,
    val resolvedModel: String? = null,
    val description: String? = null,
    val effortLevels: List<String>? = null,
)

@Serializable
data class RateWindow(
    /** Percent, 0..100. */
    val utilization: Double? = null,
    val status: String? = null,
)

@Serializable
data class AccountUsage(val windows: Map<String, RateWindow> = emptyMap(), val at: Long = 0)

/** A Claude login the daemon can run chats under (AccountInfo in protocol.ts). */
@Serializable
data class AccountInfo(
    val name: String,
    val configDir: String? = null,
    val active: Boolean = false,
    val lastUsage: AccountUsage? = null,
    val signedIn: Boolean = true,
    val loginExpired: Boolean = false,
)

@Serializable
data class LoginStarted(val loginId: String, val url: String)

@Serializable
data class LoginResult(val ok: Boolean, val error: String? = null, val configDir: String? = null)

/** The app's newest release, as the server sees it. */
@Serializable
data class AppRelease(val tag: String, val version: String, val name: String, val size: Long, val path: String? = null)

/** A subagent a chat has working (ActiveAgent in protocol.ts). */
@Serializable
data class ActiveAgent(val id: String, val description: String = "", val taskId: String? = null, val kind: String? = null)

/** Plan usage, account-wide (UsageSnapshot in protocol.ts). */
@Serializable
data class UsageSnapshot(
    val windows: Map<String, RateWindow> = emptyMap(),
    val subscriptionType: String? = null,
    val available: Boolean = true,
)

/** Where a chat is working (WorkPlace in gitBranch.ts): its checkout, or an agent worktree. */
@Serializable
data class WorkPlace(
    val name: String,
    val branch: String? = null,
    val path: String = "",
    val worktree: Boolean = false,
    val current: Boolean = false,
)

@Serializable
data class WorkPlaces(val places: List<WorkPlace> = emptyList())

/** A Claude Code conversation on the server that can be brought in as a chat. */
@Serializable
data class ClaudeSessionCandidate(
    val file: String,
    val sdkSessionId: String = "",
    val cwd: String = "",
    val title: String = "",
    val mtimeMs: Double = 0.0,
)

/** One run of text in one colour: colour "#rrggbb", or null for the theme's text colour; style 1 italic, 2 bold, 4 underline. */
data class Span(val text: String, val color: String?, val style: Int)

data class Highlighted(val language: String, val fg: String, val bg: String, val lines: List<List<Span>>, val truncated: Boolean)

/** How freely a chat runs tools: asks, bypasses, or bypasses and allows even what it still asks. */
enum class PermissionLevel(val label: String, val explain: String) {
    Ask("Ask before running tools", "Each tool call waits for you"),
    Bypass("Bypass permissions", "Tools run without asking; a few risky commands still ask"),
    SuperBypass("Super bypass", "Everything is allowed, even what still asks; questions still come to you");

    fun next(): PermissionLevel = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(session: SessionInfo): PermissionLevel = when {
            session.permissionMode != "bypassPermissions" -> Ask
            session.autoApprove -> SuperBypass
            else -> Bypass
        }
    }
}

