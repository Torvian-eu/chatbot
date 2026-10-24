package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity

/**
 * Persistence gateway for worker-bound credential secrets.
 *
 * Every method handles ciphertext only: no read returns the value, and the worker binding is fixed at
 * insert because the server cannot re-encrypt.
 */
interface WorkerCredentialSecretDao {
    /**
     * Inserts an encrypted secret.
     *
     * @param entity Ciphertext and metadata to insert.
     * @return Either a duplicate-reference failure or the stored secret.
     */
    suspend fun createSecret(
        entity: CreateWorkerCredentialSecretEntity
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity>

    /**
     * Replaces the ciphertext and metadata of an existing secret.
     *
     * @param id Secret identifier.
     * @param entity Replacement ciphertext and metadata.
     * @return Either a failure or the stored secret after the update.
     */
    suspend fun updateSecret(
        id: Long,
        entity: UpdateWorkerCredentialSecretEntity
    ): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity>

    /**
     * Deletes a secret.
     *
     * Profiles that reference the deleted reference are deliberately left in place.
     *
     * @param id Secret identifier.
     * @return Either a failure or the secret as it was before deletion.
     */
    suspend fun deleteSecret(id: Long): Either<WorkerCredentialPersistenceError, WorkerCredentialSecretEntity>

    /**
     * @param id Secret identifier.
     * @return The stored secret, or null when no row has that identifier.
     */
    suspend fun getSecretById(id: Long): WorkerCredentialSecretEntity?

    /**
     * @param workerId Worker whose secrets are wanted.
     * @return All secrets bound to the worker, in creation order.
     */
    suspend fun getSecretsByWorkerId(workerId: Long): List<WorkerCredentialSecretEntity>

    /**
     * @param workerId Worker the reference belongs to.
     * @param reference Reference to resolve.
     * @return The stored secret, or null when the worker has no row with that reference.
     */
    suspend fun getSecretByWorkerIdAndReference(workerId: Long, reference: String): WorkerCredentialSecretEntity?
}
