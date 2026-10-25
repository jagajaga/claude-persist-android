package me.jagajaga.persist.ui.list

import me.jagajaga.persist.data.SessionInfo

enum class ChatGrouping { Recent, Projects }

/** How the chat list is ordered; kept apart from the composables so it can be tested. */
object ChatOrder {
    /** Newest activity first, across every project. */
    fun recent(sessions: List<SessionInfo>): List<SessionInfo> =
        sessions.sortedByDescending { it.lastActivityAt }

    /** By project, the project with the newest chat first, and newest first within each. */
    fun byProject(sessions: List<SessionInfo>): List<Pair<String, List<SessionInfo>>> =
        sessions.groupBy { it.folder }
            .map { (folder, list) -> folder to recent(list) }
            .sortedByDescending { (_, list) -> list.first().lastActivityAt }

    /** The chats doing something right now, for switching between them. */
    fun active(sessions: List<SessionInfo>): List<SessionInfo> = recent(sessions.filter { it.working })
}
