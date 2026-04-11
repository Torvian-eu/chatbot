package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenChallengeResponse
import eu.torvian.chatbot.common.models.api.auth.ServiceTokenResponse
import eu.torvian.chatbot.common.models.api.worker.WorkerChallengeDto
import eu.torvian.chatbot.common.models.worker.WorkerDto
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class WorkerAuthManagerTest {

    @Test
    fun `uses existing token when not expired`() = runTest {
        val initialToken = StoredServiceToken("cached", Instant.parse("2026-04-10T12:30:00Z"))
        val store = InMemoryTokenStore(initialToken)
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 60.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val token = manager.getValidToken()

        assertTrue(token.isRight())
        assertEquals("cached", token.getOrNull()?.accessToken)
        assertEquals(0, api.challengeCalls)
        assertEquals(0, api.exchangeCalls)
    }

    @Test
    fun `runs challenge flow when token is missing`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val token = manager.getValidToken()

        assertTrue(token.isRight())
        assertEquals("fresh-token", token.getOrNull()?.accessToken)
        assertEquals(1, api.challengeCalls)
        assertEquals(1, api.exchangeCalls)
        assertEquals("fresh-token", store.load().getOrNull()?.accessToken)
    }

    @Test
    fun `refreshes when cached token is within refresh skew`() = runTest {
        val initialToken = StoredServiceToken("cached", Instant.parse("2026-04-10T11:00:30Z"))
        val store = InMemoryTokenStore(initialToken)
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 60.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val token = manager.getValidToken()

        assertTrue(token.isRight())
        assertEquals("fresh-token", token.getOrNull()?.accessToken)
        assertEquals(1, api.challengeCalls)
        assertEquals(1, api.exchangeCalls)
    }

    @Test
    fun `force reauthenticate clears token and requests a new one`() = runTest {
        val store = InMemoryTokenStore(StoredServiceToken("old", Instant.parse("2026-04-10T12:30:00Z")))
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val token = manager.forceReauthenticate()

        assertTrue(token.isRight())
        assertEquals("fresh-token", token.getOrNull()?.accessToken)
        assertEquals(1, api.challengeCalls)
        assertEquals(1, api.exchangeCalls)
    }

    @Test
    fun `propagates logical auth errors from the API`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi(challengeError = WorkerAuthApiError.WorkerNotFound(999L))

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 999L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        assertTrue(result.swap().getOrNull() is WorkerAuthManagerError.AuthApi)
    }

    @Test
    fun `returns expired challenge when server challenge is already stale`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi(challengeExpiresAt = Instant.parse("2026-04-10T10:59:00Z"))

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        assertTrue(result.swap().getOrNull() is WorkerAuthManagerError.ExpiredChallenge)
        assertEquals(1, api.challengeCalls)
        assertEquals(0, api.exchangeCalls)
    }

    @Test
    fun `concurrent getValidToken calls only authenticate once`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi(challengeDelayMs = 50)

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val results = listOf(
            async { manager.getValidToken() },
            async { manager.getValidToken() },
            async { manager.getValidToken() }
        ).awaitAll()

        assertTrue(results.all { it.isRight() })
        assertEquals(1, api.challengeCalls)
        assertEquals(1, api.exchangeCalls)
    }

    @Test
    fun `corrupt token cache is cleared and recovered with challenge flow`() = runTest {
        val store = CorruptOnceTokenStore()
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val token = manager.getValidToken()

        assertTrue(token.isRight())
        assertEquals("fresh-token", token.getOrNull()?.accessToken)
        assertEquals(1, store.clearCalls)
        assertEquals(1, api.challengeCalls)
        assertEquals(1, api.exchangeCalls)
    }

    @Test
    fun `returns blank challenge payload error when challenge is empty`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi(challengePayload = "   ")

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        assertEquals(WorkerAuthManagerError.BlankChallengePayload, result.swap().getOrNull())
        assertEquals(1, api.challengeCalls)
        assertEquals(0, api.exchangeCalls)
    }

    @Test
    fun `wraps signer failures`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(error = ChallengeSignerError.SigningFailed("boom")),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthManagerError.ChallengeSigner)
    }

    @Test
    fun `wraps exchange failures from auth api`() = runTest {
        val store = InMemoryTokenStore(null)
        val api = FakeWorkerAuthApi(exchangeError = WorkerAuthApiError.InvalidCredentials("nope"))

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthManagerError.AuthApi)
        assertEquals(1, api.challengeCalls)
        assertEquals(1, api.exchangeCalls)
    }

    @Test
    fun `wraps token store save failures`() = runTest {
        val store = SaveFailingTokenStore()
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthManagerError.TokenStore)
        assertTrue(error.error is ServiceTokenStoreError.TokenCacheWriteFailed)
    }

    @Test
    fun `returns token store error when corruption recovery clear fails`() = runTest {
        val store = CorruptAndClearFailingTokenStore()
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.getValidToken()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthManagerError.TokenStore)
        assertTrue(error.error is ServiceTokenStoreError.TokenCacheDeleteFailed)
    }

    @Test
    fun `force reauthenticate returns token store error when clear fails`() = runTest {
        val store = ClearFailingTokenStore()
        val api = FakeWorkerAuthApi()

        val manager: WorkerAuthManager = WorkerAuthManagerImpl(
            workerId = 7L,
            certificateFingerprint = "fp",
            refreshSkew = 1.minutes,
            tokenStore = store,
            authApi = api,
            signer = FakeSigner(),
            nowProvider = { Instant.parse("2026-04-10T11:00:00Z") }
        )

        val result = manager.forceReauthenticate()

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is WorkerAuthManagerError.TokenStore)
        assertTrue(error.error is ServiceTokenStoreError.TokenCacheDeleteFailed)
        assertEquals(0, api.challengeCalls)
    }

    private class InMemoryTokenStore(initial: StoredServiceToken?) : ServiceTokenStore {
        private var value: StoredServiceToken? = initial

        override suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?> = Either.Right(value)

        override suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit> {
            value = token
            return Either.Right(Unit)
        }

        override suspend fun clear(): Either<ServiceTokenStoreError, Unit> {
            value = null
            return Either.Right(Unit)
        }
    }

    private class FakeSigner(
        private val error: ChallengeSignerError? = null
    ) : ChallengeSigner {
        override fun sign(challenge: String): Either<ChallengeSignerError, String> {
            return error?.let { Either.Left(it) } ?: Either.Right("signed-$challenge")
        }
    }

    private class FakeWorkerAuthApi(
        private val challengeError: WorkerAuthApiError? = null,
        private val exchangeError: WorkerAuthApiError? = null,
        private val challengeDelayMs: Long = 0,
        private val challengeExpiresAt: Instant = Instant.parse("2026-04-10T11:01:00Z"),
        private val challengePayload: String = "challenge-payload"
    ) : WorkerAuthApi {
        var challengeCalls = 0
        var exchangeCalls = 0

        override suspend fun createChallenge(workerId: Long, certificateFingerprint: String): Either<WorkerAuthApiError, ServiceTokenChallengeResponse> {
            challengeCalls++
            challengeError?.let { return Either.Left(it) }
            if (challengeDelayMs > 0) {
                delay(challengeDelayMs)
            }
            return Either.Right(
                ServiceTokenChallengeResponse(
                    workerId = workerId,
                    challenge = WorkerChallengeDto(
                        challengeId = "challenge-1",
                        challenge = challengePayload,
                        expiresAt = challengeExpiresAt
                    )
                )
            )
        }

        override suspend fun exchangeServiceToken(
            workerId: Long,
            challengeId: String,
            signatureBase64: String
        ): Either<WorkerAuthApiError, ServiceTokenResponse> {
            exchangeCalls++
            exchangeError?.let { return Either.Left(it) }
            return Either.Right(
                ServiceTokenResponse(
                    accessToken = "fresh-token",
                    expiresAt = Instant.parse("2026-04-10T12:00:00Z"),
                    worker = WorkerDto(
                        id = workerId,
                        ownerUserId = 1L,
                        displayName = "test-worker",
                        certificateFingerprint = "fp",
                        allowedScopes = listOf("mcp:invoke"),
                        createdAt = Instant.parse("2026-04-10T10:00:00Z"),
                        lastSeenAt = null
                    )
                )
            )
        }
    }

    private class CorruptOnceTokenStore : ServiceTokenStore {
        private var firstLoad = true
        private var token: StoredServiceToken? = null
        var clearCalls = 0

        override suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?> {
            if (firstLoad) {
                firstLoad = false
                return Either.Left(ServiceTokenStoreError.TokenCacheCorrupt("token.json", "invalid json"))
            }
            return Either.Right(token)
        }

        override suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit> {
            this.token = token
            return Either.Right(Unit)
        }

        override suspend fun clear(): Either<ServiceTokenStoreError, Unit> {
            clearCalls++
            token = null
            return Either.Right(Unit)
        }
    }

    private class SaveFailingTokenStore : ServiceTokenStore {
        override suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?> = Either.Right(null)

        override suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit> {
            return Either.Left(ServiceTokenStoreError.TokenCacheWriteFailed("token.json", "disk full"))
        }

        override suspend fun clear(): Either<ServiceTokenStoreError, Unit> = Either.Right(Unit)
    }

    private class CorruptAndClearFailingTokenStore : ServiceTokenStore {
        private var firstLoad = true

        override suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?> {
            return if (firstLoad) {
                firstLoad = false
                Either.Left(ServiceTokenStoreError.TokenCacheCorrupt("token.json", "invalid"))
            } else {
                Either.Right(null)
            }
        }

        override suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit> = Either.Right(Unit)

        override suspend fun clear(): Either<ServiceTokenStoreError, Unit> {
            return Either.Left(ServiceTokenStoreError.TokenCacheDeleteFailed("token.json", "access denied"))
        }
    }

    private class ClearFailingTokenStore : ServiceTokenStore {
        override suspend fun load(): Either<ServiceTokenStoreError, StoredServiceToken?> {
            return Either.Right(StoredServiceToken("cached", Instant.parse("2026-04-10T12:30:00Z")))
        }

        override suspend fun save(token: StoredServiceToken): Either<ServiceTokenStoreError, Unit> = Either.Right(Unit)

        override suspend fun clear(): Either<ServiceTokenStoreError, Unit> {
            return Either.Left(ServiceTokenStoreError.TokenCacheDeleteFailed("token.json", "access denied"))
        }
    }
}


