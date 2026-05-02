package eu.torvian.chatbot.common.models.api.worker

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * One-time challenge payload used for worker signature proof.
 *
 * @property challengeId Unique identifier used to consume the challenge after verification.
 * @property challenge Plain-text challenge string to be signed by the worker private key.
 * @property expiresAt Expiration timestamp for this challenge.
 */
@Serializable
data class WorkerChallengeDto(
    val challengeId: String,
    val challenge: String,
    val expiresAt: Instant
)

