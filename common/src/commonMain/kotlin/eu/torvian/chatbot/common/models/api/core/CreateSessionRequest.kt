package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new chat session.
 *
 * @property name Name for the new session. Must be non-blank.
 */
@Serializable
data class CreateSessionRequest(val name: String)
