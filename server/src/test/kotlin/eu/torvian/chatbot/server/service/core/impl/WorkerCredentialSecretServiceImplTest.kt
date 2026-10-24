package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialSecretRequest
import eu.torvian.chatbot.common.models.api.credential.UpdateCredentialSecretRequest
import eu.torvian.chatbot.server.data.dao.WorkerCredentialSecretDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerCredentialPersistenceError
import eu.torvian.chatbot.server.data.entities.CreateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.UpdateWorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.service.core.error.credential.WorkerCredentialServiceError
import eu.torvian.chatbot.server.service.security.CredentialEncryptionError
import eu.torvian.chatbot.server.service.security.CredentialSecretEncryptionService
import eu.torvian.chatbot.server.service.security.EncryptedCredentialPayload
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies credential-secret persistence: encryption on write, ciphertext-only reads, and the
 * immutability of both the reference and the worker binding.
 */
class WorkerCredentialSecretServiceImplTest {

    private val secretDao = mockk<WorkerCredentialSecretDao>()
    private val workerDao = mockk<WorkerDao>()
    private val encryptionService = mockk<CredentialSecretEncryptionService>()
    private val transactionScope = mockk<TransactionScope>()

    private val service = WorkerCredentialSecretServiceImpl(
        secretDao = secretDao,
        workerDao = workerDao,
        encryptionService = encryptionService,
        transactionScope = transactionScope,
    )

    private val ownerId = 5L
    private val workerId = 17L

    @BeforeEach
    fun setUp() {
        clearMocks(secretDao, workerDao, encryptionService, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        coEvery { workerDao.getWorkerById(workerId) } returns worker().right()
        coEvery { encryptionService.encrypt(any(), "pem") } returns payload().right()
    }

    /**
     * Ensures a new value is encrypted before storage and never written in plaintext.
     */
    @Test
    fun `createSecret stores ciphertext only`() = runTest {
        val createSlot: CapturingSlot<CreateWorkerCredentialSecretEntity> = slot()
        coEvery { secretDao.createSecret(capture(createSlot)) } answers { stored(createSlot.captured).right() }

        val result = service.createSecret(ownerId, CreateCredentialSecretRequest(workerId, "vps_password", "value"))

        val metadata = result.getOrNull() ?: error("expected success, got $result")
        assertEquals("vps_password", metadata.reference)
        assertEquals("alg", metadata.algorithm)
        assertEquals("cipher", createSlot.captured.encryptedSecret)
        assertEquals("dek", createSlot.captured.encryptedDek)
        assertEquals("fp", createSlot.captured.workerCertificateFingerprint)
        coVerify { encryptionService.encrypt("value", "pem") }
    }

    /**
     * Ensures a reference the shared rules refuse is rejected before anything is stored.
     *
     * The same string is later echoed in logs and in model-visible error text, so a control character
     * may not reach storage.
     */
    @Test
    fun `createSecret rejects an unusable reference`() = runTest {
        val result = service.createSecret(
            ownerId,
            CreateCredentialSecretRequest(workerId, "vps\npassword", "value")
        )

        val error = assertIs<WorkerCredentialServiceError.ValidationFailed>(result.leftOrNull())
        assertTrue(
            error.violations.single().contains("control characters"),
            "Unexpected violations: ${error.violations}",
        )
        coVerify(exactly = 0) { encryptionService.encrypt(any(), any()) }
        coVerify(exactly = 0) { secretDao.createSecret(any()) }
    }

    /**
     * Ensures an encryption failure is reported as a typed error and nothing is stored.
     */
    @Test
    fun `createSecret reports encryption failure`() = runTest {
        coEvery { encryptionService.encrypt(any(), any()) } returns
            CredentialEncryptionError.ValueTooLarge(9000, 8192).left()

        val result = service.createSecret(ownerId, CreateCredentialSecretRequest(workerId, "ref", "value"))

        val error = result.leftOrNull()
        assertIs<WorkerCredentialServiceError.EncryptionFailed>(error)
        assertIs<CredentialEncryptionError.ValueTooLarge>(error.error)
        coVerify(exactly = 0) { secretDao.createSecret(any()) }
    }

    /**
     * Ensures an empty value is refused where it is typed, because the worker rejects an empty plaintext
     * at decrypt time and the operator cannot read a stored value back.
     */
    @Test
    fun `createSecret rejects an empty value`() = runTest {
        val result = service.createSecret(ownerId, CreateCredentialSecretRequest(workerId, "vps_password", ""))

        val error = assertIs<WorkerCredentialServiceError.ValidationFailed>(result.leftOrNull())
        assertEquals(listOf("value must be non-empty UTF-8 text"), error.violations)
        coVerify(exactly = 0) { encryptionService.encrypt(any(), any()) }
        coVerify(exactly = 0) { secretDao.createSecret(any()) }
    }

    /**
     * Ensures a replacement by an empty value is refused for the same reason, before anything is stored.
     */
    @Test
    fun `updateSecret rejects an empty value`() = runTest {
        coEvery { secretDao.getSecretById(9L) } returns stored()

        val result = service.updateSecret(ownerId, 9L, UpdateCredentialSecretRequest("vps_password", ""))

        val error = assertIs<WorkerCredentialServiceError.ValidationFailed>(result.leftOrNull())
        assertEquals(listOf("value must be non-empty UTF-8 text"), error.violations)
        coVerify(exactly = 0) { secretDao.updateSecret(any(), any()) }
    }

    /**
     * Ensures a secret cannot be moved to another worker, because the server cannot re-encrypt.
     */
    @Test
    fun `updateSecret refuses a reference change`() = runTest {
        coEvery { secretDao.getSecretById(9L) } returns stored()

        val result = service.updateSecret(ownerId, 9L, UpdateCredentialSecretRequest("renamed", "value"))

        assertIs<WorkerCredentialServiceError.SecretReferenceImmutable>(result.leftOrNull())
        coVerify(exactly = 0) { secretDao.updateSecret(any(), any()) }
    }

    /**
     * Ensures a value replacement keeps the worker binding and stores fresh ciphertext.
     */
    @Test
    fun `updateSecret keeps the worker binding and replaces ciphertext`() = runTest {
        val updateSlot: CapturingSlot<UpdateWorkerCredentialSecretEntity> = slot()
        coEvery { secretDao.getSecretById(9L) } returns stored()
        coEvery { secretDao.updateSecret(9L, capture(updateSlot)) } answers {
            stored().copy(encryptedSecret = updateSlot.captured.encryptedSecret).right()
        }

        val result = service.updateSecret(ownerId, 9L, UpdateCredentialSecretRequest("vps_password", "new"))

        assertEquals("vps_password", result.getOrNull()?.reference)
        assertEquals("cipher", updateSlot.captured.encryptedSecret)
        assertEquals("vps_password", updateSlot.captured.reference)
    }

    /**
     * Ensures a duplicate reference is reported as a logical error.
     */
    @Test
    fun `createSecret maps a duplicate reference`() = runTest {
        coEvery { secretDao.createSecret(any()) } returns
            WorkerCredentialPersistenceError.DuplicateSecretReference(workerId, "ref").left()

        val result = service.createSecret(ownerId, CreateCredentialSecretRequest(workerId, "ref", "value"))

        assertIs<WorkerCredentialServiceError.DuplicateSecretReference>(result.leftOrNull())
    }

    /**
     * Ensures deleting a secret returns its metadata and does not touch profiles.
     */
    @Test
    fun `deleteSecret returns the removed metadata`() = runTest {
        coEvery { secretDao.getSecretById(9L) } returns stored()
        coEvery { secretDao.deleteSecret(9L) } returns stored().right()

        val result = service.deleteSecret(ownerId, 9L)

        assertEquals("vps_password", result.getOrNull()?.reference)
        coVerify(exactly = 1) { secretDao.deleteSecret(9L) }
    }

    /**
     * Ensures operator reads expose metadata only, while the worker read carries ciphertext.
     */
    @Test
    fun `secret reads separate operator metadata from worker ciphertext`() = runTest {
        coEvery { secretDao.getSecretsByWorkerId(workerId) } returns listOf(stored())

        val metadata = service.getSecrets(ownerId, workerId).getOrNull()?.single()
            ?: error("expected metadata")
        val workerSecret = service.getAssignedSecrets(workerId).getOrNull()?.single()
            ?: error("expected worker secret")

        assertEquals("vps_password", metadata.reference)
        assertEquals("cipher", workerSecret.encryptedSecret)
        assertEquals("dek", workerSecret.encryptedDek)
    }

    /**
     * @return The worker owning the secret.
     */
    private fun worker(): WorkerEntity = WorkerEntity(
        id = workerId,
        workerUid = "worker-17",
        ownerUserId = ownerId,
        displayName = "worker",
        certificatePem = "pem",
        certificateFingerprint = "fp",
        allowedScopes = emptyList(),
        createdAt = Instant.fromEpochMilliseconds(0),
        lastSeenAt = null,
        toolNamePrefix = null,
    )

    /**
     * @return Ciphertext metadata produced by the fake encryptor.
     */
    private fun payload(): EncryptedCredentialPayload = EncryptedCredentialPayload(
        encryptedSecret = "cipher",
        encryptedDek = "dek",
        algorithm = "alg",
        workerCertificateFingerprint = "fp",
    )

    /**
     * @return A stored secret row of the test worker.
     */
    private fun stored(): WorkerCredentialSecretEntity = WorkerCredentialSecretEntity(
        id = 9L,
        workerId = workerId,
        reference = "vps_password",
        encryptedSecret = "cipher",
        encryptedDek = "dek",
        algorithm = "alg",
        workerCertificateFingerprint = "fp",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /**
     * @param create Entity captured from the create call.
     * @return The entity as it would be returned from storage.
     */
    private fun stored(create: CreateWorkerCredentialSecretEntity): WorkerCredentialSecretEntity =
        stored().copy(
            workerId = create.workerId,
            reference = create.reference,
            encryptedSecret = create.encryptedSecret,
            encryptedDek = create.encryptedDek,
            algorithm = create.algorithm,
            workerCertificateFingerprint = create.workerCertificateFingerprint,
        )
}
