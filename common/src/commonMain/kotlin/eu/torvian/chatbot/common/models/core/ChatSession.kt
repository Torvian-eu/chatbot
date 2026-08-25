package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Maximum number of characters allowed in a chat session name.
 *
 * This mirrors the `name` varchar(255) column of the server's `chat_sessions` table
 * (see `ChatSessionTable`). It is shared by the UI (text field limits and view-model guards)
 * and the server (validation), so both layers stay consistent with the database schema.
 */
const val MAX_SESSION_NAME_LENGTH = 255

/**
 * Represents a single chat session or conversation thread.
 * Used as a shared data model between frontend and backend communication.
 *
 * A session no longer stores its own model/settings selection: it references an agent role (when one
 * is selected) that bundles the model, settings profile, tools and composed system prompt. Model,
 * settings and tools are resolved from that role at turn-preparation time.
 *
 * @property id Unique identifier for the session (Database PK).
 * @property name The name or title of the session.
 * @property createdAt Timestamp when the session was created.
 * @property updatedAt Timestamp when the session was last updated (e.g., message added).
 * @property groupId Optional ID referencing a parent group session.
 * @property agentRoleId Optional ID of the user-defined agent role selected for this session. `null`
 *            means no role is selected and the session cannot send messages until one is selected.
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
    val agentRoleId: Long?,
    val currentLeafMessageId: Long?,
    val messages: List<ChatMessage> = emptyList()
)
