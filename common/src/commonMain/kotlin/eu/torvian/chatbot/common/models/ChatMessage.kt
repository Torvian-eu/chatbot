package eu.torvian.chatbot.common.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a single message within a chat session.
 *
 * Supports both user and assistant messages and includes threading information.
 * Uses a sealed class structure with a JSON class discriminator based on the 'role' field.
 * Used as a shared data model between frontend and backend API communication.
 *
 * @property id Unique identifier for the message (Database PK).
 * @property sessionId ID of the session this message belongs to (Database FK).
 * @property role The role of the message sender (e.g., "user", "assistant"). Used as the discriminator.
 * @property content The content of the message.
 * @property createdAt Timestamp when the message was created.
 * @property updatedAt Timestamp when the message was last updated (e.g., edited).
 * @property parentMessageId Optional ID of the parent message. Null for root messages of threads.
 * @property childrenMessageIds List of child message IDs. Empty for leaf messages.
 */
@Serializable
sealed class ChatMessage {
    abstract val id: Long
    abstract val sessionId: Long
    abstract val role: Role
    abstract val content: String
    abstract val createdAt: Instant
    abstract val updatedAt: Instant
    abstract val parentMessageId: Long?
    abstract val childrenMessageIds: List<Long>

    /**
     * Represents a message sent by the user.
     *
     * @property id Unique identifier for the message (Database PK).
     * @property sessionId ID of the session this message belongs to (Database FK).
     * @property content The content of the message.
     * @property createdAt Timestamp when the message was created.
     * @property updatedAt Timestamp when the message was last updated (e.g., edited).
     * @property parentMessageId Optional ID of the parent message. Null for root messages of threads.
     * @property childrenMessageIds List of child message IDs. Empty for leaf messages.
     */
    @Serializable
    data class UserMessage(
        override val id: Long,
        override val sessionId: Long,
        override val content: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val parentMessageId: Long?,
        override val childrenMessageIds: List<Long> = emptyList()
    ) : ChatMessage() {
        override val role: Role = Role.USER
    }

    /**
     * Represents a message sent by the assistant (LLM).
     * Includes details about the model and settings used for generation.
     *
     * @property id Unique identifier for the message (Database PK).
     * @property sessionId ID of the session this message belongs to (Database FK).
     * @property content The content of the message.
     * @property createdAt Timestamp when the message was created.
     * @property updatedAt Timestamp when the message was last updated (e.g., edited).
     * @property parentMessageId Optional ID of the parent message. Null for root messages of threads.
     * @property childrenMessageIds List of child message IDs. Empty for leaf messages.
     * @property modelId ID of the LLM model used to generate this message.
     * @property settingsId ID of the settings profile used to generate this message.
     */
    @Serializable
    data class AssistantMessage(
        override val id: Long,
        override val sessionId: Long,
        override val content: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val parentMessageId: Long?,
        override val childrenMessageIds: List<Long> = emptyList(),
        val modelId: Long?,
        val settingsId: Long?
    ) : ChatMessage() {
        override val role: Role = Role.ASSISTANT
    }

    /**
     * Enum defining the roles of message senders.
     */
    enum class Role {
        USER, ASSISTANT
    }
}
