package eu.torvian.chatbot.common.models.api.credential

import kotlinx.serialization.Serializable

/**
 * Why a persisted credential change did not reach its worker.
 *
 * Classified rather than free text: the app renders this value, so no worker- or transport-supplied
 * string can reach the operator through it.
 */
@Serializable
enum class WorkerDispatchFailure {
    /** The worker had no live session when the change was dispatched. */
    WORKER_OFFLINE,

    /** The worker acknowledged the command but no terminal result arrived before the timeout. */
    WORKER_TIMEOUT,

    /** The session disappeared before the command completed. */
    WORKER_DISCONNECTED,

    /** The worker refused the command, typically because its build predates the credential commands. */
    WORKER_REJECTED,

    /** The command could not be written to the socket. */
    TRANSPORT_FAILED,

    /** A protocol-level failure made the outcome unreadable. */
    PROTOCOL_ERROR,
}

/**
 * Delivery result of one persisted credential change.
 *
 * A write is committed before the push is attempted, so a failure here means "stored but not yet
 * known to the worker", not "not saved". The next bootstrap replays it, which is why this is a
 * degraded state rather than a failed write.
 *
 * @property workerId Worker the change was pushed to.
 * @property failure Non-null exactly when the change was persisted but not accepted by the worker,
 *   which is also exactly the condition `failure == null` inverts; there is deliberately no second
 *   boolean that could disagree with it.
 */
@Serializable
data class WorkerDispatchOutcomeDto(
    val workerId: Long,
    val failure: WorkerDispatchFailure? = null,
)

/**
 * Result of a persisted profile create or update.
 *
 * A create reports one delivery outcome. A move reports two, because the profile is withdrawn from one
 * worker and created on another, so [dispatch] alone does not describe the whole change.
 *
 * @property profile Stored profile, including its server-assigned id and timestamps.
 * @property dispatch Whether the worker that now owns the profile has learned about the change yet.
 * @property previousWorkerDispatch Outcome of withdrawing the profile from the worker it moved away
 *   from; null when the profile did not move. A failure here means that worker still advertises the
 *   profile and its own copy of the secret until its next bootstrap.
 */
@Serializable
data class CredentialProfileWriteResultDto(
    val profile: CredentialProfileDto,
    val dispatch: WorkerDispatchOutcomeDto,
    val previousWorkerDispatch: WorkerDispatchOutcomeDto? = null,
)

/**
 * Result of a persisted secret create or update.
 *
 * @property secret Stored secret metadata; the value is never part of a response.
 * @property dispatch Whether the worker has learned about the change yet.
 */
@Serializable
data class CredentialSecretWriteResultDto(
    val secret: CredentialSecretMetadataDto,
    val dispatch: WorkerDispatchOutcomeDto,
)

/**
 * Result of a persisted profile or secret delete.
 *
 * @property deletedId Identifier of the row that was removed.
 * @property dispatch Whether the worker has learned about the removal yet.
 */
@Serializable
data class CredentialDeleteResultDto(
    val deletedId: Long,
    val dispatch: WorkerDispatchOutcomeDto,
)
