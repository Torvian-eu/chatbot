package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.worker.Worker
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.service.core.error.worker.AuthenticateWorkerError
import eu.torvian.chatbot.server.service.core.error.worker.RegisterWorkerError
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
    private val transactionScope = mockk<TransactionScope>()

    private val service = WorkerServiceImpl(
        workerDao = workerDao,
        certificateService = certificateService,
        transactionScope = transactionScope
    )

    private val testWorker = Worker(
        id = 10L,
        ownerUserId = 1L,
        displayName = "test-worker",
        certificatePem = "pem",
        certificateFingerprint = "abc",
        allowedScopes = emptyList(),
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        lastSeenAt = null
    )

    @BeforeEach
    fun setUp() {
        clearMocks(workerDao, certificateService, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `registerWorker returns InvalidCertificate for malformed pem`() = runTest {
        every { certificateService.parseCertificate("bad-cert") } throws IllegalArgumentException("bad cert")

        val result = service.registerWorker(
            ownerUserId = 1L,
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

        val result = service.createServiceTokenChallenge(10L, "different")

        assertTrue(result.isLeft())
        assertEquals(AuthenticateWorkerError.WorkerNotFound(10L), result.leftOrNull())
    }

    @Test
    fun `authenticateWorker returns InvalidChallenge when challenge is missing`() = runTest {
        coEvery { workerDao.getWorkerById(10L) } returns testWorker.right()
        coEvery { workerDao.getChallenge(10L, "missing", any()) } returns
            WorkerError.InvalidChallenge("missing").left()

        val result = service.authenticateWorker(10L, "missing", "sig")

        assertTrue(result.isLeft())
        assertEquals(AuthenticateWorkerError.InvalidChallenge("missing"), result.leftOrNull())
        coVerify(exactly = 0) { workerDao.consumeChallenge(any()) }
    }
}

