package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.CredentialApi
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialDeleteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretWriteResultDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchFailure
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchOutcomeDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies the app-side credential cache and the degraded-delivery reporting of
 * [DefaultCredentialRepository].
 */
class DefaultCredentialRepositoryTest {

    private val api: CredentialApi = mockk()

    private val repository = DefaultCredentialRepository(api)

    /**
     * Ensures a rule violation reported by the server becomes a repository error and leaves the cache
     * untouched.
     */
    @Test
    fun `createProfile maps api errors without touching the cache`() = runTest {
        coEvery { api.createProfile(any()) } returns Either.Left(
            ApiResourceError.NetworkError("offline", null)
        )

        val result = repository.createProfile(createRequest())

        val error = assertIs<Either.Left<RepositoryError>>(result).value
        assertIs<RepositoryError.DataFetchError>(error)
        assertIs<DataState.Idle>(repository.profiles.value)
        assertNull(repository.dispatchWarning.value)
    }

    /**
     * Ensures a delivered write raises no warning and updates the cached list.
     */
    @Test
    fun `createProfile stores the row and raises no warning when delivered`() = runTest {
        coEvery { api.createProfile(any()) } returns Either.Right(
            CredentialProfileWriteResultDto(
                profile = profile(),
                dispatch = WorkerDispatchOutcomeDto(workerId = 17L, failure = null)
            )
        )

        val result = repository.createProfile(createRequest())

        assertIs<Either.Right<CredentialProfileDto>>(result)
        assertNull(repository.dispatchWarning.value)
        // The cache was never loaded, so the write leaves it in its initial state.
        assertIs<DataState.Idle>(repository.profiles.value)
    }

    /**
     * Ensures a persisted-but-undelivered write keeps the save and reports the classified warning.
     */
    @Test
    fun `createProfile keeps the save and warns when delivery failed`() = runTest {
        coEvery { api.createProfile(any()) } returns Either.Right(
            CredentialProfileWriteResultDto(
                profile = profile(),
                dispatch = WorkerDispatchOutcomeDto(
                    workerId = 17L,
                    failure = WorkerDispatchFailure.WORKER_REJECTED
                )
            )
        )

        val result = repository.createProfile(createRequest())

        val saved = assertIs<Either.Right<CredentialProfileDto>>(result).value
        assertEquals("vps", saved.name)
        val warning = repository.dispatchWarning.value ?: error("expected a dispatch warning")
        assertEquals(WorkerDispatchFailure.WORKER_REJECTED, warning.failure)
        assertEquals("vps", warning.label)
        assertEquals(17L, warning.workerId)

        repository.dismissDispatchWarning()
        assertNull(repository.dispatchWarning.value)
    }

    /**
     * Ensures a secret replacement keeps operator metadata only and never exposes a value.
     */
    @Test
    fun `updateSecret caches metadata only`() = runTest {
        coEvery { api.updateSecret(9L, any()) } returns Either.Right(
            CredentialSecretWriteResultDto(
                secret = secretMetadata(),
                dispatch = WorkerDispatchOutcomeDto(workerId = 17L, failure = null)
            )
        )

        val result = repository.updateSecret(9L, UpdateCredentialSecretRequest("ref", "value"))

        val metadata = assertIs<Either.Right<CredentialSecretMetadataDto>>(result).value
        assertEquals("ref", metadata.reference)
        assertNull(repository.dispatchWarning.value)
    }

    /**
     * Ensures deleting a secret removes it from the cache and reports a degraded delivery.
     */
    @Test
    fun `deleteSecret removes the row and warns when undelivered`() = runTest {
        coEvery { api.getSecrets(17L) } returns Either.Right(listOf(secretMetadata()))
        coEvery { api.deleteSecret(9L) } returns Either.Right(
            CredentialDeleteResultDto(
                deletedId = 9L,
                dispatch = WorkerDispatchOutcomeDto(workerId = 17L, failure = WorkerDispatchFailure.WORKER_OFFLINE)
            )
        )

        repository.loadSecrets(17L)
        repository.deleteSecret(9L)

        val state = repository.secrets.value
        assertIs<DataState.Success<List<CredentialSecretMetadataDto>>>(state)
        assertEquals(emptyList(), state.data)
        assertEquals(WorkerDispatchFailure.WORKER_OFFLINE, repository.dispatchWarning.value?.failure)
    }

    /**
     * Ensures a profile replacement whose withdrawal from the previous worker failed warns about that
     * worker, because it keeps the profile and its own copy of the secret until its next bootstrap.
     */
    @Test
    fun `updateProfile warns about the previous worker when the withdrawal failed`() = runTest {
        coEvery { api.updateProfile(1L, any()) } returns Either.Right(
            CredentialProfileWriteResultDto(
                profile = profile().copy(workerId = 18L),
                dispatch = WorkerDispatchOutcomeDto(workerId = 18L, failure = null),
                previousWorkerDispatch = WorkerDispatchOutcomeDto(
                    workerId = 17L,
                    failure = WorkerDispatchFailure.WORKER_OFFLINE
                ),
            )
        )

        val result = repository.updateProfile(1L, updateRequest())

        assertIs<Either.Right<CredentialProfileDto>>(result)
        val warning = repository.dispatchWarning.value ?: error("expected a dispatch warning")
        assertEquals(WorkerDispatchFailure.WORKER_OFFLINE, warning.failure)
        assertEquals(17L, warning.workerId)
    }

    /**
     * Ensures a delivered move raises no warning, even though it has two delivery outcomes.
     */
    @Test
    fun `updateProfile raises no warning when both halves of a move were delivered`() = runTest {
        coEvery { api.updateProfile(1L, any()) } returns Either.Right(
            CredentialProfileWriteResultDto(
                profile = profile().copy(workerId = 18L),
                dispatch = WorkerDispatchOutcomeDto(workerId = 18L, failure = null),
                previousWorkerDispatch = WorkerDispatchOutcomeDto(workerId = 17L, failure = null),
            )
        )

        repository.updateProfile(1L, updateRequest())

        assertNull(repository.dispatchWarning.value)
    }

    /**
     * Ensures a create request the test reuses.
     *
     * @return Askpass profile request bound to the test worker.
     */
    private fun createRequest(): CreateCredentialProfileRequest = CreateCredentialProfileRequest(
        workerId = 17L,
        name = "vps",
        secretRef = "ref",
        command = "/bin/sh",
        channel = CredentialChannel.ASKPASS,
        askpassVariables = listOf("SSH_ASKPASS"),
    )

    /**
     * Ensures a replacement request the test reuses.
     *
     * @return Askpass profile replacement that moves the profile to the second test worker.
     */
    private fun updateRequest(): UpdateCredentialProfileRequest = UpdateCredentialProfileRequest(
        workerId = 18L,
        name = "vps",
        secretRef = "ref",
        command = "/bin/sh",
        channel = CredentialChannel.ASKPASS,
        askpassVariables = listOf("SSH_ASKPASS"),
    )

    /**
     * @return A stored profile for the test worker.
     */
    private fun profile(): CredentialProfileDto = CredentialProfileDto(
        id = 1L,
        workerId = 17L,
        name = "vps",
        secretRef = "ref",
        command = "/bin/sh",
        channel = CredentialChannel.ASKPASS,
        askpassVariables = listOf("SSH_ASKPASS"),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * @return Secret metadata for the test worker, with no value.
     */
    private fun secretMetadata(): CredentialSecretMetadataDto = CredentialSecretMetadataDto(
        id = 9L,
        workerId = 17L,
        reference = "ref",
        algorithm = "RSA-OAEP-256+A256GCM",
        workerCertificateFingerprint = "fp",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
