package eu.torvian.chatbot.common.models.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

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
 * @property fileReferences List of file references attached to this message.
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
    abstract val fileReferences: List<FileReference>

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
     * @property fileReferences List of file references attached to this message.
     */
    @Serializable
    data class UserMessage(
        override val id: Long,
        override val sessionId: Long,
        override val content: String,
        override val createdAt: Instant,
        override val updatedAt: Instant,
        override val parentMessageId: Long?,
        override val childrenMessageIds: List<Long> = emptyList(),
        override val fileReferences: List<FileReference> = emptyList()
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
     * @property fileReferences List of file references attached to this message.
     * @property modelId ID of the LLM model used to generate this message.
     * @property settingsId ID of the settings profile used to generate this message.
     * @property agentRoleId ID of the agent role used to generate this message, when the message was
     *            produced through an agent role. Null for assistant messages not tied to a role.
     * @property reasoningItems For Responses-capable models, the raw reasoning output items
     *            (e.g. `{"type":"reasoning",...}`) emitted alongside this assistant message, used to replay
     *            reasoning context across turns in a stateless fashion. `null` when the model did not emit
     *            reasoning. Each item is an opaque object (may include OpenAI-encrypted content) and must not
     *            be logged or rendered.
     * @property isComplete Whether the generation of this message finished normally. Four combinations are
     *            observable: `true` with no cause means completed (also the meaning of a legacy payload or a
     *            manually inserted/cloned-completed message); `false` with a `null` [incompleteCause] means the
     *            generation is still in flight (streaming placeholder) or was abandoned without a recorded
     *            cause; `false` with [AssistantMessageIncompleteCause.INTERRUPTED_BY_USER] means the user
     *            stopped it; `false` with [AssistantMessageIncompleteCause.FAILED] means it failed and
     *            [errorCode]/[errorMessage] are set. Payloads produced before these fields existed decode as
     *            completed (`isComplete = true`) with no cause, code or reason.
     * @property incompleteCause Machine-readable cause of a non-completion, or `null` when no terminal cause
     *            is known (completed message, in-flight placeholder). Never `null` when [isComplete] is `false`
     *            for a message that reached a terminal state.
     * @property errorCode Machine-readable failure classification, set only together with
     *            [AssistantMessageIncompleteCause.FAILED].
     * @property errorMessage Bounded, user-facing reason for a failure. It is server-authored English that
     *            never contains raw provider bodies or exception text, and it is `null` for a user
     *            interruption (the client localizes that label from [incompleteCause] instead).
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
        override val fileReferences: List<FileReference> = emptyList(),
        val modelId: Long?,
        val settingsId: Long?,
        val agentRoleId: Long? = null,
        val reasoningItems: List<JsonObject>? = null,
        val isComplete: Boolean = true,
        val incompleteCause: AssistantMessageIncompleteCause? = null,
        val errorCode: AssistantMessageErrorCode? = null,
        val errorMessage: String? = null
    ) : ChatMessage() {
        override val role: Role = Role.ASSISTANT

        /**
         * Whether this message ended without completing *and* carries a terminal cause explaining why.
         *
         * Derived on the fly (never serialized) so the notice rule lives in one place: completed messages
         * and in-flight placeholders (no cause) show nothing, while interrupted and failed messages show a
         * notice. This is what lets clients suppress the notice for the streaming message of the active turn
         * without tracking turn state separately.
         */
        val showsIncompleteNotice: Boolean get() = !isComplete && incompleteCause != null
    }

    /**
     * Enum defining the roles of message senders.
     */
    enum class Role {
        USER, ASSISTANT
    }
}
