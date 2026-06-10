package eu.torvian.chatbot.server.worker.mcp.configsync

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.service.core.LocalMCPServerSignerSnapshot
import eu.torvian.chatbot.server.service.core.LocalMCPServerService
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerNotFoundError
import eu.torvian.chatbot.server.worker.command.WorkerCommandDispatchError
import eu.torvian.chatbot.server.worker.mcp.runtimecontrol.LocalMCPRuntimeCommandDispatchError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Verifies persistence orchestration and compensation behavior in [DefaultLocalMCPServerConfigSyncService].
 */
class DefaultLocalMCPServerConfigSyncServiceTest {
    /**
     * Persistence service fixture.
     */
    private val localMCPServerService: LocalMCPServerService = mockk()

    /**
     * Low-level worker sync fixture.
     */
    private val localMCPServerWorkerSyncService: LocalMCPServerWorkerSyncService = mockk()

    /**
     * Subject under test.
     */
    private val service = DefaultLocalMCPServerConfigSyncService(localMCPServerService, localMCPServerWorkerSyncService)

    /**
     * Verifies successful create orchestration persists first and returns the created server.
     */
    @Test
    fun `create signed server returns persisted server when worker sync succeeds`() = runTest {
        val request = createRequestFixture(workerId = 41L)
        val signedRequest = signedRequestFixture("create-41")
        val createdServer = serverFixture(serverId = 101L, workerId = 41L)
        coEvery { localMCPServerService.createSignedServer(9L, request, signedRequest) } returns createdServer.right()
        coEvery {
            localMCPServerWorkerSyncService.syncCreated(SignedLocalMCPServerDto(createdServer, signedRequest))
        } returns Unit.right()

        val result = service.createSignedServer(userId = 9L, request = request, signedRequest = signedRequest)

        assertEquals(createdServer, rightValue(result))
        coVerify(exactly = 0) { localMCPServerService.deleteServer(any(), any()) }
    }

    /**
     * Verifies create sync failures trigger delete compensation before surfacing the sync error.
     */
    @Test
    fun `create signed server deletes created row when worker sync fails`() = runTest {
        val request = createRequestFixture(workerId = 41L)
        val signedRequest = signedRequestFixture("create-rollback")
        val createdServer = serverFixture(serverId = 102L, workerId = 41L)
        val syncError = syncErrorFixture(workerId = 41L)
        coEvery { localMCPServerService.createSignedServer(9L, request, signedRequest) } returns createdServer.right()
        coEvery {
            localMCPServerWorkerSyncService.syncCreated(SignedLocalMCPServerDto(createdServer, signedRequest))
        } returns syncError.left()
        coEvery { localMCPServerService.deleteServer(9L, createdServer.id) } returns Unit.right()

        val result = service.createSignedServer(userId = 9L, request = request, signedRequest = signedRequest)

        val error = leftValue(result)
        assertIs<LocalMCPServerConfigSyncError.WorkerSyncFailed>(error)
        assertEquals(syncError, error.error)
        coVerifyOrder {
            localMCPServerService.createSignedServer(9L, request, signedRequest)
            localMCPServerWorkerSyncService.syncCreated(SignedLocalMCPServerDto(createdServer, signedRequest))
            localMCPServerService.deleteServer(9L, createdServer.id)
        }
    }

    /**
     * Verifies failed create compensation surfaces a dedicated compensation error.
     */
    @Test
    fun `create signed server returns compensation failed when delete rollback fails`() = runTest {
        val request = createRequestFixture(workerId = 41L)
        val signedRequest = signedRequestFixture("create-compensation-failure")
        val createdServer = serverFixture(serverId = 103L, workerId = 41L)
        val syncError = syncErrorFixture(workerId = 41L)
        val deleteError = LocalMCPServerNotFoundError(createdServer.id)
        coEvery { localMCPServerService.createSignedServer(9L, request, signedRequest) } returns createdServer.right()
        coEvery {
            localMCPServerWorkerSyncService.syncCreated(SignedLocalMCPServerDto(createdServer, signedRequest))
        } returns syncError.left()
        coEvery { localMCPServerService.deleteServer(9L, createdServer.id) } returns deleteError.left()

        val result = service.createSignedServer(userId = 9L, request = request, signedRequest = signedRequest)

        val error = leftValue(result)
        assertIs<LocalMCPServerConfigSyncError.CompensationFailed>(error)
        assertEquals("delete-created-server", error.operation)
        assertEquals(syncError, error.syncError)
        assertEquals(deleteError, error.compensationError)
    }

    /**
     * Verifies same-worker update sync failures restore both persisted state and worker cache state.
     */
    @Test
    fun `update signed server restores previous signed state after same worker sync failure`() = runTest {
        val previousSignedRequest = signedRequestFixture("previous-41")
        val updateSignedRequest = signedRequestFixture("updated-41")
        val previousServer = serverFixture(serverId = 104L, workerId = 41L)
        val previousSignerSnapshot = signerSnapshotFixture(previousServer, updateSignedRequest.signerId, previousSignedRequest)
        val previousSignedServer = SignedLocalMCPServerDto(previousServer, previousSignedRequest)
        val updatedServer = previousServer.copy(name = "filesystem-updated", command = "node")
        val updateRequest = updatedServer.toUpdateRequest()
        val syncError = syncErrorFixture(workerId = 41L)
        coEvery {
            localMCPServerService.getServerSignerSnapshot(9L, 104L, updateSignedRequest.signerId)
        } returns previousSignerSnapshot.right()
        coEvery { localMCPServerService.updateSignedServer(9L, 104L, updateRequest, updateSignedRequest) } returns updatedServer.right()
        coEvery {
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 41L)
        } returns syncError.left()
        coEvery {
            localMCPServerService.restoreServerSnapshot(9L, 104L, previousSignerSnapshot)
        } returns previousSignerSnapshot.right()
        coEvery { localMCPServerWorkerSyncService.syncUpdated(previousSignedServer, 41L) } returns Unit.right()

        val result = service.updateSignedServer(9L, 104L, updateRequest, updateSignedRequest)

        val error = leftValue(result)
        assertIs<LocalMCPServerConfigSyncError.WorkerSyncFailed>(error)
        assertEquals(syncError, error.error)
        coVerifyOrder {
            localMCPServerService.getServerSignerSnapshot(9L, 104L, updateSignedRequest.signerId)
            localMCPServerService.updateSignedServer(9L, 104L, updateRequest, updateSignedRequest)
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 41L)
            localMCPServerService.restoreServerSnapshot(9L, 104L, previousSignerSnapshot)
            localMCPServerWorkerSyncService.syncUpdated(previousSignedServer, 41L)
        }
        coVerify(exactly = 1) { localMCPServerService.updateSignedServer(9L, 104L, updateRequest, updateSignedRequest) }
        coVerify(exactly = 1) {
            localMCPServerService.restoreServerSnapshot(9L, 104L, previousSignerSnapshot)
        }
        coVerify(exactly = 0) { localMCPServerService.getSignedServersByWorkerId(any()) }
    }

    /**
     * Verifies reassignment update sync failures restore persisted state and recreate the original worker cache.
     */
    @Test
    fun `update signed server restores previous worker assignment after reassignment sync failure`() = runTest {
        val previousSignedRequest = signedRequestFixture("previous-51")
        val updateSignedRequest = signedRequestFixture("updated-52")
        val previousServer = serverFixture(serverId = 105L, workerId = 51L)
        val previousSignerSnapshot = signerSnapshotFixture(previousServer, updateSignedRequest.signerId, previousSignedRequest)
        val previousSignedServer = SignedLocalMCPServerDto(previousServer, previousSignedRequest)
        val updatedServer = previousServer.copy(workerId = 52L, name = "filesystem-reassigned")
        val updateRequest = updatedServer.toUpdateRequest()
        val syncError = syncErrorFixture(workerId = 52L)
        coEvery {
            localMCPServerService.getServerSignerSnapshot(9L, 105L, updateSignedRequest.signerId)
        } returns previousSignerSnapshot.right()
        coEvery { localMCPServerService.updateSignedServer(9L, 105L, updateRequest, updateSignedRequest) } returns updatedServer.right()
        coEvery {
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 51L)
        } returns syncError.left()
        coEvery {
            localMCPServerService.restoreServerSnapshot(9L, 105L, previousSignerSnapshot)
        } returns previousSignerSnapshot.right()
        coEvery { localMCPServerWorkerSyncService.syncDeleted(52L, 105L) } returns Unit.right()
        coEvery { localMCPServerWorkerSyncService.syncCreated(previousSignedServer) } returns Unit.right()

        val result = service.updateSignedServer(9L, 105L, updateRequest, updateSignedRequest)

        val error = leftValue(result)
        assertIs<LocalMCPServerConfigSyncError.WorkerSyncFailed>(error)
        assertEquals(syncError, error.error)
        coVerifyOrder {
            localMCPServerService.getServerSignerSnapshot(9L, 105L, updateSignedRequest.signerId)
            localMCPServerService.updateSignedServer(9L, 105L, updateRequest, updateSignedRequest)
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 51L)
            localMCPServerService.restoreServerSnapshot(9L, 105L, previousSignerSnapshot)
            localMCPServerWorkerSyncService.syncDeleted(52L, 105L)
            localMCPServerWorkerSyncService.syncCreated(previousSignedServer)
        }
        coVerify(exactly = 1) { localMCPServerService.updateSignedServer(9L, 105L, updateRequest, updateSignedRequest) }
        coVerify(exactly = 1) {
            localMCPServerService.restoreServerSnapshot(9L, 105L, previousSignerSnapshot)
        }
        coVerify(exactly = 0) { localMCPServerService.getSignedServersByWorkerId(any()) }
    }

    /**
     * Verifies unsigned snapshot compensation restores persistence but skips best-effort worker restoration.
     */
    @Test
    fun `update signed server skips worker restore when restored snapshot is unsigned`() = runTest {
        val updateSignedRequest = signedRequestFixture("updated-61")
        val previousServer = serverFixture(serverId = 107L, workerId = 61L)
        val previousUnsignedSnapshot = signerSnapshotFixture(previousServer, updateSignedRequest.signerId, null)
        val updatedServer = previousServer.copy(name = "filesystem-updated", command = "node")
        val updateRequest = updatedServer.toUpdateRequest()
        val syncError = syncErrorFixture(workerId = 61L)
        coEvery {
            localMCPServerService.getServerSignerSnapshot(9L, 107L, updateSignedRequest.signerId)
        } returns previousUnsignedSnapshot.right()
        coEvery { localMCPServerService.updateSignedServer(9L, 107L, updateRequest, updateSignedRequest) } returns updatedServer.right()
        coEvery {
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 61L)
        } returns syncError.left()
        coEvery {
            localMCPServerService.restoreServerSnapshot(9L, 107L, previousUnsignedSnapshot)
        } returns previousUnsignedSnapshot.right()

        val result = service.updateSignedServer(9L, 107L, updateRequest, updateSignedRequest)

        val error = leftValue(result)
        assertIs<LocalMCPServerConfigSyncError.WorkerSyncFailed>(error)
        assertEquals(syncError, error.error)
        coVerifyOrder {
            localMCPServerService.getServerSignerSnapshot(9L, 107L, updateSignedRequest.signerId)
            localMCPServerService.updateSignedServer(9L, 107L, updateRequest, updateSignedRequest)
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 61L)
            localMCPServerService.restoreServerSnapshot(9L, 107L, previousUnsignedSnapshot)
        }
        coVerify(exactly = 1) {
            localMCPServerWorkerSyncService.syncUpdated(SignedLocalMCPServerDto(updatedServer, updateSignedRequest), 61L)
        }
        coVerify(exactly = 1) {
            localMCPServerService.restoreServerSnapshot(9L, 107L, previousUnsignedSnapshot)
        }
        coVerify(exactly = 0) { localMCPServerService.getSignedServersByWorkerId(any()) }
        coVerify(exactly = 0) { localMCPServerWorkerSyncService.syncCreated(any()) }
        coVerify(exactly = 0) { localMCPServerWorkerSyncService.syncDeleted(any(), any()) }
    }

    /**
     * Verifies delete orchestration surfaces worker-sync failures after persistence succeeds.
     */
    @Test
    fun `delete server returns worker sync failure when worker cleanup fails`() = runTest {
        val existingServer = serverFixture(serverId = 106L, workerId = 61L)
        val syncError = syncErrorFixture(workerId = 61L)
        coEvery { localMCPServerService.getServerById(9L, 106L) } returns existingServer.right()
        coEvery { localMCPServerService.deleteServer(9L, 106L) } returns Unit.right()
        coEvery { localMCPServerWorkerSyncService.syncDeleted(61L, 106L) } returns syncError.left()

        val result = service.deleteServer(userId = 9L, serverId = 106L)

        val error = leftValue(result)
        assertIs<LocalMCPServerConfigSyncError.WorkerSyncFailed>(error)
        assertEquals(syncError, error.error)
    }

    /**
     * Extracts the successful value or fails the test with a descriptive message.
     *
     * @param either Result under inspection.
     * @return Right value from the provided [Either].
     */
    private fun <L, R> rightValue(either: Either<L, R>): R = when (either) {
        is Either.Right -> either.value
        is Either.Left -> fail("Expected Right but was Left(${'$'}{either.value})")
    }

    /**
     * Extracts the error value or fails the test with a descriptive message.
     *
     * @param either Result under inspection.
     * @return Left value from the provided [Either].
     */
    private fun <L, R> leftValue(either: Either<L, R>): L = when (either) {
        is Either.Left -> either.value
        is Either.Right -> fail("Expected Left but was Right(${'$'}{either.value})")
    }

    /**
     * Builds a deterministic create request fixture.
     *
     * @param workerId Assigned worker identifier.
     * @return Create request fixture.
     */
    private fun createRequestFixture(workerId: Long): CreateLocalMCPServerRequest = CreateLocalMCPServerRequest(
        workerId = workerId,
        name = "filesystem",
        command = "npx",
        environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug"))
    )

    /**
     * Builds a deterministic Local MCP server fixture.
     *
     * @param serverId Local MCP server identifier.
     * @param workerId Assigned worker identifier.
     * @return Local MCP server fixture.
     */
    private fun serverFixture(serverId: Long, workerId: Long): LocalMCPServerDto = LocalMCPServerDto(
        id = serverId,
        userId = 9L,
        workerId = workerId,
        name = "filesystem",
        command = "npx",
        environmentVariables = listOf(LocalMCPEnvironmentVariableDto("LOG_LEVEL", "debug")),
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )

    /**
     * Builds deterministic detached signing metadata.
     *
     * @param nonce Stable nonce suffix used to distinguish fixtures.
     * @return Detached signed request fixture.
     */
    private fun signedRequestFixture(nonce: String): SignedRequest = SignedRequest(
        payload = "{\"nonce\":\"$nonce\"}",
        signature = "signature-$nonce",
        signerId = "device-1",
        timestamp = 1_700_000_000_000,
        nonce = nonce
    )

    /**
     * Builds a deterministic signer-scoped compensation snapshot fixture.
     *
     * @param server Previously persisted Local MCP server.
     * @param signerId Client-side signer identifier represented by the snapshot.
     * @param signedRequest Previously persisted detached signature for that signer, if any.
     * @return Signer-scoped snapshot fixture.
     */
    private fun signerSnapshotFixture(
        server: LocalMCPServerDto,
        signerId: String,
        signedRequest: SignedRequest?
    ): LocalMCPServerSignerSnapshot = LocalMCPServerSignerSnapshot(
        server = server,
        signerId = signerId,
        signedRequest = signedRequest
    )

    /**
     * Builds a deterministic worker sync failure fixture.
     *
     * @param workerId Worker identifier associated with the failure.
     * @return Worker sync error fixture.
     */
    private fun syncErrorFixture(workerId: Long): LocalMCPRuntimeCommandDispatchError =
        LocalMCPRuntimeCommandDispatchError.DispatchFailed(
            WorkerCommandDispatchError.WorkerNotConnected(workerId = workerId)
        )

    /**
     * Converts a server DTO to the equivalent full update request used for update invocation fixtures.
     *
     * @receiver Server configuration to convert.
     * @return Matching update request.
     */
    private fun LocalMCPServerDto.toUpdateRequest(): UpdateLocalMCPServerRequest = UpdateLocalMCPServerRequest(
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
}