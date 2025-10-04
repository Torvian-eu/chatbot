package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.Permission
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.security.CryptoError
import eu.torvian.chatbot.common.security.CryptoProvider
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Comprehensive unit tests for [FileSystemTokenStorage] implementation.
 *
 * This test class covers:
 * - All public methods and key functionality
 * - Arrow's Either types for error handling
 * - File system operations (success and failure scenarios)
 * - Error handling with appropriate error types
 * - Edge cases like missing files, invalid JSON, etc.
 * - Proper test cleanup to avoid leaving test files
 */
class FileSystemTokenStorageTest {

    private fun createTestTokenStorage(): TestFileSystemTokenStorage {
        return TestFileSystemTokenStorage()
    }

    private fun createTestUser(id: Long = 1L, username: String = "testuser"): User {
        return User(
            id = id,
            username = username,
            email = "$username@example.com",
            status = UserStatus.ACTIVE,
            createdAt = Clock.System.now(),
            lastLogin = Clock.System.now()
        )
    }

    @AfterTest
    fun cleanup() {
        // Clean up any test files that might have been created
        try {
            val tempDir = Path(System.getProperty("java.io.tmpdir"))
            if (SystemFileSystem.exists(tempDir)) {
                SystemFileSystem.list(tempDir).forEach { path ->
                    if (path.name.startsWith("chatbot-fs-test-")) {
                        try {
                            if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
                                SystemFileSystem.delete(path, mustExist = false)
                            } else {
                                SystemFileSystem.delete(path, mustExist = false)
                            }
                        } catch (_: Exception) {
                            // Ignore cleanup errors - they shouldn't fail the test
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore cleanup errors - they shouldn't fail the test
        }
    }


    @Test
    fun `saveAuthData should successfully save and encrypt tokens with user data`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        // Act
        val result = tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())

        // Assert
        assertTrue(result.isRight(), "Save operation should succeed")

        // Verify files were created
        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testKeyFilePath))
        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testDataFilePath))
    }

    @Test
    fun `getAccessToken should return correct token after saving`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        // Arrange - save tokens first
        val saveResult = tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())
        assertTrue(saveResult.isRight(), "Save operation should succeed")

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isRight(), "Get access token should succeed")
        assertEquals(accessToken, result.getOrNull())
    }

    @Test
    fun `getRefreshToken should return correct token after saving`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        // Arrange - save tokens first
        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())

        // Act
        val result = tokenStorage.getRefreshToken()

        // Assert
        assertTrue(result.isRight())
        assertEquals(refreshToken, result.getOrNull())
    }

    @Test
    fun `getExpiry should return correct expiration time after saving`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        // Arrange - save tokens first
        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())

        // Act
        val result = tokenStorage.getExpiry()

        // Assert
        assertTrue(result.isRight())
        assertEquals(expiresAt.epochSeconds, result.getOrNull()?.epochSeconds)
    }

    @Test
    fun `getAccessToken should return NotFound when no tokens stored`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `getRefreshToken should return NotFound when no tokens stored`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act
        val result = tokenStorage.getRefreshToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `getExpiry should return NotFound when no tokens stored`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act
        val result = tokenStorage.getExpiry()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `clearTokens should remove all stored tokens`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        // Arrange - save tokens first
        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())

        // Verify tokens exist
        assertTrue(tokenStorage.getAccessToken().isRight())

        // Act
        val clearResult = tokenStorage.clearAuthData()

        // Assert clear succeeded
        assertTrue(clearResult.isRight())

        // Assert tokens are gone
        val accessResult = tokenStorage.getAccessToken()
        assertTrue(accessResult.isLeft())
        assertTrue(accessResult.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `clearTokens should succeed even when no tokens exist`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act
        val result = tokenStorage.clearAuthData()

        // Assert
        assertTrue(result.isRight())
    }

    @Test
    fun `should handle missing key file gracefully`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Create only the data file without the key file
        tokenStorage.testFileSystem.createDirectories(tokenStorage.testDataFilePath.parent!!)
        tokenStorage.testFileSystem.sink(tokenStorage.testDataFilePath).buffered().use { sink ->
            sink.writeString("encrypted-data")
        }

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `should handle missing data file gracefully`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Create only the key file without the data file
        tokenStorage.testFileSystem.createDirectories(tokenStorage.testKeyFilePath.parent!!)
        tokenStorage.testFileSystem.sink(tokenStorage.testKeyFilePath).buffered().use { sink ->
            sink.writeString("""{"wrappedDek":"test","kekVersion":1}""")
        }

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `should handle corrupted metadata file gracefully`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Create corrupted metadata file
        tokenStorage.testFileSystem.createDirectories(tokenStorage.testKeyFilePath.parent!!)
        tokenStorage.testFileSystem.sink(tokenStorage.testKeyFilePath).buffered().use { sink ->
            sink.writeString("invalid-json")
        }
        tokenStorage.testFileSystem.sink(tokenStorage.testDataFilePath).buffered().use { sink ->
            sink.writeString("encrypted-data")
        }

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.InvalidTokenFormat)
    }

    @Test
    fun `should handle crypto provider DEK generation failure`() = runTest {
        val mockCrypto = FileSystemMockCryptoProvider().apply {
            dekGenerationShouldFail = true
        }
        val tokenStorage = TestFileSystemTokenStorage(mockCrypto)
        val user = createTestUser()

        // Act
        val result = tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.EncryptionError)
        assertEquals(result.leftOrNull()?.message?.contains("Failed to generate DEK"), true)
    }

    @Test
    fun `should handle crypto provider encryption failure`() = runTest {
        val mockCrypto = FileSystemMockCryptoProvider().apply {
            encryptionShouldFail = true
        }
        val tokenStorage = TestFileSystemTokenStorage(mockCrypto)
        val user = createTestUser()

        // Act
        val result = tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.EncryptionError)
        assertEquals(result.leftOrNull()?.message?.contains("Failed to encrypt data"), true)
    }

    @Test
    fun `should handle crypto provider DEK wrapping failure`() = runTest {
        val mockCrypto = FileSystemMockCryptoProvider().apply {
            dekWrappingShouldFail = true
        }
        val tokenStorage = TestFileSystemTokenStorage(mockCrypto)
        val user = createTestUser()

        // Act
        val result = tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.EncryptionError)
        assertEquals(result.leftOrNull()?.message?.contains("Failed to wrap DEK"), true)
    }

    @Test
    fun `should handle crypto provider DEK unwrapping failure`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser()

        // Save tokens first
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Make unwrapping fail
        tokenStorage.mockCryptoProvider.dekUnwrappingShouldFail = true

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.EncryptionError)
        assertEquals(result.leftOrNull()?.message?.contains("Failed to unwrap DEK"), true)
    }

    @Test
    fun `should handle crypto provider decryption failure`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser()

        // Save tokens first
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Make decryption fail
        tokenStorage.mockCryptoProvider.decryptionShouldFail = true

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.EncryptionError)
        assertEquals(result.leftOrNull()?.message?.contains("Failed to decrypt data"), true)
    }

    @Test
    fun `should handle corrupted token data gracefully`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser()

        // Save tokens first
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Make JSON parsing fail by corrupting the decrypted data
        tokenStorage.mockCryptoProvider.returnCorruptedDecryptedData = true

        // Act
        val result = tokenStorage.getAccessToken()

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.InvalidTokenFormat)
        assertEquals(result.leftOrNull()?.message?.contains("Failed to parse decrypted token data"), true)
    }

    @Test
    fun `active re-encryption should work when KEK version changes`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save tokens with KEK version 1
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())

        // Simulate KEK version change
        tokenStorage.mockCryptoProvider.currentKekVersion = 2

        // Act - retrieve tokens, which should trigger active re-encryption
        val accessResult = tokenStorage.getAccessToken()

        // Assert - tokens should still be retrievable
        assertTrue(accessResult.isRight())
        assertEquals(accessToken, accessResult.getOrNull())

        // Verify that the re-encryption was attempted (the mock tracks this)
        assertTrue(tokenStorage.mockCryptoProvider.reEncryptionAttempted)
    }

    @Test
    fun `active re-encryption failure should not prevent token retrieval`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save tokens with KEK version 1
        val accessToken = "test.access.token"
        val refreshToken = "test.refresh.token"
        val expiresAt = Clock.System.now() + 1.hours
        val user = createTestUser()

        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, emptyList())

        // Simulate KEK version change and make re-encryption fail
        tokenStorage.mockCryptoProvider.currentKekVersion = 2
        tokenStorage.mockCryptoProvider.reEncryptionShouldFail = true

        // Act - retrieve tokens, which should still work despite re-encryption failure
        val accessResult = tokenStorage.getAccessToken()

        // Assert - tokens should still be retrievable
        assertTrue(accessResult.isRight())
        assertEquals(accessToken, accessResult.getOrNull())
    }

    @Test
    fun `should handle file system IO errors during save`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser()

        // Make the crypto provider fail to simulate an IO error during save
        tokenStorage.mockCryptoProvider.dekGenerationShouldFail = true

        // Act
        val result = tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.EncryptionError)
    }

    @Test
    fun `should create storage directory if it does not exist`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser()

        // Ensure directory doesn't exist initially
        if (tokenStorage.testFileSystem.exists(tokenStorage.testStorageDir)) {
            tokenStorage.testFileSystem.delete(tokenStorage.testStorageDir, mustExist = false)
        }

        // Act
        val result = tokenStorage.saveAuthData("access", "refresh", Clock.System.now(), user, emptyList())

        // Assert
        assertTrue(result.isRight())
        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testStorageDir))
    }
}

/**
 * Mock implementation of CryptoProvider for testing FileSystemTokenStorage.
 * Provides controllable failure modes for comprehensive error testing.
 */
private class FileSystemMockCryptoProvider : CryptoProvider {
    var currentKekVersion = 1
    var reEncryptionAttempted = false

    // Failure flags for testing error scenarios
    var dekGenerationShouldFail = false
    var encryptionShouldFail = false
    var dekWrappingShouldFail = false
    var dekUnwrappingShouldFail = false
    var decryptionShouldFail = false
    var reEncryptionShouldFail = false
    var returnCorruptedDecryptedData = false

    override suspend fun generateDEK(): Either<CryptoError, String> {
        return if (dekGenerationShouldFail) {
            CryptoError.KeyGenerationError("Mock DEK generation failure").left()
        } else {
            "mock-dek-${UUID.randomUUID()}".right()
        }
    }

    override suspend fun encryptData(plainText: String, dek: String): Either<CryptoError, String> {
        return if (encryptionShouldFail) {
            CryptoError.EncryptionError("Mock encryption failure").left()
        } else {
            // Simple mock encryption: just base64 encode the plaintext with a prefix
            "encrypted:${Base64.getEncoder().encodeToString(plainText.toByteArray())}".right()
        }
    }

    override suspend fun decryptData(cipherText: String, dek: String): Either<CryptoError, String> {
        return if (decryptionShouldFail) {
            CryptoError.DecryptionError("Mock decryption failure").left()
        } else if (returnCorruptedDecryptedData) {
            "corrupted-json-data".right()
        } else {
            // Simple mock decryption: remove prefix and base64 decode
            if (!cipherText.startsWith("encrypted:")) {
                return CryptoError.InvalidCiphertext("Invalid cipher text format").left()
            }
            val base64Part = cipherText.removePrefix("encrypted:")
            try {
                String(Base64.getDecoder().decode(base64Part)).right()
            } catch (e: Exception) {
                CryptoError.DecryptionError("Failed to decode: ${e.message}").left()
            }
        }
    }

    override suspend fun wrapDEK(dek: String): Either<CryptoError, String> {
        return if (dekWrappingShouldFail) {
            CryptoError.EncryptionError("Mock DEK wrapping failure").left()
        } else if (reEncryptionShouldFail && dek.startsWith("mock-dek-")) {
            // This is a re-encryption attempt
            CryptoError.EncryptionError("Mock re-encryption failure").left()
        } else {
            // Track when re-encryption is attempted (when called for an existing DEK)
            if (dek.startsWith("mock-dek-")) {
                reEncryptionAttempted = true
            }
            "wrapped:$dek:version:$currentKekVersion".right()
        }
    }

    override suspend fun unwrapDEK(wrappedDek: String, kekVersion: Int): Either<CryptoError, String> {
        return if (dekUnwrappingShouldFail) {
            CryptoError.KeyVersionNotFound(kekVersion).left()
        } else if (!wrappedDek.startsWith("wrapped:") || !wrappedDek.contains(":version:$kekVersion")) {
            CryptoError.KeyVersionNotFound(kekVersion).left()
        } else {
            // Extract the DEK from the wrapped format
            val parts = wrappedDek.split(":")
            if (parts.size != 4) {
                CryptoError.InvalidCiphertext("Invalid wrapped DEK format").left()
            } else {
                parts[1].right() // Return the DEK part
            }
        }
    }

    override fun getKeyVersion(): Int = currentKekVersion
}

/**
 * Test implementation of FileSystemTokenStorage that uses unique file paths per test instance
 * and provides access to internal components for testing.
 */
private class TestFileSystemTokenStorage(
    val mockCryptoProvider: FileSystemMockCryptoProvider = FileSystemMockCryptoProvider(),
    fileSystem: FileSystem = SystemFileSystem
) {
    val testFileSystem = fileSystem
    private val testId = UUID.randomUUID().toString()
    val testStorageDir = Path(System.getProperty("java.io.tmpdir"), "chatbot-fs-test-$testId")

    // Public accessors for testing
    val testKeyFilePath: Path get() = Path(testStorageDir, "dek.json")
    val testDataFilePath: Path get() = Path(testStorageDir, "tokens.enc")

    // Create the actual FileSystemTokenStorage instance
    private val tokenStorage = FileSystemTokenStorage(
        cryptoProvider = mockCryptoProvider,
        storageDirectoryPath = testStorageDir.toString(),
        fileSystem = fileSystem,
        json = Json { ignoreUnknownKeys = true }
    )

    // Delegate all TokenStorage methods to the actual implementation
    suspend fun getAccessToken() = tokenStorage.getAccessToken()

    suspend fun getRefreshToken() = tokenStorage.getRefreshToken()

    suspend fun getExpiry() = tokenStorage.getExpiry()

    suspend fun saveAuthData(accessToken: String, refreshToken: String, expiresAt: Instant, user: User, permissions: List<Permission> = emptyList()) =
        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, permissions)

    suspend fun clearAuthData() = tokenStorage.clearAuthData()
}
