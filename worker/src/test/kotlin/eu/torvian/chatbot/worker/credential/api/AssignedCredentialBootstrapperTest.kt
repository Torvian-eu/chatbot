package eu.torvian.chatbot.worker.credential.api

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.AssignedCredentialsDto
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.worker.credential.CredentialProfileValidationResult
import eu.torvian.chatbot.worker.credential.CredentialProfileValidator
import eu.torvian.chatbot.worker.credential.InMemoryWorkerCredentialStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies the credential bootstrap: cache replacement, per-row rejection, the pinned worker id, and
 * the tolerated-failure policy that distinguishes it from the MCP bootstrap.
 */
class AssignedCredentialBootstrapperTest {

    private val api = mockk<WorkerCredentialApi>()
    private val validator = mockk<CredentialProfileValidator>()
    private val store = InMemoryWorkerCredentialStore()

    /**
     * Builds a bootstrapper over the shared fixtures.
     *
     * @param pinnedWorkerId Worker id configured as `worker.identity.workerId`, or null when no pin is
     *   configured.
     * @return Bootstrapper under test.
     */
    private fun bootstrapper(pinnedWorkerId: Long? = null): AssignedCredentialBootstrapper =
        AssignedCredentialBootstrapper(api, validator, store, pinnedWorkerId)

    /**
     * Ensures authorized rows populate the cache and the counts are not inflated by rejections.
     */
    @Test
    fun `bootstrap caches authorized profiles and secrets`() = runTest {
        coEvery { api.getAssignedCredentials() } returns AssignedCredentialsDto(
            workerId = 2L,
            profiles = listOf(signedProfile(id = 1L, name = "vps")),
            secrets = listOf(secret(id = 5L, reference = "vps_password")),
        ).right()
        coEvery { validator.validate(any(), any(), 2L) } returns CredentialProfileValidationResult.Authorized

        val result = bootstrapper().bootstrap()

        assertIs<arrow.core.Either.Right<Unit>>(result)
        assertEquals("vps", store.profile("vps")?.name)
        assertEquals("vps_password", store.secret("vps_password")?.reference)
    }

    /**
     * Ensures one rejected profile does not block the rest of the payload.
     */
    @Test
    fun `bootstrap skips a rejected profile and keeps the others`() = runTest {
        coEvery { api.getAssignedCredentials() } returns AssignedCredentialsDto(
            workerId = 2L,
            profiles = listOf(
                signedProfile(id = 1L, name = "bad"),
                signedProfile(id = 2L, name = "good"),
            ),
            secrets = emptyList(),
        ).right()
        coEvery { validator.validate(match { it.name == "bad" }, any(), 2L) } returns
            CredentialProfileValidationResult.MissingSignedRequest
        coEvery { validator.validate(match { it.name == "good" }, any(), 2L) } returns
            CredentialProfileValidationResult.Authorized

        val result = bootstrapper().bootstrap()

        assertIs<arrow.core.Either.Right<Unit>>(result)
        assertNull(store.profile("bad"))
        assertEquals(2L, store.profile("good")?.id)
    }

    /**
     * Ensures a secret bound to another worker is skipped.
     */
    @Test
    fun `bootstrap skips a secret bound to another worker`() = runTest {
        coEvery { api.getAssignedCredentials() } returns AssignedCredentialsDto(
            workerId = 2L,
            profiles = emptyList(),
            secrets = listOf(secret(id = 5L, reference = "mine"), secret(id = 6L, reference = "theirs", workerId = 3L)),
        ).right()

        bootstrapper().bootstrap()

        assertEquals("mine", store.secret("mine")?.reference)
        assertNull(store.secret("theirs"))
    }

    /**
     * Ensures a response whose worker id matches the pin caches its rows.
     */
    @Test
    fun `bootstrap caches rows when the response agrees with the pin`() = runTest {
        coEvery { api.getAssignedCredentials() } returns AssignedCredentialsDto(
            workerId = 2L,
            profiles = listOf(signedProfile(id = 1L, name = "vps")),
            secrets = listOf(secret(id = 5L, reference = "vps_password")),
        ).right()
        coEvery { validator.validate(any(), any(), 2L) } returns CredentialProfileValidationResult.Authorized

        val result = bootstrapper(pinnedWorkerId = 2L).bootstrap()

        assertIs<arrow.core.Either.Right<Unit>>(result)
        assertEquals("vps", store.profile("vps")?.name)
        assertEquals("vps_password", store.secret("vps_password")?.reference)
    }

    /**
     * Ensures a response whose worker id contradicts the pin reports the mismatch and caches nothing.
     */
    @Test
    fun `bootstrap reports a pinned worker id mismatch without caching`() = runTest {
        coEvery { api.getAssignedCredentials() } returns AssignedCredentialsDto(
            workerId = 9L,
            profiles = listOf(signedProfile(id = 1L, name = "vps")),
            secrets = listOf(secret(id = 5L, reference = "vps_password", workerId = 9L)),
        ).right()

        val result = bootstrapper(pinnedWorkerId = 2L).bootstrap()

        val error = assertIs<AssignedCredentialBootstrapError.WorkerIdMismatch>(
            assertIs<arrow.core.Either.Left<AssignedCredentialBootstrapError>>(result).value
        )
        assertEquals(2L, error.expected)
        assertEquals(9L, error.actual)
        assertEquals(emptyList(), store.profiles())
        assertNull(store.secret("vps_password"), "a row was cached despite the pinned-id disagreement")
    }

    /**
     * Ensures an absent pin still skips a row the response itself labels for another worker.
     */
    @Test
    fun `without a pin the response's own rows are cached and foreign rows are skipped`() = runTest {
        coEvery { api.getAssignedCredentials() } returns AssignedCredentialsDto(
            workerId = 2L,
            profiles = emptyList(),
            secrets = listOf(secret(id = 5L, reference = "mine"), secret(id = 6L, reference = "theirs", workerId = 3L)),
        ).right()

        val result = bootstrapper(pinnedWorkerId = null).bootstrap()

        assertIs<arrow.core.Either.Right<Unit>>(result)
        assertEquals("mine", store.secret("mine")?.reference)
        assertNull(store.secret("theirs"))
    }

    /**
     * Ensures a fetch failure is returned as a Left instead of failing silently, so the caller can log
     * it and continue.
     */
    @Test
    fun `bootstrap surfaces fetch failures`() = runTest {
        coEvery { api.getAssignedCredentials() } returns
            WorkerCredentialApiError.UnexpectedHttpStatus("fetch", 404).left()

        val result = bootstrapper().bootstrap()

        assertIs<AssignedCredentialBootstrapError.FetchFailed>(
            assertIs<arrow.core.Either.Left<AssignedCredentialBootstrapError>>(result).value
        )
        assertEquals(emptyList(), store.profiles())
    }

    /**
     * @param id Profile identifier.
     * @param name Profile name.
     * @return A relayed profile with a detached signature.
     */
    private fun signedProfile(id: Long, name: String): SignedCredentialProfileDto = SignedCredentialProfileDto(
        profile = CredentialProfileDto(
            id = id,
            workerId = 2L,
            name = name,
            secretRef = "vps_password",
            command = "/bin/sh",
            channel = CredentialChannel.ASKPASS,
            askpassVariables = listOf("SSH_ASKPASS"),
            updatedAt = Instant.fromEpochMilliseconds(0),
        ),
        signedRequest = SignedRequest(
            payload = "{}",
            signature = "sig",
            signerId = "device-1",
            timestamp = 0L,
            nonce = "nonce"
        ),
    )

    /**
     * @param id Secret identifier.
     * @param reference Secret reference.
     * @param workerId Worker the row is bound to.
     * @return An encrypted secret row.
     */
    private fun secret(id: Long, reference: String, workerId: Long = 2L): WorkerCredentialSecretDto =
        WorkerCredentialSecretDto(
            id = id,
            workerId = workerId,
            reference = reference,
            encryptedSecret = "cipher",
            encryptedDek = "dek",
            algorithm = "RSA-OAEP-256+A256GCM",
            workerCertificateFingerprint = "fp",
            updatedAt = Instant.fromEpochMilliseconds(0),
        )
}
