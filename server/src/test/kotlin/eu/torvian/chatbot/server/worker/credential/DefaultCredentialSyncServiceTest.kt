package eu.torvian.chatbot.server.worker.credential

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.common.models.api.credential.WorkerDispatchFailure
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.WorkerCredentialProfileService
import eu.torvian.chatbot.server.service.core.WorkerCredentialSecretService
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies the persist-then-best-effort-dispatch policy: a failed push must never fail or roll back a
 * committed write, and the response must carry the classified delivery outcome.
 */
class DefaultCredentialSyncServiceTest {

    private val profileService = mockk<WorkerCredentialProfileService>()
    private val secretService = mockk<WorkerCredentialSecretService>()
    private val dispatchService = mockk<CredentialCommandDispatchService>()

    private val service = DefaultCredentialSyncService(profileService, secretService, dispatchService)

    private val ownerId = 5L
    private val workerId = 17L
    private val otherWorkerId = 18L

    private val signedRequest = SignedRequest(
        payload = "{}",
        signature = "sig",
        signerId = "device-1",
        timestamp = 1L,
        nonce = "nonce",
    )

    @BeforeEach
    fun setUp() {
        clearMocks(profileService, secretService, dispatchService)
    }

    /**
     * Ensures a stored profile is pushed and a successful push reports no failure.
     */
    @Test
    fun `createProfile persists then dispatches a create`() = runTest {
        coEvery { profileService.createProfile(ownerId, any(), signedRequest) } returns profile().right()
        coEvery { profileService.getAssignedProfile(1L) } returns signed(profile()).right()
        coEvery { dispatchService.createProfile(workerId, any()) } returns Unit.right()

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        val value = result.getOrNull() ?: error("expected success, got $result")
        assertEquals(1L, value.profile.id)
        assertNull(value.dispatch.failure)
        assertEquals(workerId, value.dispatch.workerId)
        coVerifyOrder {
            profileService.createProfile(ownerId, any(), signedRequest)
            dispatchService.createProfile(workerId, any())
        }
    }

    /**
     * Ensures a failed push keeps the write and reports the classified failure instead of failing.
     */
    @Test
    fun `createProfile keeps the write when dispatch fails`() = runTest {
        coEvery { profileService.createProfile(ownerId, any(), signedRequest) } returns profile().right()
        coEvery { profileService.getAssignedProfile(1L) } returns signed(profile()).right()
        coEvery { dispatchService.createProfile(workerId, any()) } returns
            WorkerDispatchFailure.WORKER_OFFLINE.left()

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        val value = result.getOrNull() ?: error("write must not fail, got $result")
        assertEquals(WorkerDispatchFailure.WORKER_OFFLINE, value.dispatch.failure)
    }

    /**
     * Ensures a persistence failure is not turned into a delivered-looking response.
     */
    @Test
    fun `createProfile reports persistence failures`() = runTest {
        coEvery { profileService.createProfile(ownerId, any(), signedRequest) } returns
            WorkerCredentialServiceError.ValidationFailed(listOf("bad")).left()

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        assertIs<WorkerCredentialServiceError.ValidationFailed>(result.leftOrNull())
        coVerify(exactly = 0) { dispatchService.createProfile(any(), any()) }
    }

    /**
     * Ensures a same-worker update dispatches an update.
     */
    @Test
    fun `updateProfile dispatches an update on the same worker`() = runTest {
        coEvery { profileService.getProfile(ownerId, 1L) } returns profile().right()
        coEvery { profileService.updateProfile(ownerId, 1L, any(), signedRequest) } returns profile().right()
        coEvery { profileService.getAssignedProfile(1L) } returns signed(profile()).right()
        coEvery { dispatchService.updateProfile(workerId, any()) } returns Unit.right()

        val result = service.updateProfile(ownerId, 1L, updateRequest(workerId), signedRequest)

        assertNull(result.getOrNull()?.dispatch?.failure)
        coVerify(exactly = 1) { dispatchService.updateProfile(workerId, any()) }
        coVerify(exactly = 0) { dispatchService.createProfile(any(), any()) }
    }

    /**
     * Ensures a profile that moves workers is deleted on the old worker before it is created on the new one.
     */
    @Test
    fun `updateProfile moves a profile between workers`() = runTest {
        coEvery { profileService.getProfile(ownerId, 1L) } returns profile().right()
        coEvery { profileService.updateProfile(ownerId, 1L, any(), signedRequest) } returns
            profile(workerId = otherWorkerId).right()
        coEvery { profileService.getAssignedProfile(1L) } returns signed(profile(workerId = otherWorkerId)).right()
        coEvery { dispatchService.deleteProfile(workerId, 1L) } returns Unit.right()
        coEvery { dispatchService.createProfile(otherWorkerId, any()) } returns Unit.right()

        val result = service.updateProfile(ownerId, 1L, updateRequest(otherWorkerId), signedRequest)

        val value = result.getOrNull() ?: error("expected success, got $result")
        assertEquals(otherWorkerId, value.dispatch.workerId)
        assertNull(value.dispatch.failure)
        assertEquals(workerId, value.previousWorkerDispatch?.workerId)
        assertNull(value.previousWorkerDispatch?.failure)
        coVerifyOrder {
            dispatchService.deleteProfile(workerId, 1L)
            dispatchService.createProfile(otherWorkerId, any())
        }
    }

    /**
     * Ensures a move whose withdrawal fails is not reported as a clean delivery: the old worker keeps the
     * profile until its next bootstrap, so the operator must see that half of the move did not arrive.
     */
    @Test
    fun `updateProfile reports a failed withdrawal from the previous worker`() = runTest {
        coEvery { profileService.getProfile(ownerId, 1L) } returns profile().right()
        coEvery { profileService.updateProfile(ownerId, 1L, any(), signedRequest) } returns
            profile(workerId = otherWorkerId).right()
        coEvery { profileService.getAssignedProfile(1L) } returns signed(profile(workerId = otherWorkerId)).right()
        coEvery { dispatchService.deleteProfile(workerId, 1L) } returns
            WorkerDispatchFailure.WORKER_OFFLINE.left()
        coEvery { dispatchService.createProfile(otherWorkerId, any()) } returns Unit.right()

        val result = service.updateProfile(ownerId, 1L, updateRequest(otherWorkerId), signedRequest)

        val value = result.getOrNull() ?: error("write must not fail, got $result")
        assertEquals(WorkerDispatchFailure.WORKER_OFFLINE, value.previousWorkerDispatch?.failure)
        assertEquals(workerId, value.previousWorkerDispatch?.workerId)
        // The new owner accepted the profile, so its own outcome stays delivered.
        assertNull(value.dispatch.failure)
        assertEquals(otherWorkerId, value.dispatch.workerId)
    }

    /**
     * Ensures a same-worker update reports no withdrawal, so a single-worker write has one outcome only.
     */
    @Test
    fun `updateProfile reports no previous worker outcome on the same worker`() = runTest {
        coEvery { profileService.getProfile(ownerId, 1L) } returns profile().right()
        coEvery { profileService.updateProfile(ownerId, 1L, any(), signedRequest) } returns profile().right()
        coEvery { profileService.getAssignedProfile(1L) } returns signed(profile()).right()
        coEvery { dispatchService.updateProfile(workerId, any()) } returns Unit.right()

        val result = service.updateProfile(ownerId, 1L, updateRequest(workerId), signedRequest)

        assertNull(result.getOrNull()?.previousWorkerDispatch)
    }

    /**
     * Ensures deletion dispatches to the worker the profile belonged to.
     */
    @Test
    fun `deleteProfile dispatches a delete to the owning worker`() = runTest {
        coEvery { profileService.deleteProfile(ownerId, 1L) } returns profile().right()
        coEvery { dispatchService.deleteProfile(workerId, 1L) } returns Unit.right()

        val result = service.deleteProfile(ownerId, 1L)

        assertEquals(1L, result.getOrNull()?.deletedId)
        assertNull(result.getOrNull()?.dispatch?.failure)
    }

    /**
     * Ensures secret writes dispatch the encrypted row and report a failure without losing the write.
     */
    @Test
    fun `createSecret keeps the write when dispatch fails`() = runTest {
        coEvery { secretService.createSecret(ownerId, any()) } returns secretMetadata().right()
        coEvery { secretService.getAssignedSecret(9L) } returns workerSecret().right()
        coEvery { dispatchService.createSecret(workerId, any()) } returns
            WorkerDispatchFailure.WORKER_REJECTED.left()

        val result = service.createSecret(
            ownerId,
            CreateCredentialSecretRequest(workerId = workerId, reference = "ref", value = "value")
        )

        val value = result.getOrNull() ?: error("write must not fail, got $result")
        assertEquals(WorkerDispatchFailure.WORKER_REJECTED, value.dispatch.failure)
        assertEquals(9L, value.secret.id)
    }

    /**
     * Ensures a secret value replacement dispatches an update on the same worker.
     */
    @Test
    fun `updateSecret dispatches an update`() = runTest {
        coEvery { secretService.updateSecret(ownerId, 9L, any()) } returns secretMetadata().right()
        coEvery { secretService.getAssignedSecret(9L) } returns workerSecret().right()
        coEvery { dispatchService.updateSecret(workerId, any()) } returns Unit.right()

        val result = service.updateSecret(ownerId, 9L, UpdateCredentialSecretRequest("ref", "value"))

        assertNull(result.getOrNull()?.dispatch?.failure)
    }

    /**
     * Ensures a secret deletion dispatches a delete to the owning worker.
     */
    @Test
    fun `deleteSecret dispatches a delete`() = runTest {
        coEvery { secretService.deleteSecret(ownerId, 9L) } returns secretMetadata().right()
        coEvery { dispatchService.deleteSecret(workerId, 9L) } returns Unit.right()

        val result = service.deleteSecret(ownerId, 9L)

        assertEquals(9L, result.getOrNull()?.deletedId)
        coVerify(exactly = 1) { dispatchService.deleteSecret(workerId, 9L) }
    }

    /**
     * @param workerId Worker the profile is bound to.
     * @return A stored profile.
     */
    private fun profile(workerId: Long = this.workerId): CredentialProfileDto = CredentialProfileDto(
        id = 1L,
        workerId = workerId,
        name = "vps",
        secretRef = "ref",
        command = "/bin/sh",
        channel = CredentialChannel.ENV,
        envName = "TOKEN",
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * @param profile Profile to wrap.
     * @return The profile with a detached signature, as relayed to the worker.
     */
    private fun signed(profile: CredentialProfileDto): SignedCredentialProfileDto =
        SignedCredentialProfileDto(profile = profile, signedRequest = signedRequest)

    /**
     * @return A stored secret metadata row.
     */
    private fun secretMetadata(): CredentialSecretMetadataDto = CredentialSecretMetadataDto(
        id = 9L,
        workerId = workerId,
        reference = "ref",
        algorithm = "alg",
        workerCertificateFingerprint = "fp",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * @return The encrypted secret row relayed to the worker.
     */
    private fun workerSecret(): WorkerCredentialSecretDto = WorkerCredentialSecretDto(
        id = 9L,
        workerId = workerId,
        reference = "ref",
        encryptedSecret = "cipher",
        encryptedDek = "dek",
        algorithm = "alg",
        workerCertificateFingerprint = "fp",
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * @return A create request for the test profile.
     */
    private fun createRequest(): CreateCredentialProfileRequest = CreateCredentialProfileRequest(
        workerId = workerId,
        name = "vps",
        secretRef = "ref",
        command = "/bin/sh",
        channel = CredentialChannel.ENV,
        envName = "TOKEN",
    )

    /**
     * @param workerId Worker the replacement profile is bound to.
     * @return An update request for the test profile.
     */
    private fun updateRequest(workerId: Long): UpdateCredentialProfileRequest = UpdateCredentialProfileRequest(
        workerId = workerId,
        name = "vps",
        secretRef = "ref",
        command = "/bin/sh",
        channel = CredentialChannel.ENV,
        envName = "TOKEN",
    )
}
