package eu.torvian.chatbot.app.service.mcp

import eu.torvian.chatbot.app.domain.models.LocalMCPServer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for LocalMCPServerProcessManager.
 *
 * These tests verify the process lifecycle management functionality.
 *
 * Note: Some tests use simple commands like "java -version" for testing
 * process management without requiring actual MCP server installations.
 */
class LocalMCPServerProcessManagerTest {
    private lateinit var processManager: LocalMCPServerProcessManager
    private val testClock = Clock.System

    @BeforeEach
    fun setup() {
        processManager = LocalMCPServerProcessManagerDesktop(testClock)
    }

    @AfterEach
    fun tearDown() = runTest {
        // Clean up any running processes
        processManager.stopAllServers()
    }

    @Test
    fun `startServer - success with valid command`() = runTest {
        // Given: Valid MCP server configuration using a simple command
        val config = createTestServer(
            id = 1L,
            command = "java",
            arguments = listOf("-version")
        )

        // When: Starting the server
        val result = processManager.startServer(config)

        // Then: Process starts successfully
        assertTrue(result.isRight(), "Expected successful start")

        // And: Status shows RUNNING
        val status = processManager.getServerStatus(1L)
        assertNotNull(status, "Expected status to be available")
        assertEquals(ProcessState.RUNNING, status.state, "Expected process to be running")
        assertNotNull(status.pid, "Expected PID to be set")
        assertNotNull(status.startedAt, "Expected start time to be set")
    }

    @Test
    fun `startServer - fails with already running process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 2L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Attempting to start the same server again
        val result = processManager.startServer(config)

        // Then: Returns ProcessAlreadyRunning error
        assertTrue(result.isLeft(), "Expected error")
        val error = result.leftOrNull()
        assertTrue(error is StartServerError.ProcessAlreadyRunning, "Expected ProcessAlreadyRunning error")
        assertEquals(2L, (error as StartServerError.ProcessAlreadyRunning).serverId)
    }

    @Test
    fun `startServer - fails with blank command`() = runTest {
        // Given: Config with blank command
        val config = createTestServer(
            id = 3L,
            command = "",
            arguments = emptyList()
        )

        // When: Starting the server
        val result = processManager.startServer(config)

        // Then: Returns InvalidConfiguration error
        assertTrue(result.isLeft(), "Expected error")
        val error = result.leftOrNull()
        assertTrue(error is StartServerError.InvalidConfiguration, "Expected InvalidConfiguration error")
        assertEquals(3L, (error as StartServerError.InvalidConfiguration).serverId)
        assertTrue(error.reason.contains("blank"), "Expected reason to mention blank command")
    }

    @Test
    fun `startServer - fails with non-existent file path`() = runTest {
        // Given: Config with non-existent file path
        val config = createTestServer(
            id = 4L,
            command = "/non/existent/path/to/server",
            arguments = emptyList()
        )

        // When: Starting the server
        val result = processManager.startServer(config)

        // Then: Returns InvalidConfiguration error
        assertTrue(result.isLeft(), "Expected error")
        val error = result.leftOrNull()
        assertTrue(error is StartServerError.InvalidConfiguration, "Expected InvalidConfiguration error")
        assertEquals(4L, (error as StartServerError.InvalidConfiguration).serverId)
        assertTrue(error.reason.contains("does not exist"), "Expected reason to mention file not found")
    }

    @Test
    fun `stopServer - success with running process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 5L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Stopping the server
        val result = processManager.stopServer(5L)

        // Then: Process stops successfully
        assertTrue(result.isRight(), "Expected successful stop")

        // And: Status shows STOPPED or ERROR (process may exit immediately)
        val status = processManager.getServerStatus(5L)
        assertNotNull(status, "Expected status to be available")
        assertTrue(
            status.state == ProcessState.STOPPED || status.state == ProcessState.ERROR,
            "Expected process to be stopped or in error state"
        )
    }

    @Test
    fun `stopServer - fails when process not running`() = runTest {
        // Given: No running process

        // When: Attempting to stop a non-existent server
        val result = processManager.stopServer(6L)

        // Then: Returns ProcessNotRunning error
        assertTrue(result.isLeft(), "Expected error")
        val error = result.leftOrNull()
        assertTrue(error is StopServerError.ProcessNotRunning, "Expected ProcessNotRunning error")
        assertEquals(6L, (error as StopServerError.ProcessNotRunning).serverId)
    }

    @Test
    fun `getServerStatus - returns RUNNING for active process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 7L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Querying status
        val status = processManager.getServerStatus(7L)

        // Then: Returns RUNNING status
        assertNotNull(status, "Expected status to be available")
        assertEquals(ProcessState.RUNNING, status.state, "Expected process to be running")
        assertNotNull(status.pid, "Expected PID to be set")
        assertNotNull(status.startedAt, "Expected start time to be set")
    }

    @Test
    fun `getServerStatus - returns STOPPED for non-existent process`() = runTest {
        // Given: No running process

        // When: Querying status
        val status = processManager.getServerStatus(8L)

        // Then: Returns STOPPED status
        assertNotNull(status, "Expected status to be available")
        assertEquals(ProcessState.STOPPED, status.state, "Expected process to be stopped")
        assertEquals(8L, status.serverId)
    }

    @Test
    fun `restartServer - successfully restarts a process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 9L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Restarting the server
        val result = processManager.restartServer(config)

        // Then: Restart is successful
        assertTrue(result.isRight(), "Expected successful restart")

        // And: Process is running with potentially different PID
        val newStatus = processManager.getServerStatus(9L)
        assertNotNull(newStatus, "Expected status to be available")
        assertEquals(ProcessState.RUNNING, newStatus.state, "Expected process to be running")
        // Note: PIDs may differ between restarts
    }

    @Test
    fun `stopAllServers - stops multiple running processes`() = runTest {
        // Given: Multiple running processes
        val config1 = createTestServer(id = 10L, command = "java", arguments = listOf("-version"))
        val config2 = createTestServer(id = 11L, command = "java", arguments = listOf("-version"))
        processManager.startServer(config1)
        processManager.startServer(config2)

        // When: Stopping all servers
        val stoppedCount = processManager.stopAllServers()

        // Then: Both processes are stopped
        assertTrue(stoppedCount >= 2, "Expected at least 2 processes to be stopped, got $stoppedCount")

        // And: Statuses show STOPPED
        val status1 = processManager.getServerStatus(10L)
        val status2 = processManager.getServerStatus(11L)
        assertNotNull(status1)
        assertNotNull(status2)
        assertTrue(
            status1.state == ProcessState.STOPPED || status1.state == ProcessState.ERROR,
            "Expected process 10 to be stopped"
        )
        assertTrue(
            status2.state == ProcessState.STOPPED || status2.state == ProcessState.ERROR,
            "Expected process 11 to be stopped"
        )
    }

    @Test
    fun `getProcessInputStream - returns stream for running process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 12L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Getting input stream
        val stream = processManager.getProcessInputStream(12L)

        // Then: Stream is available
        assertNotNull(stream, "Expected input stream to be available")
    }

    @Test
    fun `getProcessOutputStream - returns stream for running process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 13L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Getting output stream
        val stream = processManager.getProcessOutputStream(13L)

        // Then: Stream is available
        assertNotNull(stream, "Expected output stream to be available")
    }

    @Test
    fun `getProcessErrorStream - returns stream for running process`() = runTest {
        // Given: A running process
        val config = createTestServer(
            id = 14L,
            command = "java",
            arguments = listOf("-version")
        )
        processManager.startServer(config)

        // When: Getting error stream
        val stream = processManager.getProcessErrorStream(14L)

        // Then: Stream is available
        assertNotNull(stream, "Expected error stream to be available")
    }

    @Test
    fun `startServer - respects environment variables`() = runTest {
        // Given: Config with environment variables
        val config = createTestServer(
            id = 15L,
            command = "java",
            arguments = listOf("-version"),
            environmentVariables = mapOf("TEST_VAR" to "test_value")
        )

        // When: Starting the server
        val result = processManager.startServer(config)

        // Then: Process starts successfully (environment variables are set internally)
        assertTrue(result.isRight(), "Expected successful start")
    }

    // Helper function to create test LocalMCPServer instances
    private fun createTestServer(
        id: Long,
        command: String,
        arguments: List<String>,
        environmentVariables: Map<String, String> = emptyMap(),
        workingDirectory: String? = null
    ): LocalMCPServer {
        return LocalMCPServer(
            id = id,
            userId = 1L,
            name = "test-server-$id",
            description = "Test server $id",
            command = command,
            arguments = arguments,
            environmentVariables = environmentVariables,
            workingDirectory = workingDirectory,
            isEnabled = true,
            autoStartOnEnable = false,
            autoStartOnLaunch = false,
            autoStopAfterInactivitySeconds = null,
            createdAt = testClock.now(),
            updatedAt = testClock.now()
        )
    }
}
