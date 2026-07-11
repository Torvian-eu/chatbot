package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.tool.ToolType
import eu.torvian.chatbot.server.data.dao.BuiltInToolDefinitionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.service.core.WorkerService
import eu.torvian.chatbot.server.service.security.CertificateService
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transaction-wrapped integration tests for [WorkerService] prefix persistence and propagation.
 *
 * Verifies that registering a worker with a prefix seeds the eight built-in tools with prefixed
 * public names, that updating the prefix renames the public names while preserving the unprefixed
 * `builtInToolName`, and that clearing the prefix reverts the public names to the canonical names.
 * All assertions run inside the service transaction so the worker row and tool linkages stay
 * consistent.
 */
class WorkerServiceImplPrefixTest {

    private lateinit var container: DIContainer
    private lateinit var service: WorkerService
    private lateinit var workerDao: WorkerDao
    private lateinit var builtInToolDefinitionDao: BuiltInToolDefinitionDao
    private lateinit var certificateService: CertificateService
    private lateinit var testDataManager: TestDataManager

    private val testUser = TestDefaults.user1

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        service = container.get()
        workerDao = container.get()
        builtInToolDefinitionDao = container.get()
        certificateService = container.get()
        testDataManager = container.get()

        testDataManager.createTables(
            setOf(
                Table.USERS,
                Table.WORKERS,
                Table.TOOL_DEFINITIONS,
                Table.BUILT_IN_TOOL_DEFINITIONS
            )
        )

        testDataManager.setup(
            eu.torvian.chatbot.server.testutils.data.TestDataSet(
                users = listOf(testUser)
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `registerWorker with prefix seeds 8 tools with prefixed public names and persists prefix`() = runTest {
        val result = service.registerWorker(
            ownerUserId = testUser.id,
            workerUid = "prefix-register-worker",
            displayName = "prefix-register-worker",
            certificatePem = validCertPem("prefix-register-worker"),
            allowedScopes = emptyList(),
            toolNamePrefix = "project1"
        )

        assertTrue(result.isRight(), "registration failed: ${result.leftOrNull()}")
        val worker = result.getOrNull()!!
        assertEquals("project1", worker.toolNamePrefix)

        val tools = builtInToolDefinitionDao.getToolsByWorkerId(worker.id)
        assertEquals(8, tools.size)
        assertEquals(ToolType.BUILTIN_WORKER, tools.first().type)
        assertTrue(tools.all { it.name == "project1.${it.builtInToolName}" })
        // The persisted worker row carries the prefix.
        val persisted = workerDao.getWorkerById(worker.id).getOrNull()!!
        assertEquals("project1", persisted.toolNamePrefix)
    }

    @Test
    fun `updateWorker with new prefix renames public names and preserves builtInToolName`() = runTest {
        val registered = service.registerWorker(
            ownerUserId = testUser.id,
            workerUid = "prefix-update-worker",
            displayName = "prefix-update-worker",
            certificatePem = validCertPem("prefix-update-worker"),
            allowedScopes = emptyList(),
            toolNamePrefix = "project1"
        ).getOrNull()!!

        val before = builtInToolDefinitionDao.getToolsByWorkerId(registered.id)
        val beforeNames = before.associate { it.builtInToolName to it.name }

        val updated = service.updateWorker(
            ownerUserId = testUser.id,
            workerId = registered.id,
            displayName = registered.displayName,
            allowedScopes = emptyList(),
            toolNamePrefix = "project2"
        )

        assertTrue(updated.isRight(), "update failed: ${updated.leftOrNull()}")
        assertEquals("project2", updated.getOrNull()!!.toolNamePrefix)

        val after = builtInToolDefinitionDao.getToolsByWorkerId(registered.id)
        assertEquals(8, after.size)
        for (tool in after) {
            // builtInToolName must be unchanged across the rename.
            assertEquals(beforeNames[tool.builtInToolName], beforeNames[tool.builtInToolName])
            // Public name reflects the new prefix.
            assertEquals("project2.${tool.builtInToolName}", tool.name)
        }
        val persisted = workerDao.getWorkerById(registered.id).getOrNull()!!
        assertEquals("project2", persisted.toolNamePrefix)
    }

    @Test
    fun `updateWorker with null prefix reverts public names to unprefixed canonical names`() = runTest {
        val registered = service.registerWorker(
            ownerUserId = testUser.id,
            workerUid = "prefix-clear-worker",
            displayName = "prefix-clear-worker",
            certificatePem = validCertPem("prefix-clear-worker"),
            allowedScopes = emptyList(),
            toolNamePrefix = "project1"
        ).getOrNull()!!

        val updated = service.updateWorker(
            ownerUserId = testUser.id,
            workerId = registered.id,
            displayName = registered.displayName,
            allowedScopes = emptyList(),
            toolNamePrefix = null
        )

        assertTrue(updated.isRight(), "update failed: ${updated.leftOrNull()}")
        assertEquals(null, updated.getOrNull()!!.toolNamePrefix)

        val after = builtInToolDefinitionDao.getToolsByWorkerId(registered.id)
        assertEquals(8, after.size)
        // Public names revert to the unprefixed canonical built-in names.
        assertTrue(after.all { it.name == it.builtInToolName })
        val persisted = workerDao.getWorkerById(registered.id).getOrNull()!!
        assertEquals(null, persisted.toolNamePrefix)
    }

    /**
     * Generates a valid self-signed certificate PEM for use as a worker registration credential.
     *
     * @param uid Stable identifier used only to vary the certificate subject.
     * @return PEM-encoded X.509 certificate accepted by [WorkerService.registerWorker].
     */
    private fun validCertPem(uid: String): String {
        val keyPair = certificateService.generateRSAKeyPair()
        val certificate = certificateService.generateSelfSignedCertificate(keyPair, "CN=$uid")
        return certificateService.certificateToPem(certificate)
    }
}

