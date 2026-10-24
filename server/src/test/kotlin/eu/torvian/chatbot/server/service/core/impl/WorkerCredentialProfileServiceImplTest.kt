package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialProfileRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileDao
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileSignatureDao
import eu.torvian.chatbot.server.data.dao.WorkerCredentialSecretDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.UserDeviceEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileSignatureEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Verifies credential-profile persistence, validation, ownership and signature replay.
 */
class WorkerCredentialProfileServiceImplTest {

    private val profileDao = mockk<WorkerCredentialProfileDao>()
    private val signatureDao = mockk<WorkerCredentialProfileSignatureDao>(relaxed = true)
    private val secretDao = mockk<WorkerCredentialSecretDao>()
    private val userDeviceDao = mockk<UserDeviceDao>()
    private val workerDao = mockk<WorkerDao>()
    private val transactionScope = mockk<TransactionScope>()

    private val service = WorkerCredentialProfileServiceImpl(
        profileDao = profileDao,
        profileSignatureDao = signatureDao,
        secretDao = secretDao,
        userDeviceDao = userDeviceDao,
        workerDao = workerDao,
        transactionScope = transactionScope,
    )

    private val ownerId = 5L
    private val workerId = 17L

    private val worker = WorkerEntity(
        id = workerId,
        workerUid = "worker-17",
        ownerUserId = ownerId,
        displayName = "worker",
        certificatePem = "pem",
        certificateFingerprint = "fp",
        allowedScopes = listOf("messages:read"),
        createdAt = Instant.fromEpochMilliseconds(0),
        lastSeenAt = null,
        toolNamePrefix = null,
    )

    private val device = UserDeviceEntity(
        id = 3L,
        userId = ownerId,
        clientDeviceId = "device-1",
        deviceName = null,
        createdAt = Instant.fromEpochMilliseconds(0),
        lastUsedAt = Instant.fromEpochMilliseconds(0),
    )

    private val signedRequest = SignedRequest(
        payload = "{\"name\":\"vps\"}",
        signature = "sig",
        signerId = "device-1",
        timestamp = 11L,
        nonce = "nonce-1",
    )

    @BeforeEach
    fun setUp() {
        clearMocks(profileDao, signatureDao, secretDao, userDeviceDao, workerDao, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        coEvery { workerDao.getWorkerById(workerId) } returns worker.right()
        coEvery { userDeviceDao.getDeviceByClientId(ownerId, "device-1") } returns device
        coEvery { userDeviceDao.getDeviceById(device.id) } returns device
        coEvery { secretDao.getSecretByWorkerIdAndReference(workerId, "vps_password") } returns secret()
    }

    /**
     * Ensures a valid profile is stored and its exact signed payload is persisted for replay.
     */
    @Test
    fun `createProfile stores the profile and its signed payload`() = runTest {
        val createSlot: CapturingSlot<CreateWorkerCredentialProfileEntity> = slot()
        coEvery { profileDao.createProfile(capture(createSlot)) } answers { stored(createSlot.captured) }

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        val dto = result.getOrNull() ?: error("expected success, got $result")
        assertEquals("vps", dto.name)
        assertEquals(CredentialChannel.ASKPASS, dto.channel)
        assertEquals(listOf("SSH_ASKPASS"), dto.askpassVariables)
        assertEquals(mapOf("SSH_ASKPASS_REQUIRE" to "force"), dto.environmentVariables)

        val captured = createSlot.captured
        assertEquals(workerId, captured.workerId)
        assertEquals("vps_password", captured.secretRef)

        val signatureSlot: CapturingSlot<WorkerCredentialProfileSignatureEntity> = slot()
        coVerify { signatureDao.upsertSignature(capture(signatureSlot)) }
        assertEquals("{\"name\":\"vps\"}", signatureSlot.captured.payloadJson)
        assertEquals("sig", signatureSlot.captured.signature)
        assertEquals(device.id, signatureSlot.captured.userDeviceId)
    }

    /**
     * Ensures a signature from a device the user does not have is refused.
     */
    @Test
    fun `createProfile rejects an unknown signer`() = runTest {
        coEvery { userDeviceDao.getDeviceByClientId(ownerId, "device-1") } returns null

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        assertIs<WorkerCredentialServiceError.Unauthorized>(result.leftOrNull())
    }

    /**
     * Ensures a worker owned by somebody else cannot receive a profile.
     */
    @Test
    fun `createProfile rejects a worker owned by another user`() = runTest {
        coEvery { workerDao.getWorkerById(workerId) } returns WorkerError.NotFound(workerId).left()

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        assertIs<WorkerCredentialServiceError.WorkerNotFound>(result.leftOrNull())
    }

    /**
     * Ensures the shared rules are applied and reported without echoing values.
     */
    @Test
    fun `createProfile reports rule violations`() = runTest {
        val result = service.createProfile(
            ownerId,
            createRequest().copy(askpassVariables = emptyList()),
            signedRequest
        )

        val error = result.leftOrNull()
        assertIs<WorkerCredentialServiceError.ValidationFailed>(error)
        assertEquals(1, error.violations.size)
    }

    /**
     * Ensures a profile may reference only a secret on the same worker.
     */
    @Test
    fun `createProfile rejects a secret that is not assigned to the worker`() = runTest {
        coEvery { secretDao.getSecretByWorkerIdAndReference(workerId, "vps_password") } returns null

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        assertIs<WorkerCredentialServiceError.SecretNotAssigned>(result.leftOrNull())
    }

    /**
     * Ensures a duplicate name is reported as a logical error, not a driver failure.
     */
    @Test
    fun `createProfile maps a duplicate name`() = runTest {
        coEvery { profileDao.createProfile(any()) } returns
            WorkerCredentialPersistenceError.DuplicateProfileName(workerId, "vps").left()

        val result = service.createProfile(ownerId, createRequest(), signedRequest)

        assertIs<WorkerCredentialServiceError.DuplicateProfileName>(result.leftOrNull())
    }

    /**
     * Ensures an update stores a replacement signature over the new payload.
     */
    @Test
    fun `updateProfile replaces the signature`() = runTest {
        coEvery { profileDao.getProfileById(1L) } returns storedProfile()
        coEvery { profileDao.updateProfile(1L, any()) } returns storedProfile(name = "vps2").right()
        val updateSlot: CapturingSlot<UpdateWorkerCredentialProfileEntity> = slot()

        val result = service.updateProfile(
            ownerId,
            1L,
            UpdateCredentialProfileRequest(
                workerId = workerId,
                name = "vps2",
                secretRef = "vps_password",
                command = "/bin/sh",
                channel = CredentialChannel.ASKPASS,
                askpassVariables = listOf("SSH_ASKPASS"),
            ),
            signedRequest
        )

        assertEquals("vps2", result.getOrNull()?.name)
        coVerify { profileDao.updateProfile(1L, capture(updateSlot)) }
        assertEquals("vps2", updateSlot.captured.name)
        coVerify { signatureDao.upsertSignature(any()) }
    }

    /**
     * Ensures a missing profile is reported as not found.
     */
    @Test
    fun `updateProfile reports a missing profile`() = runTest {
        coEvery { profileDao.getProfileById(1L) } returns null

        val result = service.updateProfile(
            ownerId,
            1L,
            UpdateCredentialProfileRequest(
                workerId = workerId,
                name = "vps",
                secretRef = "vps_password",
                command = "/bin/sh",
                channel = CredentialChannel.ASKPASS,
                askpassVariables = listOf("SSH_ASKPASS"),
            ),
            signedRequest
        )

        assertIs<WorkerCredentialServiceError.ProfileNotFound>(result.leftOrNull())
    }

    /**
     * Ensures the bootstrap read relays the most recent signature with the signer's client id.
     */
    @Test
    fun `getAssignedProfiles relays the latest signature`() = runTest {
        coEvery { profileDao.getProfilesByWorkerId(workerId) } returns listOf(storedProfile())
        coEvery { signatureDao.getSignaturesByProfileIds(listOf(1L)) } returns listOf(
            signature(updatedAtMs = 100L, payload = "old", signature = "old-sig"),
            signature(updatedAtMs = 200L, payload = "new", signature = "new-sig"),
        )

        val result = service.getAssignedProfiles(workerId)

        val assigned = result.getOrNull()?.single() ?: error("expected one profile, got $result")
        assertEquals("new", assigned.signedRequest?.payload)
        assertEquals("new-sig", assigned.signedRequest?.signature)
        assertEquals("device-1", assigned.signedRequest?.signerId)
    }

    /**
     * Ensures a profile without any signature is relayed unsigned so the worker rejects it.
     */
    @Test
    fun `getAssignedProfiles leaves an unsigned profile without a signature`() = runTest {
        coEvery { profileDao.getProfilesByWorkerId(workerId) } returns listOf(storedProfile())
        coEvery { signatureDao.getSignaturesByProfileIds(listOf(1L)) } returns emptyList()

        val result = service.getAssignedProfiles(workerId)

        assertNull(result.getOrNull()?.single()?.signedRequest)
    }

    /**
     * Ensures the worker-scoped read with no rows returns an empty list rather than failing.
     */
    @Test
    fun `getAssignedProfiles returns an empty list for a worker without rows`() = runTest {
        coEvery { profileDao.getProfilesByWorkerId(workerId) } returns emptyList()

        assertEquals(emptyList(), service.getAssignedProfiles(workerId).getOrNull())
        coVerify(exactly = 0) { signatureDao.getSignaturesByProfileIds(any()) }
    }

    /**
     * Ensures deletion returns the removed profile and enforces ownership first.
     */
    @Test
    fun `deleteProfile enforces ownership and returns the removed profile`() = runTest {
        coEvery { profileDao.getProfileById(1L) } returns storedProfile()
        coEvery { profileDao.deleteProfile(1L) } returns storedProfile().right()

        val result = service.deleteProfile(ownerId, 1L)

        assertNotNull(result.getOrNull())
        coVerify { profileDao.deleteProfile(1L) }
    }

    /**
     * @return A create request matching the askpass OpenSSH recipe.
     */
    private fun createRequest(): CreateCredentialProfileRequest = CreateCredentialProfileRequest(
        workerId = workerId,
        name = "vps",
        secretRef = "vps_password",
        command = "/bin/sh",
        args = listOf("-c", "true"),
        channel = CredentialChannel.ASKPASS,
        askpassVariables = listOf("SSH_ASKPASS"),
        environmentVariables = mapOf("SSH_ASKPASS_REQUIRE" to "force"),
    )

    /**
     * @param name Profile name to store.
     * @return A stored profile entity with the default askpass recipe.
     */
    private fun storedProfile(name: String = "vps"): WorkerCredentialProfileEntity =
        WorkerCredentialProfileEntity(
            id = 1L,
            workerId = workerId,
            name = name,
            description = null,
            secretRef = "vps_password",
            command = "/bin/sh",
            args = listOf("-c", "true"),
            channel = CredentialChannel.ASKPASS,
            envName = null,
            fileName = null,
            askpassUsername = null,
            environmentVariables = mapOf("SSH_ASKPASS_REQUIRE" to "force"),
            askpassVariables = listOf("SSH_ASKPASS"),
            isEnabled = true,
            createdAt = Instant.fromEpochMilliseconds(0),
            updatedAt = Instant.fromEpochMilliseconds(0),
        )

    /**
     * @param create Entity captured from the create call.
     * @return The entity as it would be returned from storage.
     */
    private fun stored(create: CreateWorkerCredentialProfileEntity): Either<
        WorkerCredentialPersistenceError,
        WorkerCredentialProfileEntity
        > = storedProfile(name = create.name).right()

    /**
     * @param updatedAtMs Update timestamp used to order signatures.
     * @param payload Signed payload stored with the row.
     * @param signature Signature bytes stored with the row.
     * @return A persisted signature row for the test profile.
     */
    private fun signature(
        updatedAtMs: Long,
        payload: String,
        signature: String,
    ): WorkerCredentialProfileSignatureEntity = WorkerCredentialProfileSignatureEntity(
        profileId = 1L,
        userDeviceId = device.id,
        signature = signature,
        timestamp = 1L,
        nonce = "nonce",
        payloadJson = payload,
        createdAt = Instant.fromEpochMilliseconds(updatedAtMs),
        updatedAt = Instant.fromEpochMilliseconds(updatedAtMs),
    )

    /**
     * @return A stored secret row on the same worker.
     */
    private fun secret(): WorkerCredentialSecretEntity = WorkerCredentialSecretEntity(
        id = 9L,
        workerId = workerId,
        reference = "vps_password",
        encryptedSecret = "cipher",
        encryptedDek = "dek",
        algorithm = "RSA-OAEP-256+A256GCM",
        workerCertificateFingerprint = "fp",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )
}
