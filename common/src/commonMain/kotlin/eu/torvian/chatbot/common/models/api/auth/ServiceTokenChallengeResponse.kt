package eu.torvian.chatbot.common.models.api.auth

import eu.torvian.chatbot.common.models.api.worker.WorkerChallengeDto
import kotlinx.serialization.Serializable

/**
 * Response returned when a service-token challenge is issued for a worker.
 *
 * @property workerId Identifier of the worker that owns the challenge.
 * @property challenge One-time challenge that must be signed before token exchange.
 */
@Serializable
data class ServiceTokenChallengeResponse(
    val workerId: Long,
    val challenge: WorkerChallengeDto
)

