package eu.torvian.chatbot.common.models.api.worker.protocol.payload

import eu.torvian.chatbot.common.security.SignedRequest
import kotlinx.serialization.Serializable

/**
 * Signed authorization envelope for a built-in tool execution request.
 *
 * The detached [signedRequest] carries the exact serialized
 * [BuiltInToolExecutionAuthorization] which the worker decodes and validates.
 *
 * @property signedRequest Detached signature metadata and the exact authorization payload signed by the app.
 */
@Serializable
data class SignedBuiltInToolExecutionRequest(
    val signedRequest: SignedRequest
)

