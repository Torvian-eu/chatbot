package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Request body for creating a new chat session.
 *
 * @property name Optional name for the new session.
 */
@Serializable
data class CreateSessionRequest(val name: String? = null) // name is optional