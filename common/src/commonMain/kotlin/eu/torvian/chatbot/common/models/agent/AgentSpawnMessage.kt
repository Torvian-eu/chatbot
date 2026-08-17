package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.Serializable

/**
 * Simplified, serializable conversation message used inside an [AgentSpawnRequest].
 *
 * Unlike the full persisted [eu.torvian.chatbot.common.models.core.ChatMessage] hierarchy, this
 * payload deliberately carries only the text content of a user or assistant turn: no ids, no tool
 * calls, no reasoning items. In practice the spawn conversation is a single [User] item carrying
 * the prompt, but the sealed shape is forward-compatible with multi-turn spawn conversations.
 */
@Serializable
sealed interface AgentSpawnMessage {

    /**
     * A user-authored message in the spawned conversation.
     *
     * @property content Text content of the message.
     */
    @Serializable
    data class User(val content: String) : AgentSpawnMessage

    /**
     * An assistant-authored message in the spawned conversation.
     *
     * @property content Text content of the message.
     */
    @Serializable
    data class Assistant(val content: String) : AgentSpawnMessage
}
