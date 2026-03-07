package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents a user-defined group for organizing chat sessions.
 * Used as a shared data model between frontend and backend API communication.
 *
 * @property id Unique identifier for the group (Database PK).
 * @property name The name of the group.
 * @property createdAt Timestamp when the group was created.
 */
@Serializable
data class ChatGroup(
    val id: Long,
    val name: String,
    val createdAt: Instant
)