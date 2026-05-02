package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPSecretEnvironmentVariableReference
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerWorkerOwnershipMismatchError
import eu.torvian.chatbot.server.service.security.CredentialManager
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
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [LocalMCPServerServiceImpl].
 */
class LocalMCPServerServiceImplTest {
    private val localMCPServerDao = mockk<LocalMCPServerDao>()
    private val localMCPToolDefinitionDao = mockk<LocalMCPToolDefinitionDao>()
    private val workerDao = mockk<WorkerDao>()
    private val credentialManager = mockk<CredentialManager>()
    private val transactionScope = mockk<TransactionScope>()

    private val service = LocalMCPServerServiceImpl(
        localMCPServerDao = localMCPServerDao,
        localMCPToolDefinitionDao = localMCPToolDefinitionDao,
        workerDao = workerDao,
        credentialManager = credentialManager,
        transactionScope = transactionScope
    )

    private val workerEntity = WorkerEntity(
        id = 17L,
        workerUid = "worker-17",
        ownerUserId = 5L,
        displayName = "worker",
        certificatePem = "pem",
        certificateFingerprint = "fp",
        allowedScopes = listOf("messages:read"),
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        lastSeenAt = null
    )

    @BeforeEach
    fun setUp() {
        clearMocks(localMCPServerDao, localMCPToolDefinitionDao, workerDao, credentialManager, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `createServer stores secret aliases and returns plaintext secret values`() = runTest {
        val createPayloadSlot: CapturingSlot<CreateLocalMCPServerEntity> = slot()
        val request = CreateLocalMCPServerRequest(
            workerId = workerEntity.id,
            name = "filesystem",
            command = "npx",
            arguments = listOf("-y", "@modelcontextprotocol/server-filesystem"),
            environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_KEY", "secret-token"))
        )

        coEvery { workerDao.getWorkerById(workerEntity.id) } returns workerEntity.right()
        coEvery { credentialManager.storeCredential("secret-token") } returns "alias-1"
        coEvery { localMCPServerDao.createServer(capture(createPayloadSlot)) } answers {
            val payload = createPayloadSlot.captured
            LocalMCPServerEntity(
                id = 99L,
                userId = payload.userId,
                workerId = payload.workerId,
                name = payload.name,
                description = payload.description,
                command = payload.command,
                arguments = payload.arguments,
                workingDirectory = payload.workingDirectory,
                isEnabled = payload.isEnabled,
                autoStartOnEnable = payload.autoStartOnEnable,
                autoStartOnLaunch = payload.autoStartOnLaunch,
                autoStopAfterInactivitySeconds = payload.autoStopAfterInactivitySeconds,
                toolNamePrefix = payload.toolNamePrefix,
                environmentVariables = payload.environmentVariables,
                secretEnvironmentVariables = payload.secretEnvironmentVariables,
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
            )
        }

        val result = service.createServer(userId = workerEntity.ownerUserId, request = request)

        assertTrue(result.isRight())
        val server = result.getOrNull()!!
        assertEquals("alias-1", createPayloadSlot.captured.secretEnvironmentVariables.single().alias)
        assertEquals("secret-token", server.secretEnvironmentVariables.single().value)
        coVerify(exactly = 1) { credentialManager.storeCredential("secret-token") }
    }

    @Test
    fun `updateServer rejects assignment to worker owned by another user`() = runTest {
        val ownerEntity = workerEntity.copy(ownerUserId = 999L)
        val request = CreateLocalMCPServerRequest(
            workerId = ownerEntity.id,
            name = "filesystem",
            command = "npx"
        )

        coEvery { workerDao.getWorkerById(ownerEntity.id) } returns ownerEntity.right()

        val result = service.createServer(userId = 5L, request = request)

        assertTrue(result.isLeft())
        assertEquals(
            LocalMCPServerWorkerOwnershipMismatchError(
                userId = 5L,
                workerId = ownerEntity.id,
                workerOwnerUserId = ownerEntity.ownerUserId
            ),
            result.leftOrNull()
        )
    }

    @Test
    fun `getServersByWorkerId resolves secret aliases using credential manager`() = runTest {
        val entity = LocalMCPServerEntity(
            id = 44L,
            userId = workerEntity.ownerUserId,
            workerId = workerEntity.id,
            name = "filesystem",
            description = null,
            command = "npx",
            arguments = emptyList(),
            workingDirectory = null,
            isEnabled = true,
            autoStartOnEnable = false,
            autoStartOnLaunch = false,
            autoStopAfterInactivitySeconds = null,
            toolNamePrefix = null,
            environmentVariables = emptyList(),
            secretEnvironmentVariables = listOf(LocalMCPSecretEnvironmentVariableReference("API_KEY", "alias-2")),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
        )

        coEvery { localMCPServerDao.getServersByWorkerId(workerEntity.id) } returns listOf(entity)
        coEvery { credentialManager.getCredential("alias-2") } returns "resolved-value".right()

        val result = service.getServersByWorkerId(workerEntity.id)

        assertTrue(result.isRight())
        assertEquals("resolved-value", result.getOrNull()!!.single().secretEnvironmentVariables.single().value)
    }
}


