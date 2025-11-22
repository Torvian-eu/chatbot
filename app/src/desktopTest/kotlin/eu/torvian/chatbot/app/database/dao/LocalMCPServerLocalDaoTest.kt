package eu.torvian.chatbot.app.database.dao

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.torvian.chatbot.app.database.LocalDatabase
import eu.torvian.chatbot.app.database.LocalDatabaseProvider
import eu.torvian.chatbot.app.database.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.GetLocalMCPServerError
import eu.torvian.chatbot.app.database.dao.error.UpdateLocalMCPServerError
import eu.torvian.chatbot.app.service.misc.EncryptedSecretService
import eu.torvian.chatbot.app.service.misc.EncryptedSecretServiceImpl
import eu.torvian.chatbot.app.testutils.misc.TestClock
import eu.torvian.chatbot.app.utils.transaction.SqlDelightTransactionScope
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import eu.torvian.chatbot.common.security.EncryptedSecret
import eu.torvian.chatbot.common.security.EncryptionService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import java.util.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import arrow.core.right

/**
 * Tests for LocalMCPServerLocalDaoImpl.
 *
 * These tests use an in-memory SQLite database to verify DAO operations,
 * providing a real TransactionScope with an Unconfined dispatcher.
 *
 * Tests verify:
 * - Insert operations with environment variable encryption
 * - Update operations including environment variable changes
 * - Delete operations with secret cleanup
 * - Retrieval operations with decryption
 * - Error handling for various failure scenarios
 */
class LocalMCPServerLocalDaoTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: LocalDatabase
    private lateinit var transactionScope: TransactionScope
    private lateinit var encryptedSecretDao: EncryptedSecretLocalDao
    private lateinit var encryptedSecretService: EncryptedSecretService
    private lateinit var encryptionService: EncryptionService
    private lateinit var dao: LocalMCPServerLocalDao
    private lateinit var clock: TestClock

    @BeforeTest
    fun setup() {
        // Create in-memory database driver
        driver = JdbcSqliteDriver(
            url = JdbcSqliteDriver.IN_MEMORY,
            properties = Properties().apply {
                put("foreign_keys", "true")  // Enable foreign key constraint enforcement
            },
            schema = LocalDatabase.Schema.synchronous()
        )
        database = LocalDatabaseProvider.createDatabase(driver)

        // Create a transaction scope specifically for testing
        transactionScope = SqlDelightTransactionScope(
            transacter = database,
            coroutineContext = Dispatchers.Unconfined
        )

        // Create encrypted secret DAO
        encryptedSecretDao = EncryptedSecretLocalDaoImpl(
            queries = database.encryptedSecretTableQueries,
            transactionScope = transactionScope
        )

        // Create test clock
        clock = TestClock(Instant.fromEpochMilliseconds(1000L))

        // Create mock encryption service
        encryptionService = mockk()
        coEvery { encryptionService.encrypt(any()) } answers {
            val plaintext = firstArg<String>()
            EncryptedSecret(
                encryptedSecret = plaintext, // Just return plaintext for testing
                encryptedDEK = "mock-dek",
                keyVersion = 1
            ).right()
        }
        coEvery { encryptionService.decrypt(any()) } answers {
            val encrypted = firstArg<EncryptedSecret>()
            encrypted.encryptedSecret.right() // Return the "encrypted" value as plaintext
        }

        // Create encrypted secret service
        encryptedSecretService = EncryptedSecretServiceImpl(
            encryptionService = encryptionService,
            dao = encryptedSecretDao,
            clock = clock
        )

        // Create the DAO instance
        dao = LocalMCPServerLocalDaoImpl(
            queries = database.localMCPServerLocalTableQueries,
            encryptedSecretService = encryptedSecretService,
            transactionScope = transactionScope
        )
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }


    private fun createTestServer(
        id: Long = 1L,
        userId: Long = 1L,
        name: String = "Test Server",
        description: String? = "Test Description",
        command: String = "python",
        arguments: List<String> = listOf("-m", "mcp_server"),
        environmentVariables: Map<String, String> = emptyMap(),
        workingDirectory: String? = null,
        isEnabled: Boolean = true,
        autoStartOnEnable: Boolean = false,
        autoStartOnLaunch: Boolean = false,
        autoStopAfterInactivitySeconds: Int? = null,
        toolsEnabledByDefault: Boolean = false,
        createdAt: Instant = clock.now(),
        updatedAt: Instant = clock.now()
    ): LocalMCPServer = LocalMCPServer(
        id = id,
        userId = userId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        environmentVariables = environmentVariables,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolsEnabledByDefault = toolsEnabledByDefault,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Test
    fun `insert should create new server with encrypted environment variables`() = runTest {
        // Arrange
        val server = createTestServer(
            environmentVariables = mapOf("API_KEY" to "secret123")
        )

        // Act
        val inserted = dao.insert(server)

        // Assert
        assertEquals(server.id, inserted.id)
        assertEquals(server.name, inserted.name)
        assertEquals(server.environmentVariables, inserted.environmentVariables)
    }

    @Test
    fun `insert should create server without environment variables`() = runTest {
        // Arrange
        val server = createTestServer(environmentVariables = emptyMap())

        // Act
        val inserted = dao.insert(server)

        // Assert
        assertEquals(server.id, inserted.id)
        assertTrue(inserted.environmentVariables.isEmpty())
    }

    @Test
    fun `update should update server configuration`() = runTest {
        // Arrange
        val server = createTestServer()
        dao.insert(server)

        clock.advanceTime(1.seconds)
        val updated = server.copy(
            name = "Updated Server",
            command = "node",
            updatedAt = clock.now()
        )

        // Act
        val result = dao.update(updated)

        // Assert
        assertTrue(result.isRight(), "Update should succeed")

        val retrieved = dao.getById(server.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals("Updated Server", retrieved.name)
        assertEquals("node", retrieved.command)
    }

    @Test
    fun `update should handle environment variable changes`() = runTest {
        // Arrange
        val server = createTestServer(
            environmentVariables = mapOf("KEY1" to "value1")
        )
        dao.insert(server)

        clock.advanceTime(1.seconds)
        val updated = server.copy(
            environmentVariables = mapOf("KEY2" to "value2"),
            updatedAt = clock.now()
        )

        // Act
        val result = dao.update(updated)

        // Assert
        assertTrue(result.isRight())

        val retrieved = dao.getById(server.id).getOrNull()
        assertNotNull(retrieved)
        assertEquals(mapOf("KEY2" to "value2"), retrieved.environmentVariables)
    }

    @Test
    fun `update should return NotFound when server doesn't exist`() = runTest {
        // Arrange
        val server = createTestServer(id = 999L)

        // Act
        val result = dao.update(server)

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is UpdateLocalMCPServerError.NotFound)
    }

    @Test
    fun `update should return DuplicateName when name already exists for user`() = runTest {
        // Arrange
        val server1 = createTestServer(id = 1L, name = "Server 1")
        val server2 = createTestServer(id = 2L, name = "Server 2")
        dao.insert(server1)
        dao.insert(server2)

        val updated = server2.copy(name = "Server 1")

        // Act
        val result = dao.update(updated)

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is UpdateLocalMCPServerError.DuplicateName)
    }

    @Test
    fun `delete should remove server and cleanup encrypted secret`() = runTest {
        // Arrange
        val server = createTestServer(
            environmentVariables = mapOf("KEY" to "value")
        )
        dao.insert(server)

        // Act
        val result = dao.delete(server.id)

        // Assert
        assertTrue(result.isRight())

        val retrieved = dao.getById(server.id)
        assertTrue(retrieved.isLeft())
    }

    @Test
    fun `delete should return NotFound when server doesn't exist`() = runTest {
        // Act
        val result = dao.delete(999L)

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is DeleteLocalMCPServerError.NotFound)
    }

    @Test
    fun `getById should retrieve server with decrypted environment variables`() = runTest {
        // Arrange
        val server = createTestServer(
            environmentVariables = mapOf("KEY" to "value")
        )
        dao.insert(server)

        // Act
        val result = dao.getById(server.id)

        // Assert
        assertTrue(result.isRight())
        val retrieved = result.getOrNull()!!
        assertEquals(server.environmentVariables, retrieved.environmentVariables)
    }

    @Test
    fun `getById should return NotFound when server doesn't exist`() = runTest {
        // Act
        val result = dao.getById(999L)

        // Assert
        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is GetLocalMCPServerError.NotFound)
    }

    @Test
    fun `getAll should return all servers for user`() = runTest {
        // Arrange
        val userId = 1L
        val server1 = createTestServer(id = 1L, userId = userId, name = "Server 1")
        val server2 = createTestServer(id = 2L, userId = userId, name = "Server 2")
        dao.insert(server1)
        dao.insert(server2)

        // Act
        val servers = dao.getAll(userId)

        // Assert
        assertEquals(2, servers.size)
        assertTrue(servers.any { it.name == "Server 1" })
        assertTrue(servers.any { it.name == "Server 2" })
    }

    @Test
    fun `getAll should return only servers for specified user`() = runTest {
        // Arrange
        val server1 = createTestServer(id = 1L, userId = 1L, name = "User 1 Server")
        val server2 = createTestServer(id = 2L, userId = 2L, name = "User 2 Server")
        dao.insert(server1)
        dao.insert(server2)

        // Act
        val user1Servers = dao.getAll(1L)
        val user2Servers = dao.getAll(2L)

        // Assert
        assertEquals(1, user1Servers.size)
        assertEquals(1, user2Servers.size)
        assertEquals("User 1 Server", user1Servers[0].name)
        assertEquals("User 2 Server", user2Servers[0].name)
    }

    @Test
    fun `getAll should return empty list when user has no servers`() = runTest {
        // Act
        val servers = dao.getAll(1L)

        // Assert
        assertEquals(0, servers.size)
    }

    @Test
    fun `getAllEnabled should return only enabled servers`() = runTest {
        // Arrange
        val userId = 1L
        val enabledServer = createTestServer(id = 1L, userId = userId, isEnabled = true)
        val disabledServer = createTestServer(id = 2L, userId = userId, isEnabled = false)
        dao.insert(enabledServer)
        dao.insert(disabledServer)

        // Act
        val servers = dao.getAllEnabled(userId)

        // Assert
        assertEquals(1, servers.size)
        assertEquals(enabledServer.id, servers[0].id)
    }

    @Test
    fun `getAllEnabled should return empty list when no enabled servers`() = runTest {
        // Arrange
        val userId = 1L
        val disabledServer = createTestServer(id = 1L, userId = userId, isEnabled = false)
        dao.insert(disabledServer)

        // Act
        val servers = dao.getAllEnabled(userId)

        // Assert
        assertEquals(0, servers.size)
    }

    @Test
    fun `existsByName should return true when name exists for user`() = runTest {
        // Arrange
        val server = createTestServer(name = "Test Server")
        dao.insert(server)

        // Act
        val exists = dao.existsByName("Test Server", 1L)

        // Assert
        assertTrue(exists)
    }

    @Test
    fun `existsByName should return false when name doesn't exist for user`() = runTest {
        // Act
        val exists = dao.existsByName("Nonexistent Server", 1L)

        // Assert
        assertFalse(exists)
    }

    @Test
    fun `existsByName should exclude specified ID when checking`() = runTest {
        // Arrange
        val server = createTestServer(id = 1L, name = "Test Server")
        dao.insert(server)

        // Act
        val exists = dao.existsByName("Test Server", 1L, excludeId = 1L)

        // Assert
        assertFalse(exists, "Should not count the excluded server")
    }

    @Test
    fun `insert should preserve all server configuration fields`() = runTest {
        // Arrange
        val server = createTestServer(
            name = "Full Config Server",
            description = "Full configuration test",
            command = "docker",
            arguments = listOf("run", "-it", "mcp-server"),
            environmentVariables = mapOf("ENV1" to "value1", "ENV2" to "value2"),
            workingDirectory = "/app",
            isEnabled = false,
            autoStartOnEnable = true,
            autoStartOnLaunch = true,
            autoStopAfterInactivitySeconds = 600,
            toolsEnabledByDefault = true
        )

        // Act
        dao.insert(server)
        val retrieved = dao.getById(server.id).getOrNull()

        // Assert
        assertNotNull(retrieved)
        assertEquals(server.name, retrieved.name)
        assertEquals(server.description, retrieved.description)
        assertEquals(server.command, retrieved.command)
        assertEquals(server.arguments, retrieved.arguments)
        assertEquals(server.environmentVariables, retrieved.environmentVariables)
        assertEquals(server.workingDirectory, retrieved.workingDirectory)
        assertEquals(server.isEnabled, retrieved.isEnabled)
        assertEquals(server.autoStartOnEnable, retrieved.autoStartOnEnable)
        assertEquals(server.autoStartOnLaunch, retrieved.autoStartOnLaunch)
        assertEquals(server.autoStopAfterInactivitySeconds, retrieved.autoStopAfterInactivitySeconds)
        assertEquals(server.toolsEnabledByDefault, retrieved.toolsEnabledByDefault)
    }

    @Test
    fun `update should handle clearing environment variables`() = runTest {
        // Arrange
        val server = createTestServer(
            environmentVariables = mapOf("KEY1" to "value1")
        )
        dao.insert(server)

        clock.advanceTime(1.seconds)
        val updated = server.copy(
            environmentVariables = emptyMap(),
            updatedAt = clock.now()
        )

        // Act
        val result = dao.update(updated)

        // Assert
        assertTrue(result.isRight())

        val retrieved = dao.getById(server.id).getOrNull()
        assertNotNull(retrieved)
        assertTrue(retrieved.environmentVariables.isEmpty())
    }

    @Test
    fun `delete should handle server without environment variables`() = runTest {
        // Arrange
        val server = createTestServer(environmentVariables = emptyMap())
        dao.insert(server)

        // Act
        val result = dao.delete(server.id)

        // Assert
        assertTrue(result.isRight())
        val retrieved = dao.getById(server.id)
        assertTrue(retrieved.isLeft())
    }
}

