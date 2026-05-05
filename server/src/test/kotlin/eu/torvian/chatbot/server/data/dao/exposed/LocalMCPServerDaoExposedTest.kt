package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.error.DeleteLocalMCPServerError
import eu.torvian.chatbot.server.data.dao.error.LocalMCPServerError
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPSecretEnvironmentVariableReference
import eu.torvian.chatbot.server.data.entities.UpdateLocalMCPServerEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LocalMCPServerDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [LocalMCPServerDao]:
 * - Creating servers with enabled/disabled state
 * - Deleting servers by ID (success and NotFound cases)
 * - Getting server IDs by user ID
 * - Checking server existence
 * - Validating server ownership (authorized and unauthorized cases)
 * - Updating server enabled state
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class LocalMCPServerDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var dao: LocalMCPServerDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testUser1 = TestDefaults.user1
    private val testUser2 = TestDefaults.user2

    /**
     * Builds a minimal create payload for tests that only care about the generated row ID.
     *
     * The old `createServer(userId, isEnabled)` shortcut is deprecated, so these tests now
     * exercise the supported payload-based API while keeping the same intent.
     */
    private fun createServerEntity(
        userId: Long,
        isEnabled: Boolean,
        workerId: Long = 1L,
        name: String = "Test MCP Server",
        description: String? = null,
        command: String = "npx",
        arguments: List<String> = listOf("server"),
        workingDirectory: String? = null,
        autoStartOnEnable: Boolean = false,
        autoStartOnLaunch: Boolean = false,
        autoStopAfterInactivitySeconds: Int? = null,
        toolNamePrefix: String? = null,
        environmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList(),
        secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference> = emptyList()
    ): CreateLocalMCPServerEntity = CreateLocalMCPServerEntity(
        userId = userId,
        workerId = workerId,
        name = name,
        description = description,
        command = command,
        arguments = arguments,
        workingDirectory = workingDirectory,
        isEnabled = isEnabled,
        autoStartOnEnable = autoStartOnEnable,
        autoStartOnLaunch = autoStartOnLaunch,
        autoStopAfterInactivitySeconds = autoStopAfterInactivitySeconds,
        toolNamePrefix = toolNamePrefix,
        environmentVariables = environmentVariables,
        secretEnvironmentVariables = secretEnvironmentVariables
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        dao = container.get()
        testDataManager = container.get()

        // Create required tables
        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.LOCAL_MCP_SERVERS
            )
        )

        // Setup test users
        testDataManager.setup(
            TestDataSet(
                users = listOf(testUser1, testUser2)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `createServer should create new entry with enabled state and return ID`() = runTest {
        // Act
        val server = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))

        // Assert
        assertTrue(server.id > 0, "Generated ID should be positive")
        assertTrue(dao.existsById(server.id), "Server should exist")
    }

    @Test
    fun `createServer should create multiple IDs for same user`() = runTest {
        // Act
        val server1 = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))
        val server2 = dao.createServer(createServerEntity(testUser1.id, isEnabled = false))

        // Assert
        assertTrue(server1.id > 0, "First generated ID should be positive")
        assertTrue(server2.id > 0, "Second generated ID should be positive")
        assertTrue(server1.id != server2.id, "Generated IDs should be unique")
        assertTrue(dao.existsById(server1.id), "First server should exist")
        assertTrue(dao.existsById(server2.id), "Second server should exist")
    }

    @Test
    fun `deleteById should delete existing server`() = runTest {
        // Arrange
        val server = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))

        // Act
        val result = dao.deleteById(server.id)

        // Assert
        assertTrue(result.isRight(), "Deletion should succeed")
        assertFalse(dao.existsById(server.id), "Server should not exist after deletion")
    }

    @Test
    fun `deleteById should return NotFound when server doesn't exist`() = runTest {
        // Act
        val result = dao.deleteById(999L)

        // Assert
        assertTrue(result.isLeft(), "Should return error")
        assertIs<DeleteLocalMCPServerError.NotFound>(result.leftOrNull(), "Should be NotFound error")
    }

    @Test
    fun `getIdsByUserId should return all server IDs for user`() = runTest {
        // Arrange
        val server1 = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))
        val server2 = dao.createServer(createServerEntity(testUser1.id, isEnabled = false))

        // Act
        val serverIds = dao.getServersByUserId(testUser1.id).map { it.id }

        // Assert
        assertEquals(2, serverIds.size)
        assertTrue(serverIds.contains(server1.id))
        assertTrue(serverIds.contains(server2.id))
    }

    @Test
    fun `getIdsByUserId should return only servers for specified user`() = runTest {
        // Arrange
        val user1Server = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))
        val user2Server = dao.createServer(createServerEntity(testUser2.id, isEnabled = true))

        // Act
        val user1ServerIds = dao.getServersByUserId(testUser1.id).map { it.id }
        val user2ServerIds = dao.getServersByUserId(testUser2.id).map { it.id }

        // Assert
        assertEquals(1, user1ServerIds.size)
        assertEquals(1, user2ServerIds.size)
        assertTrue(user1ServerIds.contains(user1Server.id))
        assertFalse(user1ServerIds.contains(user2Server.id))
        assertTrue(user2ServerIds.contains(user2Server.id))
        assertFalse(user2ServerIds.contains(user1Server.id))
    }

    @Test
    fun `getIdsByUserId should return empty list when user has no servers`() = runTest {
        // Act
        val serverIds = dao.getServersByUserId(testUser1.id).map { it.id }

        // Assert
        assertEquals(0, serverIds.size)
    }

    @Test
    fun `existsById should return true when server exists`() = runTest {
        // Arrange
        val server = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))

        // Act & Assert
        assertTrue(dao.existsById(server.id))
    }

    @Test
    fun `existsById should return false when server doesn't exist`() = runTest {
        // Act & Assert
        assertFalse(dao.existsById(999L))
    }

    @Test
    fun `validateOwnership should succeed when user owns server`() = runTest {
        // Arrange
        val server = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))

        // Act
        val result = dao.validateOwnership(testUser1.id, server.id)

        // Assert
        assertTrue(result.isRight(), "Validation should succeed")
    }

    @Test
    fun `validateOwnership should return Unauthorized when user doesn't own server`() = runTest {
        // Arrange
        val server = dao.createServer(createServerEntity(testUser1.id, isEnabled = true))

        // Act
        val result = dao.validateOwnership(testUser2.id, server.id)

        // Assert
        assertTrue(result.isLeft(), "Should return error")
        assertIs<LocalMCPServerError.Unauthorized>(result.leftOrNull(), "Should be Unauthorized error")
    }

    @Test
    fun `validateOwnership should return Unauthorized when server doesn't exist`() = runTest {
        // Act
        val result = dao.validateOwnership(testUser1.id, 999L)

        // Assert
        assertTrue(result.isLeft(), "Should return error")
        assertIs<LocalMCPServerError.Unauthorized>(result.leftOrNull(), "Should be Unauthorized error")
    }

    @Test
    fun `createServer with full payload should persist env vars and secret aliases`() = runTest {
        val created = dao.createServer(
            CreateLocalMCPServerEntity(
                userId = testUser1.id,
                workerId = 12L,
                name = "filesystem",
                description = "test",
                command = "npx",
                arguments = listOf("-y", "server"),
                workingDirectory = "C:/tools",
                isEnabled = true,
                autoStartOnEnable = true,
                autoStartOnLaunch = false,
                autoStopAfterInactivitySeconds = 120,
                toolNamePrefix = "fs_",
                environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
                secretEnvironmentVariables = listOf(LocalMCPSecretEnvironmentVariableReference("API_KEY", "alias-123"))
            )
        )

        assertEquals("filesystem", created.name)
        assertEquals(12L, created.workerId)
        assertEquals("debug", created.environmentVariables.single().value)
        assertEquals("alias-123", created.secretEnvironmentVariables.single().alias)
    }

    @Test
    fun `updateServer should update assigned worker and return worker filtered results`() = runTest {
        val created = dao.createServer(
            CreateLocalMCPServerEntity(
                userId = testUser1.id,
                workerId = 100L,
                name = "initial",
                description = null,
                command = "cmd",
                arguments = emptyList(),
                workingDirectory = null,
                isEnabled = true,
                autoStartOnEnable = false,
                autoStartOnLaunch = false,
                autoStopAfterInactivitySeconds = null,
                toolNamePrefix = null,
                environmentVariables = emptyList(),
                secretEnvironmentVariables = emptyList()
            )
        )

        val updateResult = dao.updateServer(
            userId = testUser1.id,
            serverId = created.id,
            server = UpdateLocalMCPServerEntity(
                workerId = 101L,
                name = "updated",
                description = "updated",
                command = "npx",
                arguments = listOf("server"),
                workingDirectory = "C:/updated",
                isEnabled = false,
                autoStartOnEnable = true,
                autoStartOnLaunch = true,
                autoStopAfterInactivitySeconds = 90,
                toolNamePrefix = "updated_",
                environmentVariables = listOf(LocalMCPEnvironmentVariableDto("A", "1")),
                secretEnvironmentVariables = listOf(LocalMCPSecretEnvironmentVariableReference("B", "alias-b"))
            )
        )

        assertTrue(updateResult.isRight())
        assertEquals("updated", updateResult.getOrNull()!!.name)
        assertEquals(1, dao.getServersByWorkerId(101L).size)
        assertEquals(0, dao.getServersByWorkerId(100L).size)
    }
}

