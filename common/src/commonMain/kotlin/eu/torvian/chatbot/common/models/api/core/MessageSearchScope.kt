package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Defines the server-side scope used for cross-session message search.
 *
 * The default mode intentionally searches only the currently displayed branch of each session so
 * hidden branches are excluded unless the user explicitly asks to search all threads.
 */
@Serializable
enum class MessageSearchScope {
    /**
     * Searches only the messages on the path from each session's current leaf back to its root.
     */
    VISIBLE_THREADS_ONLY,

    /**
     * Searches every message in every session the authenticated user can access.
     */
    ALL_THREADS,
}