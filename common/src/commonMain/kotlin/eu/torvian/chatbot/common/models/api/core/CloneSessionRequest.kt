package eu.torvian.chatbot.common.models.api.core

import kotlinx.serialization.Serializable

/**
 * Request body for cloning an existing chat session.
 *
 * @property name The name for the cloned session (mandatory).
 */
@Serializable
data class CloneSessionRequest(val name: String)

