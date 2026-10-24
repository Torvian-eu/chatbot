package eu.torvian.chatbot.worker.credential.api

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.worker.credential.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Fetches assigned credential profiles and encrypted secrets and populates the worker cache.
 *
 * Failures are surfaced rather than swallowed, so the caller decides what an unreachable or unusable
 * assignment means. [AssignedCredentialBootstrapError.WorkerIdMismatch] is separated from the rest
 * because it must be fatal: it means the server's notion of this worker is not the owner's.
 *
 * @property api HTTP client for the credential bootstrap endpoint.
 * @property profileValidator Authorization validator applied before a profile is cached.
 * @property store Cache populated with the accepted rows.
 * @property pinnedWorkerId Worker id configured as `worker.identity.workerId`, or null when no pin is
 *   configured and the response's own id is therefore not cross-checked.
 */
class AssignedCredentialBootstrapper(
    private val api: WorkerCredentialApi,
    private val profileValidator: CredentialProfileValidator,
    private val store: WorkerCredentialStore,
    private val pinnedWorkerId: Long?,
) {

    /**
     * Fetches the assignment and replaces the cache with every row that passes validation.
     *
     * @return Either a fetch failure, a pinned-id disagreement, or `Unit`. A partially rejected payload
     *   still returns success, because one unusable row must not hide the rest.
     */
    suspend fun bootstrap(): Either<AssignedCredentialBootstrapError, Unit> {
        logger.info("Bootstrapping assigned credential profiles and secrets")
        val assigned = api.getAssignedCredentials().fold(
            ifLeft = { apiError ->
                logger.warn("Failed to fetch assigned credentials: {}", apiError)
                return AssignedCredentialBootstrapError.FetchFailed(apiError).left()
            },
            ifRight = { it }
        )

        // Checked before anything is cached: the caller ends the process on a disagreement, so no row
        // from a response whose worker identity the owner did not pin may enter the cache.
        val pinned = pinnedWorkerId
        if (pinned != null && assigned.workerId != pinned) {
            return AssignedCredentialBootstrapError.WorkerIdMismatch(
                expected = pinned,
                actual = assigned.workerId
            ).left()
        }

        val profiles = buildList {
            assigned.profiles.forEach { signedProfile ->
                val accepted = validate(signedProfile, assigned.workerId)
                if (accepted != null) add(accepted.toCredentialProfile())
            }
        }
        val secrets = buildList {
            assigned.secrets.forEach { secret ->
                if (acceptSecret(secret, assigned.workerId)) add(secret.toEncryptedWorkerSecret())
            }
        }

        store.replaceAll(profiles, secrets)
        logger.info(
            "Credential bootstrap cached {} profiles and {} secrets for worker {} out of {} and {} received",
            profiles.size,
            secrets.size,
            assigned.workerId,
            assigned.profiles.size,
            assigned.secrets.size
        )
        return Unit.right()
    }

    /**
     * @param signedProfile Relayed profile plus its detached signature.
     * @param workerId Worker id resolved by the server for this response.
     * @return The profile when it is authorized, or null when it must be skipped.
     */
    private suspend fun validate(
        signedProfile: SignedCredentialProfileDto,
        workerId: Long,
    ): eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto? =
        when (
            val result = profileValidator.validate(
                profile = signedProfile.profile,
                signedRequest = signedProfile.signedRequest,
                workerId = workerId,
            )
        ) {
            CredentialProfileValidationResult.Authorized -> signedProfile.profile
            is CredentialProfileValidationResult.Rejected -> {
                logger.warn(
                    "Rejected assigned credential profile {} during bootstrap (code={}, message={}, details={})",
                    signedProfile.profile.id,
                    result.code,
                    result.message,
                    result.details
                )
                null
            }
        }

    /**
     * Accepts an unsigned secret row after the worker-id check alone.
     *
     * @param secret Relayed encrypted secret.
     * @param workerId Worker id resolved by the server for this response.
     * @return Whether the row belongs to this worker.
     */
    private fun acceptSecret(secret: WorkerCredentialSecretDto, workerId: Long): Boolean {
        if (secret.workerId != workerId) {
            // Catches a server-side join or pagination mistake; it cannot detect a hostile server.
            logger.warn(
                "Rejected assigned credential secret {} because it is bound to worker {} while the response resolved worker {}",
                secret.id,
                secret.workerId,
                workerId
            )
            return false
        }
        return true
    }

    private companion object {
        /** Logger used for bootstrap diagnostics; never receives ciphertext or plaintext. */
        val logger: Logger = LogManager.getLogger(AssignedCredentialBootstrapper::class.java)
    }
}

/**
 * Errors that can occur during the credential bootstrap.
 */
sealed interface AssignedCredentialBootstrapError {
    /**
     * The assignment could not be fetched.
     *
     * @property apiError Underlying API failure.
     */
    data class FetchFailed(val apiError: WorkerCredentialApiError) : AssignedCredentialBootstrapError

    /**
     * The server resolved a different worker id than the one the owner pinned.
     *
     * The caller must treat this as fatal rather than as a fetch failure: a disagreement means the
     * server's notion of this worker is not the one the owner provisioned, so no row from the response
     * may be cached and no session may be opened under it.
     *
     * @property expected Worker id configured as `worker.identity.workerId`.
     * @property actual Worker id the bootstrap response resolved.
     */
    data class WorkerIdMismatch(val expected: Long, val actual: Long) : AssignedCredentialBootstrapError
}
