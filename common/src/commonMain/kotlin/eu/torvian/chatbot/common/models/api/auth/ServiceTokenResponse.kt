package eu.torvian.chatbot.common.models.api.auth

import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Response body returned after a worker successfully exchanges a signed challenge for an access token.
 *
 * @property accessToken JWT access token that authorizes worker requests.
 * @property expiresAt Expiration timestamp for the returned access token.
 * @property worker Worker metadata associated with the issued token.
 */
@Serializable
data class ServiceTokenResponse(
    val accessToken: String,
    val expiresAt: Instant,
    val worker: WorkerDto
)

