package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity

/**
 * Persistence gateway for worker-bound credential profiles.
 *
 * Reads are scoped by worker id, because a profile is only usable from a session on the worker it is
 * bound to and the bootstrap endpoint must never return another worker's rows.
 */
interface WorkerCredentialProfileDao {
    /**
     * Inserts a profile.
     *
     * @param entity Fields of the profile to insert.
     * @return Either a duplicate-name failure or the stored profile.
     */
    suspend fun createProfile(
        entity: CreateWorkerCredentialProfileEntity
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity>

    /**
     * Replaces every operator-authored field of an existing profile.
     *
     * @param id Profile identifier.
     * @param entity Replacement fields.
     * @return Either a failure or the stored profile after the update.
     */
    suspend fun updateProfile(
        id: Long,
        entity: UpdateWorkerCredentialProfileEntity
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity>

    /**
     * Deletes a profile and its signatures; its agent-role links go with the profile by cascade.
     *
     * @param id Profile identifier.
     * @return Either a failure or the profile as it was before deletion.
     */
    suspend fun deleteProfile(id: Long): Either<WorkerCredentialPersistenceError, WorkerCredentialProfileEntity>

    /**
     * @param id Profile identifier.
     * @return The stored profile, or null when no row has that identifier.
     */
    suspend fun getProfileById(id: Long): WorkerCredentialProfileEntity?

    /**
     * @param workerId Worker whose profiles are wanted.
     * @return All profiles bound to the worker, in creation order.
     */
    suspend fun getProfilesByWorkerId(workerId: Long): List<WorkerCredentialProfileEntity>

    /**
     * @param workerId Worker whose enabled profiles are wanted.
     * @return Enabled profiles bound to the worker, in creation order.
     */
    suspend fun getEnabledProfilesByWorkerId(workerId: Long): List<WorkerCredentialProfileEntity>
}
