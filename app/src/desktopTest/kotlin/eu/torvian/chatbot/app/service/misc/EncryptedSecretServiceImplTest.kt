package eu.torvian.chatbot.app.service.misc

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.database.dao.EncryptedSecretLocalDao
import eu.torvian.chatbot.app.database.dao.error.DeleteEncryptedSecretError
import eu.torvian.chatbot.app.database.model.EncryptedSecretEntity
import eu.torvian.chatbot.common.security.CryptoError
import eu.torvian.chatbot.common.security.EncryptedSecret
import eu.torvian.chatbot.common.security.EncryptionService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import eu.torvian.chatbot.app.database.dao.error.EncryptedSecretError as DaoEncryptedSecretError

/**
 * Tests for EncryptedSecretService using mocked dependencies.
 */
class EncryptedSecretServiceImplTest {

    private lateinit var encryptionService: EncryptionService
    private lateinit var dao: EncryptedSecretLocalDao
    private lateinit var clock: Clock
    private lateinit var service: EncryptedSecretService

    @BeforeTest
    fun setup() {
        encryptionService = mockk()
        dao = mockk()
        clock = mockk()
        service = EncryptedSecretServiceImpl(encryptionService, dao, clock)
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `encryptAndStore should encrypt and persist secret`() = runTest {
        // Given
        val plainText = "mySecret"
        val encryptedData = EncryptedSecret("encrypted", "encryptedDEK", 1)
        val now = Instant.fromEpochMilliseconds(1000L)
        val generatedEntity = EncryptedSecretEntity(
            id = 42L,
            encryptedSecret = encryptedData.encryptedSecret,
            encryptedDEK = encryptedData.encryptedDEK,
            keyVersion = encryptedData.keyVersion,
            createdAt = now.toEpochMilliseconds(),
            updatedAt = now.toEpochMilliseconds()
        )

        every { clock.now() } returns now
        coEvery { encryptionService.encrypt(plainText) } returns encryptedData.right()
        coEvery {
            dao.insert(
                encryptedSecret = "encrypted",
                encryptedDEK = "encryptedDEK",
                keyVersion = 1,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        } returns generatedEntity

        // When
        val result = service.encryptAndStore(plainText)

        // Then
        assertTrue(result.isRight(), "Should succeed")
        // Extract right value with getOrNull
        val rightVal = result.getOrNull()
        assertNotNull(rightVal)
        assertEquals(generatedEntity, rightVal)

        coVerify(exactly = 1) { encryptionService.encrypt(plainText) }
        coVerify(exactly = 1) { dao.insert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `encryptAndStore should return error on encryption failure`() = runTest {
        // Given
        val plainText = "mySecret"
        val cryptoError = CryptoError.EncryptionError("Encryption failed")

        coEvery { encryptionService.encrypt(plainText) } returns Either.Left(cryptoError)

        // When
        val result = service.encryptAndStore(plainText)

        // Then
        assertTrue(result.isLeft(), "Should fail")
        val left = result.fold({ it }, { null })
        assertNotNull(left)
        assertTrue(left is EncryptAndStoreError.EncryptionFailed)

        coVerify(exactly = 1) { encryptionService.encrypt(plainText) }
        coVerify(exactly = 0) { dao.insert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `encryptAndStore should return error on database failure`() = runTest {
        // Given
        val plainText = "mySecret"
        val encryptedData = EncryptedSecret("encrypted", "encryptedDEK", 1)
        val now = Instant.fromEpochMilliseconds(1000L)

        every { clock.now() } returns now
        coEvery { encryptionService.encrypt(plainText) } returns encryptedData.right()
        // Make DAO throw to simulate unexpected DB failure. The service currently does not
        // map insert exceptions to a specific EncryptedSecretError, so the exception
        // will be propagated. Assert that the exception is thrown.
        coEvery { dao.insert(any(), any(), any(), any(), any()) } throws RuntimeException("DB error")

        // When / Then: service should propagate the exception
        try {
            service.encryptAndStore(plainText)
            fail("Expected RuntimeException to be thrown")
        } catch (e: RuntimeException) {
            assertEquals("DB error", e.message)
        }
    }

    @Test
    fun `retrieveAndDecrypt should fetch and decrypt secret`() = runTest {
        // Given
        val secretId = 42L
        val entity = EncryptedSecretEntity(
            id = secretId,
            encryptedSecret = "encrypted",
            encryptedDEK = "encryptedDEK",
            keyVersion = 1,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val decryptedText = "mySecret"

        coEvery { dao.getById(secretId) } returns Either.Right(entity)
        coEvery { encryptionService.decrypt(any()) } returns decryptedText.right()

        // When
        val result = service.retrieveAndDecrypt(secretId)

        // Then
        assertTrue(result.isRight(), "Should succeed")
        assertEquals(decryptedText, result.getOrNull())

        coVerify(exactly = 1) { dao.getById(secretId) }
        coVerify(exactly = 1) { encryptionService.decrypt(any()) }
    }

    @Test
    fun `retrieveAndDecrypt should return error when secret not found`() = runTest {
        // Given
        val secretId = 999L

        coEvery { dao.getById(secretId) } returns Either.Left(DaoEncryptedSecretError.NotFound(secretId))

        // When
        val result = service.retrieveAndDecrypt(secretId)

        // Then
        assertTrue(result.isLeft(), "Should fail")
        val left = result.fold({ it }, { null })
        assertNotNull(left)
        assertTrue(left is RetrieveAndDecryptError.SecretNotFound)

        coVerify(exactly = 1) { dao.getById(secretId) }
        coVerify(exactly = 0) { encryptionService.decrypt(any()) }
    }

    @Test
    fun `retrieveAndDecrypt should return error on decryption failure`() = runTest {
        // Given
        val secretId = 42L
        val entity = EncryptedSecretEntity(
            id = secretId,
            encryptedSecret = "encrypted",
            encryptedDEK = "encryptedDEK",
            keyVersion = 1,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val cryptoError = CryptoError.DecryptionError("Decryption failed")

        coEvery { dao.getById(secretId) } returns Either.Right(entity)
        coEvery { encryptionService.decrypt(any()) } returns Either.Left(cryptoError)

        // When
        val result = service.retrieveAndDecrypt(secretId)

        // Then
        assertTrue(result.isLeft(), "Should fail")
        val left = result.fold({ it }, { null })
        assertNotNull(left)
        assertTrue(left is RetrieveAndDecryptError.DecryptionFailed)
    }

    @Test
    fun `updateSecret should re-encrypt and update existing secret`() = runTest {
        // Given
        val secretId = 42L
        val newPlainText = "newSecret"
        val existingEntity = EncryptedSecretEntity(
            id = secretId,
            encryptedSecret = "oldEncrypted",
            encryptedDEK = "oldDEK",
            keyVersion = 1,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val newEncryptedData = EncryptedSecret("newEncrypted", "newDEK", 2)
        val now = Instant.fromEpochMilliseconds(2000L)

        coEvery { dao.getById(secretId) } returns Either.Right(existingEntity)
        coEvery { encryptionService.encrypt(newPlainText) } returns newEncryptedData.right()
        every { clock.now() } returns now
        coEvery { dao.update(any(), any(), any(), any(), any()) } returns Either.Right(Unit)

        // When
        val result = service.updateSecret(secretId, newPlainText)

        // Then
        assertTrue(result.isRight(), "Should succeed")

        coVerify(exactly = 1) { dao.getById(secretId) }
        coVerify(exactly = 1) { encryptionService.encrypt(newPlainText) }
        coVerify(exactly = 1) {
            dao.update(
                id = secretId,
                encryptedSecret = "newEncrypted",
                encryptedDEK = "newDEK",
                keyVersion = 2,
                updatedAt = 2000L
            )
        }
    }

    @Test
    fun `updateSecret should return error when secret not found`() = runTest {
        // Given
        val secretId = 999L
        val newPlainText = "newSecret"

        coEvery { dao.getById(secretId) } returns Either.Left(DaoEncryptedSecretError.NotFound(secretId))

        // When
        val result = service.updateSecret(secretId, newPlainText)

        // Then
        assertTrue(result.isLeft(), "Should fail")
        val left = result.fold({ it }, { null })
        assertNotNull(left)
        assertTrue(left is UpdateSecretError.SecretNotFound)

        coVerify(exactly = 1) { dao.getById(secretId) }
        coVerify(exactly = 0) { encryptionService.encrypt(any()) }
        coVerify(exactly = 0) { dao.update(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deleteSecret should remove encrypted secret`() = runTest {
        // Given
        val secretId = 42L

        coEvery { dao.deleteById(secretId) } returns Either.Right(Unit)

        // When
        val result = service.deleteSecret(secretId)

        // Then
        assertTrue(result.isRight(), "Should succeed")

        coVerify(exactly = 1) { dao.deleteById(secretId) }
    }

    @Test
    fun `deleteSecret should return error on database failure`() = runTest {
        // Given
        val secretId = 42L

        // Simulate a foreign key violation returned by the DAO so the service maps
        // it to DeleteSecretError.SecretInUse.
        coEvery { dao.deleteById(secretId) } returns Either.Left(
            DeleteEncryptedSecretError.ForeignKeyViolation("Referenced by other records", RuntimeException("FK"))
        )

        // When
        val result = service.deleteSecret(secretId)

        // Then
        assertTrue(result.isLeft(), "Should fail")
        val left = result.fold({ it }, { null })
        assertNotNull(left)
        assertTrue(left is DeleteSecretError.SecretInUse)
    }
}
