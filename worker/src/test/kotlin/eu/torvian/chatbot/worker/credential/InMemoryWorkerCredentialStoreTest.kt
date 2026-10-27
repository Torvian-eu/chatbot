package eu.torvian.chatbot.worker.credential

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies the in-memory credential cache, including rename and delete bookkeeping.
 */
class InMemoryWorkerCredentialStoreTest {

    private val store = InMemoryWorkerCredentialStore()

    /**
     * Ensures a bootstrap replaces the whole cache rather than merging into it.
     */
    @Test
    fun `replaceAll drops rows that are no longer assigned`() {
        store.replaceAll(listOf(profile(id = 1L, name = "old")), listOf(secret(id = 1L, reference = "old")))

        store.replaceAll(listOf(profile(id = 2L, name = "new")), listOf(secret(id = 2L, reference = "new")))

        assertNull(store.profile("old"))
        assertEquals(2L, store.profile("new")?.id)
        assertNull(store.secret("old"))
        assertEquals(2L, store.secret("new")?.id)
    }

    /**
     * Ensures a renamed profile is reachable under its new name only.
     */
    @Test
    fun `upsertProfile replaces the name index of the same id`() {
        store.upsertProfile(profile(id = 1L, name = "old"))

        store.upsertProfile(profile(id = 1L, name = "renamed"))

        assertNull(store.profile("old"))
        assertEquals(1L, store.profile("renamed")?.id)
        assertEquals(1, store.profiles().size)
    }

    /**
     * Ensures a delete notification removes the profile by server id.
     */
    @Test
    fun `removeProfile drops the row by id`() {
        store.upsertProfile(profile(id = 7L, name = "vps"))

        store.removeProfile(7L)

        assertNull(store.profile("vps"))
        assertEquals(emptyList(), store.profiles())
    }

    /**
     * Ensures a secret is reachable by its reference and droppable by id.
     */
    @Test
    fun `secret is keyed by reference and removable by id`() {
        val secret = secret(id = 9L, reference = "vps_password")
        store.upsertSecret(secret)

        assertSame(secret, store.secret("vps_password"))

        store.removeSecret(9L)

        assertNull(store.secret("vps_password"))
    }

    /**
     * Ensures a renamed secret reference does not keep the old key alive.
     */
    @Test
    fun `upsertSecret moves the reference index of the same id`() {
        store.upsertSecret(secret(id = 9L, reference = "old"))

        store.upsertSecret(secret(id = 9L, reference = "new"))

        assertNull(store.secret("old"))
        assertEquals("new", store.secret("new")?.reference)
    }

    /**
     * @param id Profile identifier.
     * @param name Profile name.
     * @return A minimal askpass profile.
     */
    private fun profile(id: Long, name: String): CredentialProfile = CredentialProfile(
        id = id,
        workerId = 1L,
        name = name,
        description = null,
        secretRef = "ref",
        command = "/bin/sh",
        args = emptyList(),
        channel = eu.torvian.chatbot.common.models.api.credential.CredentialChannel.ASKPASS,
        envName = null,
        fileName = null,
        askpassUsername = null,
        isEnabled = true,
        environmentVariables = emptyMap(),
        askpassVariables = listOf("SSH_ASKPASS"),
        agentRoleIds = emptySet(),
    )

    /**
     * @param id Secret identifier.
     * @param reference Secret reference.
     * @return An encrypted secret row.
     */
    private fun secret(id: Long, reference: String): EncryptedWorkerSecret = EncryptedWorkerSecret(
        id = id,
        workerId = 1L,
        reference = reference,
        encryptedSecret = "cipher",
        encryptedDek = "dek",
        algorithm = "RSA-OAEP-256+A256GCM",
        workerCertificateFingerprint = "fp",
    )
}
