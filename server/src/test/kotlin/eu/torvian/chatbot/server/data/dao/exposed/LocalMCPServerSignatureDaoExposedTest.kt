package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.LocalMCPServerDao
import eu.torvian.chatbot.server.data.dao.LocalMCPServerSignatureDao
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.entities.CreateLocalMCPServerEntity
import eu.torvian.chatbot.server.data.entities.LocalMCPServerSignatureEntity
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Verifies that Local MCP signature persistence supports signer-scoped deletion by `(serverId, userDeviceId)`.
 */
class LocalMCPServerSignatureDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var localMCPServerDao: LocalMCPServerDao
    private lateinit var localMCPServerSignatureDao: LocalMCPServerSignatureDao
    private lateinit var userDeviceDao: UserDeviceDao

    /** Shared persisted user fixture required by the server and device tables. */
    private val testUser = TestDefaults.user1

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        localMCPServerDao = container.get()
        localMCPServerSignatureDao = container.get()
        userDeviceDao = container.get()

        testDataManager.createTables(
            setOf(Table.USERS, Table.USER_DEVICES, Table.LOCAL_MCP_SERVERS, Table.LOCAL_MCP_SERVER_SIGNATURES)
        )
        testDataManager.setup(TestDataSet(users = listOf(testUser)))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `deleteSignature removes only the requested signer row`() = runTest {
        val server = localMCPServerDao.createServer(createServerEntity(testUser.id))
        val deviceA = userDeviceDao.insertDevice(testUser.id, "device-a", "Laptop")
        val deviceB = userDeviceDao.insertDevice(testUser.id, "device-b", "Tablet")
        localMCPServerSignatureDao.upsertSignature(signatureEntity(server.id, deviceA.id, "signature-a"))
        localMCPServerSignatureDao.upsertSignature(signatureEntity(server.id, deviceB.id, "signature-b"))

        localMCPServerSignatureDao.deleteSignature(server.id, deviceA.id)

        assertNull(localMCPServerSignatureDao.getSignature(server.id, deviceA.id))
        val remainingSignature = localMCPServerSignatureDao.getSignature(server.id, deviceB.id)
        assertNotNull(remainingSignature)
        assertEquals("signature-b", remainingSignature.signature)
        assertEquals(1, localMCPServerSignatureDao.getSignaturesByServerId(server.id).size)
    }

    /**
     * Creates a minimal Local MCP server payload suitable for signature DAO tests.
     *
     * @param userId Owning user identifier.
     * @return Create payload with stable defaults.
     */
    private fun createServerEntity(userId: Long): CreateLocalMCPServerEntity = CreateLocalMCPServerEntity(
        userId = userId,
        workerId = 1L,
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
        secretEnvironmentVariables = emptyList()
    )

    /**
     * Builds a deterministic Local MCP signature row fixture.
     *
     * @param serverId Local MCP server identifier.
     * @param userDeviceId Signer device identifier.
     * @param signature Persisted signature payload marker.
     * @return Signature entity fixture.
     */
    private fun signatureEntity(serverId: Long, userDeviceId: Long, signature: String): LocalMCPServerSignatureEntity =
        LocalMCPServerSignatureEntity(
            serverId = serverId,
            userDeviceId = userDeviceId,
            signature = signature,
            timestamp = 1_700_000_000_000,
            nonce = "nonce-$userDeviceId",
            payloadJson = "{\"serverId\":$serverId}",
            createdAt = TestDefaults.DEFAULT_INSTANT,
            updatedAt = TestDefaults.DEFAULT_INSTANT
        )
}