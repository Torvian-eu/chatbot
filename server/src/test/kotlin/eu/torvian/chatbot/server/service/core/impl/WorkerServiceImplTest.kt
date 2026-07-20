package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.service.core.error.worker.AuthenticateWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.UpdateWorkerError
import eu.torvian.chatbot.server.service.security.CertificateService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkerServiceImplTest {
    private val workerDao = mockk<WorkerDao>()
    private val certificateService = mockk<CertificateService>()
    private val builtInToolDefinitionSeeder = mockk<BuiltInToolDefinitionSeeder>()
    private val transactionScope = mockk<TransactionScope>()

    private val service = WorkerServiceImpl(
        workerDao = workerDao,
        certificateService = certificateService,
        builtInToolDefinitionSeeder = builtInToolDefinitionSeeder,
        transactionScope = transactionScope
    )

    private val testWorker = WorkerEntity(
        id = 10L,
        workerUid = "worker-10",
        ownerUserId = 1L,
        displayName = "test-worker",
        certificatePem = "pem",
        certificateFingerprint = "abc",
        allowedScopes = emptyList(),
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        lastSeenAt = null,
        toolNamePrefix = null
    )

    @BeforeEach
    fun setUp() {
        clearMocks(workerDao, certificateService, transactionScope, builtInToolDefinitionSeeder)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        // Default: seeding succeeds so registration tests focus on worker creation.
        coEvery { builtInToolDefinitionSeeder.seedDefaultToolsForWorker(any(), any()) } returns
            emptyList<BuiltInWorkerToolDefinition>().right()
    }

    @Test
    fun `registerWorker returns InvalidCertificate for malformed pem`() = runTest {
        every { certificateService.parseCertificate("bad-cert") } throws IllegalArgumentException("bad cert")

        val result = service.registerWorker(
            ownerUserId = 1L,
            workerUid = "worker-7",
            displayName = "worker",
            certificatePem = "bad-cert",
            allowedScopes = emptyList()
        )

        assertTrue(result.isLeft())
        assertEquals(RegisterWorkerError.InvalidCertificate("bad cert"), result.leftOrNull())
    }

    @Test
    fun `createServiceTokenChallenge returns WorkerNotFound for mismatched fingerprint`() = runTest {
        coEvery { workerDao.getWorkerByFingerprint("different") } returns null

        val result = service.createServiceTokenChallenge("worker-10", "different")

        assertTrue(result.isLeft())
        assertEquals(AuthenticateWorkerError.WorkerNotFound("worker-10"), result.leftOrNull())
    }

    @Test
    fun `authenticateWorker returns InvalidChallenge when challenge is missing`() = runTest {
        coEvery { workerDao.getWorkerByUid("worker-10") } returns testWorker.right()
        coEvery { workerDao.getChallenge(10L, "missing", any()) } returns
            WorkerError.InvalidChallenge("missing").left()

        val result = service.authenticateWorker("worker-10", "missing", "sig")

        assertTrue(result.isLeft())
        assertEquals(AuthenticateWorkerError.InvalidChallenge("missing"), result.leftOrNull())
        coVerify(exactly = 0) { workerDao.consumeChallenge(any()) }
    }

    @Test
    fun `registerWorker rejects prefix with illegal characters`() = runTest {
        val result = service.registerWorker(
            ownerUserId = 1L,
            workerUid = "worker-7",
            displayName = "worker",
            certificatePem = "pem",
            allowedScopes = emptyList(),
            toolNamePrefix = "bad.prefix"
        )

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is RegisterWorkerError.InvalidInput)
        coVerify(exactly = 0) { workerDao.createWorker(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `registerWorker accepts a legal prefix`() = runTest {
        every { certificateService.parseCertificate("pem") } returns mockk()
        every { certificateService.computeCertificateFingerprint(any()) } returns "fp"
        coEvery {
            workerDao.createWorker(any(), any(), any(), any(), any(), any(), any())
        } returns testWorker.right()

        val result = service.registerWorker(
            ownerUserId = 1L,
            workerUid = "worker-7",
            displayName = "worker",
            certificatePem = "pem",
            allowedScopes = emptyList(),
            toolNamePrefix = "project1_"
        )

        assertTrue(result.isRight())
    }

    @Test
    fun `updateWorker rejects prefix with illegal characters`() = runTest {
        coEvery { workerDao.getWorkerById(10L) } returns testWorker.right()

        val result = service.updateWorker(
            ownerUserId = 1L,
            workerId = 10L,
            displayName = "worker",
            allowedScopes = emptyList(),
            toolNamePrefix = "bad prefix"
        )

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is UpdateWorkerError.InvalidInput)
        coVerify(exactly = 0) { workerDao.updateWorker(any(), any(), any(), any()) }
    }

    @Test
    fun `updateWorker accepts a legal prefix`() = runTest {
        coEvery { workerDao.getWorkerById(10L) } returns testWorker.right()
        coEvery { workerDao.updateWorker(any(), any(), any(), any()) } returns testWorker.right()
        coEvery { builtInToolDefinitionSeeder.renamePublicNamesForPrefix(any(), any()) } returns
            Unit.right()

        val result = service.updateWorker(
            ownerUserId = 1L,
            workerId = 10L,
            displayName = "worker",
            allowedScopes = emptyList(),
            toolNamePrefix = "proj-"
        )

        assertTrue(result.isRight())
    }
}
