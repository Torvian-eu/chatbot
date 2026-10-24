package eu.torvian.chatbot.worker.credential

import java.util.concurrent.ConcurrentHashMap

/**
 * Worker-local, memory-only cache of server-assigned credential profiles and encrypted secrets.
 *
 * Nothing here is persisted: profiles and ciphertext live for the process lifetime and are replaced
 * wholesale by each bootstrap, so a reconnect reconciles the cache without touching a live attached
 * target.
 */
interface WorkerCredentialStore {
    /**
     * Replaces the whole cache, as done by the bootstrap.
     *
     * @param profiles Validated profiles assigned to this worker.
     * @param secrets Encrypted secrets assigned to this worker.
     */
    fun replaceAll(profiles: List<CredentialProfile>, secrets: List<EncryptedWorkerSecret>)

    /**
     * @param name Profile name as named by the model.
     * @return The cached profile, or null when no assignment has that name.
     */
    fun profile(name: String): CredentialProfile?

    /**
     * Adds or replaces one profile by its server id.
     *
     * @param profile Profile to cache.
     */
    fun upsertProfile(profile: CredentialProfile)

    /**
     * Drops the cached profile whose server id is `id`; an unknown id is ignored.
     *
     * Used for delete notifications and for the withdrawal half of a cross-worker move, so a profile the
     * server no longer assigns to this worker stops being attachable here.
     *
     * @param id Server-side profile identifier from a delete notification.
     */
    fun removeProfile(id: Long)

    /**
     * Adds or replaces one encrypted secret by its server id.
     *
     * @param secret Encrypted secret to cache.
     */
    fun upsertSecret(secret: EncryptedWorkerSecret)

    /**
     * Drops the cached encrypted secret whose server id is `id`; an unknown id is ignored.
     *
     * A profile that still references the removed secret keeps its advertisement but fails to attach,
     * which is the same outcome as a reference the bootstrap never delivered.
     *
     * @param id Server-side secret identifier from a delete notification.
     */
    fun removeSecret(id: Long)

    /**
     * @return Snapshot of the cached profiles, in insertion-independent order.
     */
    fun profiles(): List<CredentialProfile>

    /**
     * @param reference Reference a profile uses to resolve its secret.
     * @return The cached encrypted secret, or null when it was deleted or never assigned.
     */
    fun secret(reference: String): EncryptedWorkerSecret?
}

/**
 * Default [WorkerCredentialStore], backed by concurrent maps.
 *
 * Profiles are indexed by name for lookup and by id for notifications; secrets are indexed by
 * reference because that is how a profile names them.
 */
class InMemoryWorkerCredentialStore : WorkerCredentialStore {
    private val profilesByName = ConcurrentHashMap<String, CredentialProfile>()
    private val profileIdsByName = ConcurrentHashMap<String, Long>()
    private val secretsByReference = ConcurrentHashMap<String, EncryptedWorkerSecret>()
    private val secretIdsByReference = ConcurrentHashMap<String, Long>()

    override fun replaceAll(profiles: List<CredentialProfile>, secrets: List<EncryptedWorkerSecret>) {
        profilesByName.clear()
        profileIdsByName.clear()
        secretsByReference.clear()
        secretIdsByReference.clear()
        profiles.forEach(::upsertProfile)
        secrets.forEach(::upsertSecret)
    }

    override fun profile(name: String): CredentialProfile? = profilesByName[name]

    override fun upsertProfile(profile: CredentialProfile) {
        // A renamed profile would otherwise stay reachable under its old name.
        profileIdsByName.keys.filter { name -> profileIdsByName[name] == profile.id }.forEach { name ->
            profilesByName.remove(name)
            profileIdsByName.remove(name)
        }
        profilesByName[profile.name] = profile
        profileIdsByName[profile.name] = profile.id
    }

    override fun removeProfile(id: Long) {
        val names = profileIdsByName.entries.filter { it.value == id }.map { it.key }
        names.forEach { name ->
            profilesByName.remove(name)
            profileIdsByName.remove(name)
        }
    }

    override fun upsertSecret(secret: EncryptedWorkerSecret) {
        // A replaced row must not stay reachable under a reference it no longer uses.
        secretIdsByReference.keys
            .filter { reference -> secretIdsByReference[reference] == secret.id }
            .forEach { reference ->
                secretsByReference.remove(reference)
                secretIdsByReference.remove(reference)
            }
        secretsByReference[secret.reference] = secret
        secretIdsByReference[secret.reference] = secret.id
    }

    override fun removeSecret(id: Long) {
        val references = secretIdsByReference.entries.filter { it.value == id }.map { it.key }
        references.forEach { reference ->
            secretsByReference.remove(reference)
            secretIdsByReference.remove(reference)
        }
    }

    override fun profiles(): List<CredentialProfile> = profilesByName.values.toList()

    override fun secret(reference: String): EncryptedWorkerSecret? = secretsByReference[reference]
}
