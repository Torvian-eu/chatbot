package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
