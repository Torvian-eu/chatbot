package eu.torvian.chatbot.server.data.dao

import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileSignatureEntity

/**
 * Persistence gateway for credential-profile signatures.
 *
 * Signatures are keyed by `(profileId, userDeviceId)`, so one device holds at most one signature per
 * profile and a repeated write replaces it.
 */
interface WorkerCredentialProfileSignatureDao {
    /**
     * Inserts or replaces the signature of one device for one profile.
     *
     * @param entity Signature to store.
     * @return The stored signature.
     */
    suspend fun upsertSignature(entity: WorkerCredentialProfileSignatureEntity): WorkerCredentialProfileSignatureEntity

    /**
     * @param profileIds Profiles whose signatures are wanted.
     * @return Every signature row of those profiles, in no particular order.
     */
    suspend fun getSignaturesByProfileIds(profileIds: List<Long>): List<WorkerCredentialProfileSignatureEntity>

    /**
     * Deletes every signature of one profile.
     *
     * @param profileId Profile identifier.
     */
    suspend fun deleteSignaturesByProfileId(profileId: Long)
}
