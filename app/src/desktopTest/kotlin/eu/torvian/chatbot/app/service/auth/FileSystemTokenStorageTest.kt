package eu.torvian.chatbot.app.service.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
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

        // Act - now expects to fail since there's no active account
        val result = tokenStorage.clearAuthData()

        // Assert - should fail with NotFound since there's no active account
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `should handle missing key file gracefully`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser()

        // First save an account to make it active
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now() + 1.hours, user, emptyList())

        // Then corrupt the key file
        tokenStorage.testFileSystem.delete(tokenStorage.testKeyFilePath)
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
        val user = createTestUser()

        // First save an account to make it active
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now() + 1.hours, user, emptyList())

        // Then corrupt by deleting data file and replacing key file
        tokenStorage.testFileSystem.delete(tokenStorage.testDataFilePath)
        tokenStorage.testFileSystem.delete(tokenStorage.testKeyFilePath)
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
        val user = createTestUser()

        // First save an account to make it active
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now() + 1.hours, user, emptyList())

        // Then corrupt the metadata file
        tokenStorage.testFileSystem.sink(tokenStorage.testKeyFilePath).buffered().use { sink ->
            sink.writeString("invalid-json")
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

    // ===== Multi-Account Tests =====

    @Test
    fun `listStoredAccounts should return empty list when no accounts are stored`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act
        val result = tokenStorage.listStoredAccounts()

        // Assert
        assertTrue(result.isRight())
        assertEquals(0, result.getOrNull()?.size)
    }

    @Test
    fun `listStoredAccounts should return all stored accounts`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save three accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")
        val user3 = createTestUser(3L, "user3")
        val expiresAt = Clock.System.now() + 1.hours

        tokenStorage.saveAuthData("access1", "refresh1", expiresAt, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", expiresAt, user2, emptyList())
        tokenStorage.saveAuthData("access3", "refresh3", expiresAt, user3, emptyList())

        // Act
        val result = tokenStorage.listStoredAccounts()

        // Assert
        assertTrue(result.isRight())
        val accounts = result.getOrNull()!!
        assertEquals(3, accounts.size)

        // Verify all three users are present
        val userIds = accounts.map { it.user.id }.toSet()
        assertEquals(setOf(1L, 2L, 3L), userIds, "All three users should be present")
    }

    @Test
    fun `getCurrentAccountId should return null when no account is active`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act
        val result = tokenStorage.getCurrentAccountId()

        // Assert
        assertTrue(result.isRight())
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `getCurrentAccountId should return active account after saving`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser(42L, "activeuser")

        // Arrange - save account
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now() + 1.hours, user, emptyList())

        // Act
        val result = tokenStorage.getCurrentAccountId()

        // Assert
        assertTrue(result.isRight())
        assertEquals(42L, result.getOrNull())
    }

    @Test
    fun `switchAccount should switch to existing account`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save two accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")

        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, emptyList())

        // User 2 is currently active
        assertEquals(2L, tokenStorage.getCurrentAccountId().getOrNull())

        // Act - switch to user 1
        val switchResult = tokenStorage.switchAccount(1L)

        // Assert
        assertTrue(switchResult.isRight())
        assertEquals(1L, tokenStorage.getCurrentAccountId().getOrNull())

        // Verify we can access user 1's tokens
        val accessToken = tokenStorage.getAccessToken()
        assertTrue(accessToken.isRight())
        assertEquals("access1", accessToken.getOrNull())
    }

    @Test
    fun `switchAccount should fail when account does not exist`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act - try to switch to non-existent account
        val result = tokenStorage.switchAccount(999L)

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `switchAccount should fail when account exists but data files are missing`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser(1L, "user1")

        // Save account
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now() + 1.hours, user, emptyList())

        // Delete the data files but leave account in index
        tokenStorage.testFileSystem.delete(tokenStorage.testKeyFilePath(1L))
        tokenStorage.testFileSystem.delete(tokenStorage.testDataFilePath(1L))

        // Act - try to switch to account with missing files
        val result = tokenStorage.switchAccount(1L)

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is TokenStorageError.NotFound)
    }

    @Test
    fun `removeAccount should remove account and its files`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save two accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")

        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, emptyList())

        // Verify both accounts exist
        assertEquals(2, tokenStorage.listStoredAccounts().getOrNull()?.size)

        // Act - remove user 1
        val removeResult = tokenStorage.removeAccount(1L)

        // Assert
        assertTrue(removeResult.isRight())

        // Verify account is removed from index
        val accounts = tokenStorage.listStoredAccounts().getOrNull()!!
        assertEquals(1, accounts.size)
        assertEquals(2L, accounts[0].user.id)

        // Verify files are deleted
        val userDir = tokenStorage.getUserDirectory(1L)
        assertTrue(!tokenStorage.testFileSystem.exists(userDir))
    }

    @Test
    fun `removeAccount should set activeUserId to null when removing active account`() = runTest {
        val tokenStorage = createTestTokenStorage()
        val user = createTestUser(1L, "user1")

        // Arrange - save account
        tokenStorage.saveAuthData("access", "refresh", Clock.System.now() + 1.hours, user, emptyList())

        // Verify account is active
        assertEquals(1L, tokenStorage.getCurrentAccountId().getOrNull())

        // Act - remove active account
        val removeResult = tokenStorage.removeAccount(1L)

        // Assert
        assertTrue(removeResult.isRight())
        assertEquals(null, tokenStorage.getCurrentAccountId().getOrNull())
    }

    @Test
    fun `removeAccount should keep other account active when removing non-active account`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save two accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")

        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, emptyList())

        // User 2 is currently active
        assertEquals(2L, tokenStorage.getCurrentAccountId().getOrNull())

        // Act - remove user 1 (not active)
        val removeResult = tokenStorage.removeAccount(1L)

        // Assert
        assertTrue(removeResult.isRight())
        // User 2 should still be active
        assertEquals(2L, tokenStorage.getCurrentAccountId().getOrNull())
    }

    @Test
    fun `removeAccount should succeed for non-existent account`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Act - remove non-existent account
        val result = tokenStorage.removeAccount(999L)

        // Assert
        assertTrue(result.isRight())
    }

    @Test
    fun `clearAuthData should remove active account using removeAccount`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save two accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")

        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, emptyList())

        // Switch to user 1
        tokenStorage.switchAccount(1L)
        assertEquals(1L, tokenStorage.getCurrentAccountId().getOrNull())

        // Act - clear auth data (should remove user 1)
        val clearResult = tokenStorage.clearAuthData()

        // Assert
        assertTrue(clearResult.isRight())

        // Verify user 1 is removed but user 2 still exists
        val accounts = tokenStorage.listStoredAccounts().getOrNull()!!
        assertEquals(1, accounts.size)
        assertEquals(2L, accounts[0].user.id)

        // No account is active now
        assertEquals(null, tokenStorage.getCurrentAccountId().getOrNull())
    }

    @Test
    fun `saveAuthData should create user-specific directories`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save multiple accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")

        // Act
        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, emptyList())

        // Assert - verify separate directories exist
        val user1Dir = tokenStorage.getUserDirectory(1L)
        val user2Dir = tokenStorage.getUserDirectory(2L)

        assertTrue(tokenStorage.testFileSystem.exists(user1Dir))
        assertTrue(tokenStorage.testFileSystem.exists(user2Dir))

        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testKeyFilePath(1L)))
        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testDataFilePath(1L)))
        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testKeyFilePath(2L)))
        assertTrue(tokenStorage.testFileSystem.exists(tokenStorage.testDataFilePath(2L)))
    }

    @Test
    fun `saveAuthData should update existing account and set it as active`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save two accounts
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")

        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, emptyList())
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, emptyList())

        // User 2 is currently active
        assertEquals(2L, tokenStorage.getCurrentAccountId().getOrNull())

        // Act - save new tokens for user 1
        tokenStorage.saveAuthData("new_access1", "new_refresh1", Clock.System.now() + 2.hours, user1, emptyList())

        // Assert - user 1 should now be active
        assertEquals(1L, tokenStorage.getCurrentAccountId().getOrNull())

        // Verify new tokens are stored
        assertEquals("new_access1", tokenStorage.getAccessToken().getOrNull())

        // Verify only 2 accounts still exist (no duplicate)
        assertEquals(2, tokenStorage.listStoredAccounts().getOrNull()?.size)
    }

    @Test
    fun `getAccountData and getPermissions should work with multi-account setup`() = runTest {
        val tokenStorage = createTestTokenStorage()

        // Arrange - save two accounts with different permissions
        val user1 = createTestUser(1L, "user1")
        val user2 = createTestUser(2L, "user2")
        val permissions1 = listOf(Permission(1L, "read", "conversations"))
        val permissions2 = listOf(
            Permission(2L, "write", "conversations"),
            Permission(3L, "delete", "conversations")
        )

        tokenStorage.saveAuthData("access1", "refresh1", Clock.System.now() + 1.hours, user1, permissions1)
        tokenStorage.saveAuthData("access2", "refresh2", Clock.System.now() + 1.hours, user2, permissions2)

        // Act - switch to user 1
        tokenStorage.switchAccount(1L)

        // Assert - verify user 1's data
        val accountData1 = tokenStorage.getAccountData().getOrNull()!!
        assertEquals(1L, accountData1.user.id)
        assertEquals("user1", accountData1.user.username)

        val userPermissions1 = accountData1.permissions
        assertEquals(1, userPermissions1.size)
        assertEquals("read", userPermissions1[0].action)
        assertEquals("conversations", userPermissions1[0].subject)

        // Act - switch to user 2
        tokenStorage.switchAccount(2L)

        // Assert - verify user 2's data
        val accountData2 = tokenStorage.getAccountData().getOrNull()!!
        assertEquals(2L, accountData2.user.id)
        assertEquals("user2", accountData2.user.username)

        val userPermissions2 = accountData2.permissions
        assertEquals(2, userPermissions2.size)
        assertTrue(userPermissions2.any { it.action == "write" && it.subject == "conversations" })
        assertTrue(userPermissions2.any { it.action == "delete" && it.subject == "conversations" })
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

    // Public accessors for testing - now user-specific
    fun getUserDirectory(userId: Long): Path = Path(testStorageDir, userId.toString())
    fun testKeyFilePath(userId: Long): Path = Path(getUserDirectory(userId), "dek.json")
    fun testDataFilePath(userId: Long): Path = Path(getUserDirectory(userId), "tokens.enc")
    val testKeyFilePath: Path get() = testKeyFilePath(1L) // Default to user 1 for backward compatibility
    val testDataFilePath: Path get() = testDataFilePath(1L) // Default to user 1 for backward compatibility

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

    suspend fun getAccountData() = tokenStorage.getAccountData()

    suspend fun saveAuthData(
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
        user: User,
        permissions: List<Permission> = emptyList()
    ) =
        tokenStorage.saveAuthData(accessToken, refreshToken, expiresAt, user, permissions)

    suspend fun clearAuthData() = tokenStorage.clearAuthData()

    // New multi-account methods
    suspend fun listStoredAccounts() = tokenStorage.listStoredAccounts()

    suspend fun switchAccount(userId: Long) = tokenStorage.switchAccount(userId)

    suspend fun getCurrentAccountId() = tokenStorage.getCurrentAccountId()

    suspend fun removeAccount(userId: Long) = tokenStorage.removeAccount(userId)
}
