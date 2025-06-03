package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a single chat session or conversation thread.
 * Used as a shared data model between frontend and backend.
 *
 * @property id Unique identifier for the session (Database PK).
 * @property name The name or title of the session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated (e.g., message added).
 * @property groupId Optional ID referencing a parent group session.
 * @property currentModelId Optional ID of the currently selected LLM model for this session.
 * @property currentSettingsId Optional ID of the currently selected settings profile for this session.
 * @property currentLeafMessageId The current leaf message in the session, used for displaying the
 *                                correct branch in the UI. (Null only when no messages exist)
 * @property messages List of messages within this session (included when loading full details).
 */
@Serializable
data class ChatSession(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: Long?,
    val currentModelId: Long?,
    val currentSettingsId: Long?,
    val currentLeafMessageId: Long?,
    val messages: List<ChatMessage> = emptyList()
)

/**
 * Represents a summary of a chat session, typically used for listing sessions
 * without loading all message details.
 * Used as a shared data model between frontend and backend.
 *
 * @property id Unique identifier for the session (Database PK).
 * @property name The name or title of the session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated.
 * @property groupId Optional ID referencing a parent group session.
 * @property groupName Optional name of the group, if applicable.
 */
@Serializable
data class ChatSessionSummary(
    val id: Long,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: Long?,
    val groupName: String? = null
)