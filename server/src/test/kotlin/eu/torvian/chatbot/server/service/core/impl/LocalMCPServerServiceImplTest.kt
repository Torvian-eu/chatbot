package eu.torvian.chatbot.server.service.core.impl

import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.SignedLocalMCPServerDto
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPServerSignatureDao
import eu.torvian.chatbot.server.data.dao.LocalMCPToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPSecretEnvironmentVariableReference
import eu.torvian.chatbot.server.data.entities.LocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPServerSignatureEntity
import eu.torvian.chatbot.server.data.entities.UpdateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.UserDeviceEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.service.core.LocalMCPServerSignerSnapshot
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerUnknownSignerDeviceError
import eu.torvian.chatbot.server.service.core.error.mcp.LocalMCPServerWorkerOwnershipMismatchError
import eu.torvian.chatbot.server.service.security.CredentialManager
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [LocalMCPServerServiceImpl].
 */
class LocalMCPServerServiceImplTest {
    private val localMCPServerDao = mockk<LocalMCPServerDao>()

    /** DAO mock used to verify signed snapshot persistence. */
    private val localMCPServerSignatureDao = mockk<LocalMCPServerSignatureDao>()

    private val localMCPToolDefinitionDao = mockk<LocalMCPToolDefinitionDao>()

    /** DAO mock used to resolve envelope signer devices. */
    private val userDeviceDao = mockk<UserDeviceDao>()

    private val workerDao = mockk<WorkerDao>()
    private val credentialManager = mockk<CredentialManager>()
    private val transactionScope = mockk<TransactionScope>()

    /** JSON codec configured like the route tests for deterministic payload serialization. */
    private val json = Json { encodeDefaults = true }

    private val service = LocalMCPServerServiceImpl(
        localMCPServerDao = localMCPServerDao,
        localMCPServerSignatureDao = localMCPServerSignatureDao,
        localMCPToolDefinitionDao = localMCPToolDefinitionDao,
        userDeviceDao = userDeviceDao,
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
        clearMocks(
            localMCPServerDao,
            localMCPServerSignatureDao,
            localMCPToolDefinitionDao,
            userDeviceDao,
            workerDao,
            credentialManager,
            transactionScope
        )
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `createSignedServer stores normalized config and signature snapshot`() = runTest {
        val createPayloadSlot: CapturingSlot<CreateLocalMCPServerEntity> = slot()
        val signatureSlot: CapturingSlot<LocalMCPServerSignatureEntity> = slot()
        val request = CreateLocalMCPServerRequest(
            workerId = workerEntity.id,
            name = "signed-filesystem",
            command = "npx",
            arguments = listOf("-y", "@modelcontextprotocol/server-filesystem")
        )
        val signedRequest = signedRequest(
            payload = """
            {
              "workerId": ${workerEntity.id},
              "name": "signed-filesystem",
              "command": "npx",
              "arguments": ["-y", "@modelcontextprotocol/server-filesystem"]
            }
            """.trimIndent()
        )
        val device = userDeviceEntity(clientDeviceId = signedRequest.signerId)

        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signedRequest.signerId) } returns device
        coEvery { workerDao.getWorkerById(workerEntity.id) } returns workerEntity.right()
        coEvery { localMCPServerDao.createServer(capture(createPayloadSlot)) } answers {
            createPayloadSlot.captured.toEntity(id = 123L)
        }
        coEvery { localMCPServerSignatureDao.upsertSignature(capture(signatureSlot)) } answers {
            signatureSlot.captured
        }

        val result = service.createSignedServer(
            userId = workerEntity.ownerUserId,
            request = request,
            signedRequest = signedRequest
        )

        assertTrue(result.isRight())
        assertEquals("signed-filesystem", createPayloadSlot.captured.name)
        assertEquals(device.id, signatureSlot.captured.userDeviceId)
        assertEquals(123L, signatureSlot.captured.serverId)
        assertEquals(signedRequest.signature, signatureSlot.captured.signature)
        assertEquals(signedRequest.payload, signatureSlot.captured.payloadJson)
    }

    @Test
    fun `createSignedServer rejects detached signed requests from unknown devices`() = runTest {
        val request = CreateLocalMCPServerRequest(
            workerId = workerEntity.id,
            name = "signed-filesystem",
            command = "npx"
        )
        val signedRequest = signedRequest(payload = json.encodeToString(request))

        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signedRequest.signerId) } returns null

        val result = service.createSignedServer(
            userId = workerEntity.ownerUserId,
            request = request,
            signedRequest = signedRequest
        )

        assertTrue(result.isLeft())
        assertIs<LocalMCPServerUnknownSignerDeviceError>(result.leftOrNull())
        coVerify(exactly = 0) { localMCPServerDao.createServer(any()) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.upsertSignature(any()) }
    }

    @Test
    fun `createSignedServer rejects worker ownership mismatch`() = runTest {
        val ownerEntity = workerEntity.copy(ownerUserId = 999L)
        val request = CreateLocalMCPServerRequest(
            workerId = ownerEntity.id,
            name = "filesystem",
            command = "npx"
        )
        val signedRequest = signedRequest(payload = json.encodeToString(request))

        coEvery { workerDao.getWorkerById(ownerEntity.id) } returns ownerEntity.right()
        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signedRequest.signerId) } returns userDeviceEntity(signedRequest.signerId)

        val result = service.createSignedServer(
            userId = workerEntity.ownerUserId,
            request = request,
            signedRequest = signedRequest
        )

        assertTrue(result.isLeft())
        assertIs<LocalMCPServerWorkerOwnershipMismatchError>(result.leftOrNull())
    }

    @Test
    fun `getServerSignerSnapshot returns signer-scoped detached request for matching signer row`() = runTest {
        val existingEntity = persistedServerEntity(serverId = 122L)
        val signerId = "device-restore"
        val signerDevice = userDeviceEntity(clientDeviceId = signerId).copy(id = 91L)
        val signatureEntity = LocalMCPServerSignatureEntity(
            serverId = 122L,
            userDeviceId = signerDevice.id,
            signature = "signature-restored",
            timestamp = 1_700_000_000_222,
            nonce = "nonce-restore",
            payloadJson = "{\"name\":\"filesystem\"}",
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
        )

        coEvery { localMCPServerDao.getServerByIdForUser(workerEntity.ownerUserId, 122L) } returns existingEntity.right()
        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signerId) } returns signerDevice
        coEvery { localMCPServerSignatureDao.getSignature(122L, signerDevice.id) } returns signatureEntity

        val result = service.getServerSignerSnapshot(workerEntity.ownerUserId, 122L, signerId)

        assertTrue(result.isRight())
        val snapshot = result.getOrNull()!!
        assertEquals(signerId, snapshot.signerId)
        assertEquals(signerId, snapshot.signedRequest?.signerId)
        assertEquals(signatureEntity.payloadJson, snapshot.signedRequest?.payload)
        assertEquals(signatureEntity.signature, snapshot.signedRequest?.signature)
    }

    @Test
    fun `getServerSignerSnapshot preserves signer identity when signer device no longer exists`() = runTest {
        val existingEntity = persistedServerEntity(serverId = 122L)
        val signerId = "device-missing"

        coEvery { localMCPServerDao.getServerByIdForUser(workerEntity.ownerUserId, 122L) } returns existingEntity.right()
        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signerId) } returns null

        val result = service.getServerSignerSnapshot(workerEntity.ownerUserId, 122L, signerId)

        assertTrue(result.isRight())
        val snapshot = result.getOrNull()!!
        assertEquals(signerId, snapshot.signerId)
        assertEquals(null, snapshot.signedRequest)
        coVerify(exactly = 0) { localMCPServerSignatureDao.getSignature(any(), any()) }
    }

    @Test
    fun `restoreServerSnapshot restores config and deletes signer row when previous snapshot was unsigned`() = runTest {
        val updatePayloadSlot: CapturingSlot<UpdateLocalMCPServerEntity> = slot()
        val signerId = "device-failed"
        val failedSignerDevice = userDeviceEntity(clientDeviceId = signerId).copy(id = 91L)
        val existingEntity = persistedServerEntity(
            serverId = 123L,
            secretEnvironmentVariables = listOf(LocalMCPSecretEnvironmentVariableReference("API_KEY", "alias-old"))
        )
        val snapshotServer = serverDtoFixture(
            serverId = 123L,
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_KEY", "secret-restored"))
        )
        val snapshot = signerSnapshotFixture(server = snapshotServer, signerId = signerId, signedRequest = null)

        coEvery { credentialManager.storeCredential("secret-restored") } returns "alias-restored"
        coEvery { workerDao.getWorkerById(workerEntity.id) } returns workerEntity.right()
        coEvery { localMCPServerDao.getServerByIdForUser(workerEntity.ownerUserId, 123L) } returns existingEntity.right()
        coEvery {
            localMCPServerDao.updateServer(workerEntity.ownerUserId, 123L, capture(updatePayloadSlot))
        } answers {
            existingEntity.withUpdate(updatePayloadSlot.captured).right()
        }
        coEvery { credentialManager.deleteCredential("alias-old") } returns Unit.right()
        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signerId) } returns failedSignerDevice
        coEvery { localMCPServerSignatureDao.deleteSignature(123L, failedSignerDevice.id) } returns Unit

        val result = service.restoreServerSnapshot(
            userId = workerEntity.ownerUserId,
            serverId = 123L,
            snapshot = snapshot
        )

        assertTrue(result.isRight())
        val restored = result.getOrNull()!!
        assertEquals("alias-restored", updatePayloadSlot.captured.secretEnvironmentVariables.single().alias)
        assertEquals("secret-restored", restored.server.secretEnvironmentVariables.single().value)
        assertEquals(signerId, restored.signerId)
        assertEquals(null, restored.signedRequest)
        coVerify(exactly = 1) { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, signerId) }
        coVerify(exactly = 1) { localMCPServerSignatureDao.deleteSignature(123L, failedSignerDevice.id) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.deleteSignaturesByServerId(any()) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.upsertSignature(any()) }
    }

    @Test
    fun `restoreServerSnapshot restores prior detached signature when signer device still exists`() = runTest {
        val updatePayloadSlot: CapturingSlot<UpdateLocalMCPServerEntity> = slot()
        val signatureSlot: CapturingSlot<LocalMCPServerSignatureEntity> = slot()
        val existingEntity = persistedServerEntity(serverId = 124L)
        val snapshotServer = serverDtoFixture(serverId = 124L)
        val snapshotSignedRequest = signedRequest(
            payload = "{\"name\":\"restored-signed\"}",
            signerId = "device-restore"
        )
        val signerDevice = userDeviceEntity(clientDeviceId = snapshotSignedRequest.signerId)
        val snapshot = signerSnapshotFixture(
            server = snapshotServer,
            signerId = snapshotSignedRequest.signerId,
            signedRequest = snapshotSignedRequest
        )

        coEvery { workerDao.getWorkerById(workerEntity.id) } returns workerEntity.right()
        coEvery { localMCPServerDao.getServerByIdForUser(workerEntity.ownerUserId, 124L) } returns existingEntity.right()
        coEvery {
            localMCPServerDao.updateServer(workerEntity.ownerUserId, 124L, capture(updatePayloadSlot))
        } answers {
            existingEntity.withUpdate(updatePayloadSlot.captured).right()
        }
        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, snapshotSignedRequest.signerId) } returns signerDevice
        coEvery { localMCPServerSignatureDao.upsertSignature(capture(signatureSlot)) } answers { signatureSlot.captured }

        val result = service.restoreServerSnapshot(
            userId = workerEntity.ownerUserId,
            serverId = 124L,
            snapshot = snapshot
        )

        assertTrue(result.isRight())
        val restored = result.getOrNull()!!
        assertEquals(snapshotSignedRequest.signerId, restored.signerId)
        assertEquals(snapshotSignedRequest, restored.signedRequest)
        assertEquals(signerDevice.id, signatureSlot.captured.userDeviceId)
        assertEquals(snapshotSignedRequest.payload, signatureSlot.captured.payloadJson)
        assertEquals(snapshotSignedRequest.signature, signatureSlot.captured.signature)
        assertEquals(snapshotSignedRequest.timestamp, signatureSlot.captured.timestamp)
        assertEquals(snapshotSignedRequest.nonce, signatureSlot.captured.nonce)
        coVerify(exactly = 1) { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, snapshotSignedRequest.signerId) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.deleteSignature(any(), any()) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.deleteSignaturesByServerId(any()) }
    }

    @Test
    fun `restoreServerSnapshot returns unsigned result without deleting unrelated rows when previous signer device no longer exists`() = runTest {
        val updatePayloadSlot: CapturingSlot<UpdateLocalMCPServerEntity> = slot()
        val existingEntity = persistedServerEntity(serverId = 125L)
        val snapshotSignedRequest = signedRequest(
            payload = "{\"name\":\"restored-without-device\"}",
            signerId = "device-missing"
        )
        val snapshot = signerSnapshotFixture(
            server = serverDtoFixture(serverId = 125L),
            signerId = snapshotSignedRequest.signerId,
            signedRequest = snapshotSignedRequest
        )

        coEvery { workerDao.getWorkerById(workerEntity.id) } returns workerEntity.right()
        coEvery { localMCPServerDao.getServerByIdForUser(workerEntity.ownerUserId, 125L) } returns existingEntity.right()
        coEvery {
            localMCPServerDao.updateServer(workerEntity.ownerUserId, 125L, capture(updatePayloadSlot))
        } answers {
            existingEntity.withUpdate(updatePayloadSlot.captured).right()
        }
        coEvery { userDeviceDao.getDeviceByClientId(workerEntity.ownerUserId, snapshotSignedRequest.signerId) } returns null

        val result = service.restoreServerSnapshot(
            userId = workerEntity.ownerUserId,
            serverId = 125L,
            snapshot = snapshot
        )

        assertTrue(result.isRight())
        val restored = result.getOrNull()!!
        assertEquals(snapshotSignedRequest.signerId, restored.signerId)
        assertEquals(null, restored.signedRequest)
        coVerify(exactly = 0) { localMCPServerSignatureDao.deleteSignature(any(), any()) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.deleteSignaturesByServerId(any()) }
        coVerify(exactly = 0) { localMCPServerSignatureDao.upsertSignature(any()) }
    }

    @Test
    fun `getSignedServersByWorkerId includes latest detached signed snapshot`() = runTest {
        val entity = LocalMCPServerEntity(
            id = 55L,
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
            secretEnvironmentVariables = emptyList(),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
        )
        val latestSignature = LocalMCPServerSignatureEntity(
            serverId = entity.id,
            userDeviceId = 77L,
            signature = "signature-latest",
            timestamp = 1_700_000_000_321,
            nonce = "nonce-latest",
            payloadJson = "{\"name\":\"filesystem\"}",
            createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_200_000)
        )
        val olderSignature = latestSignature.copy(
            signature = "signature-older",
            nonce = "nonce-older",
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
        )
        val device = userDeviceEntity(clientDeviceId = "device-001")

        coEvery { localMCPServerDao.getServersByWorkerId(workerEntity.id) } returns listOf(entity)
        coEvery { localMCPServerSignatureDao.getSignaturesByServerId(entity.id) } returns listOf(olderSignature, latestSignature)
        coEvery { userDeviceDao.getDeviceById(device.id) } returns device

        val result = service.getSignedServersByWorkerId(workerEntity.id)

        assertTrue(result.isRight())
        assertEquals(
            listOf(
                SignedLocalMCPServerDto(
                    server = LocalMCPServerDto(
                        id = entity.id,
                        userId = entity.userId,
                        workerId = entity.workerId,
                        name = entity.name,
                        description = entity.description,
                        command = entity.command,
                        arguments = entity.arguments,
                        workingDirectory = entity.workingDirectory,
                        isEnabled = entity.isEnabled,
                        autoStartOnEnable = entity.autoStartOnEnable,
                        autoStartOnLaunch = entity.autoStartOnLaunch,
                        autoStopAfterInactivitySeconds = entity.autoStopAfterInactivitySeconds,
                        toolNamePrefix = entity.toolNamePrefix,
                        environmentVariables = entity.environmentVariables,
                        secretEnvironmentVariables = emptyList<LocalMCPEnvironmentVariableDto>(),
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt
                    ),
                    signedRequest = SignedRequest(
                        payload = latestSignature.payloadJson,
                        signature = latestSignature.signature,
                        signerId = device.clientDeviceId,
                        timestamp = latestSignature.timestamp,
                        nonce = latestSignature.nonce
                    )
                )
            ),
            result.getOrNull()
        )
    }

    /**
     * Creates deterministic detached signed-request metadata for service tests that do not verify signature bytes.
     *
     * @param payload Exact JSON payload string to store and decode.
     * @return Detached signed request with stable metadata.
     */
    private fun signedRequest(payload: String, signerId: String = "device-001"): SignedRequest = SignedRequest(
        payload = payload,
        signature = "signature-base64",
        signerId = signerId,
        timestamp = 1_700_000_000_123,
        nonce = "nonce-001"
    )

    /**
     * Creates a persisted user-device row for signed request tests.
     *
     * @param clientDeviceId Stable client-side identifier stored in the detached signer field.
     * @return User-device entity linked to the default worker owner.
     */
    private fun userDeviceEntity(clientDeviceId: String): UserDeviceEntity = UserDeviceEntity(
        id = 77L,
        userId = workerEntity.ownerUserId,
        clientDeviceId = clientDeviceId,
        deviceName = "Laptop",
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        lastUsedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    )

    /**
     * Converts a captured create entity into a persisted server entity for DAO stubbing.
     *
     * @receiver Captured create payload.
     * @param id Server identifier to expose on the persisted entity.
     * @return Persisted server entity mirroring the create payload.
     */
    private fun CreateLocalMCPServerEntity.toEntity(id: Long): LocalMCPServerEntity = LocalMCPServerEntity(
        id = id,
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
        secretEnvironmentVariables = secretEnvironmentVariables,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    )

    /**
     * Creates a persisted Local MCP entity fixture for update and restore tests.
     *
     * @param serverId Server identifier.
     * @param workerId Assigned worker identifier.
     * @param secretEnvironmentVariables Persisted secret alias references.
     * @return Persisted server fixture.
     */
    private fun persistedServerEntity(
        serverId: Long,
        workerId: Long = workerEntity.id,
        secretEnvironmentVariables: List<LocalMCPSecretEnvironmentVariableReference> = emptyList()
    ): LocalMCPServerEntity = LocalMCPServerEntity(
        id = serverId,
        userId = workerEntity.ownerUserId,
        workerId = workerId,
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
        secretEnvironmentVariables = secretEnvironmentVariables,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
    )

    /**
     * Creates a Local MCP DTO fixture mirroring persisted server values with plaintext secrets.
     *
     * @param serverId Server identifier.
     * @param workerId Assigned worker identifier.
     * @param secretEnvironmentVariables Plaintext secret environment values.
     * @return Local MCP DTO fixture.
     */
    private fun serverDtoFixture(
        serverId: Long,
        workerId: Long = workerEntity.id,
        secretEnvironmentVariables: List<LocalMCPEnvironmentVariableDto> = emptyList()
    ): LocalMCPServerDto = LocalMCPServerDto(
        id = serverId,
        userId = workerEntity.ownerUserId,
        workerId = workerId,
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
        secretEnvironmentVariables = secretEnvironmentVariables,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )

    /**
     * Creates a signer-scoped Local MCP compensation snapshot fixture.
     *
     * @param server Previously persisted Local MCP server DTO.
     * @param signerId Client-side signer identifier represented by the snapshot.
     * @param signedRequest Previously persisted detached request for that signer, if any.
     * @return Signer-scoped compensation snapshot fixture.
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
     * Applies a normalized update payload to a persisted Local MCP entity for DAO stubbing.
     *
     * @receiver Existing persisted entity.
     * @param update Normalized update payload captured from the service.
     * @return Updated persisted entity reflecting the applied payload.
     */
    private fun LocalMCPServerEntity.withUpdate(update: UpdateLocalMCPServerEntity): LocalMCPServerEntity = copy(
        workerId = update.workerId,
        name = update.name,
        description = update.description,
        command = update.command,
        arguments = update.arguments,
        workingDirectory = update.workingDirectory,
        isEnabled = update.isEnabled,
        autoStartOnEnable = update.autoStartOnEnable,
        autoStartOnLaunch = update.autoStartOnLaunch,
        autoStopAfterInactivitySeconds = update.autoStopAfterInactivitySeconds,
        toolNamePrefix = update.toolNamePrefix,
        environmentVariables = update.environmentVariables,
        secretEnvironmentVariables = update.secretEnvironmentVariables,
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_100_000)
    )
}

