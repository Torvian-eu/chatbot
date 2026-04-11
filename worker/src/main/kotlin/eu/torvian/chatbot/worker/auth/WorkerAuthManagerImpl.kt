package eu.torvian.chatbot.worker.auth

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Default implementation of [WorkerAuthManager].
 *
 * The manager always prefers a cached access token first. If the cache is empty or the
 * token is considered too close to expiry, it falls back to the certificate challenge flow,
 * persists the newly issued token, and returns that token to the caller.
 *
 * @property workerId Stable worker identifier used for auth requests.
 * @property certificateFingerprint Fingerprint proving which worker identity is being authenticated.
 * @property refreshSkew Amount of time to refresh before expiry.
 * @property tokenStore Persistence abstraction for the cached service token.
 * @property authApi Worker authentication HTTP API client.
 * @property signer Signs the server-issued challenge with the worker private key.
 * @property nowProvider Clock abstraction used to evaluate expiry in tests.
 */
class WorkerAuthManagerImpl(
    private val workerId: Long,
    private val certificateFingerprint: String,
    private val refreshSkew: Duration,
    private val tokenStore: ServiceTokenStore,
    private val authApi: WorkerAuthApi,
    private val signer: ChallengeSigner,
    private val nowProvider: () -> Instant = { Clock.System.now() }
) : WorkerAuthManager {
    /**
     * Serializes refresh/auth flows so concurrent callers do not trigger duplicate challenge exchanges.
     */
    private val authMutex = Mutex()

    override
    suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken> = either {
        val existing = loadCachedTokenRecoveringCorruption().bind()
        if (existing != null && !shouldRefresh(existing)) {
            logger.debug("Using cached worker token (workerId={})", workerId)
            return@either existing
        }

        authMutex.withLock {
            either<WorkerAuthManagerError, StoredServiceToken> {
                val reloaded = loadCachedTokenRecoveringCorruption().bind()
                if (reloaded != null && !shouldRefresh(reloaded)) {
                    logger.debug("Using cached worker token after lock re-check (workerId={})", workerId)
                    reloaded
                } else {
                    logger.info("Cached worker token missing or expired; starting challenge flow (workerId={})", workerId)
                    authenticateWithChallengeFlow().bind()
                }
            }
        }.bind()
    }

    override
    suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken> = either {
        authMutex.withLock {
            either {
                logger.info("Forcing worker re-authentication (workerId={})", workerId)
                tokenStore.clear().mapLeft { WorkerAuthManagerError.TokenStore(it) }.bind()
                authenticateWithChallengeFlow().bind()
            }
        }.bind()
    }

    /**
     * Loads the cached token and treats corruption as recoverable by clearing the cache once.
     *
     * @return Either a token-store/auth-manager logical error or the cached token (or `null` if absent/reset).
     */
    private suspend fun loadCachedTokenRecoveringCorruption(): Either<WorkerAuthManagerError, StoredServiceToken?> {
        return when (val loadResult = tokenStore.load()) {
            is Either.Right -> Either.Right(loadResult.value)
            is Either.Left -> {
                val error = loadResult.value
                if (error is ServiceTokenStoreError.TokenCacheCorrupt) {
                    logger.warn("Worker token cache is corrupt; clearing and reauthenticating (workerId={})", workerId)
                    tokenStore.clear()
                        .mapLeft { WorkerAuthManagerError.TokenStore(it) }
                        .map { null }
                } else {
                    Either.Left(WorkerAuthManagerError.TokenStore(error))
                }
            }
        }
    }

    /**
     * Runs the certificate challenge-response flow and stores the resulting token.
     *
     * The sequence is intentionally strict: request challenge, validate that the payload is
     * non-empty, sign it with the worker private key, exchange the signature for a token,
     * and then persist the token for the next startup.
     *
     * @return Either a logical worker-auth error or a newly issued token.
     */
    private suspend fun authenticateWithChallengeFlow(): Either<WorkerAuthManagerError, StoredServiceToken> = either {
        // Ask the server for a one-time challenge that proves ownership of the worker certificate.
        val challengeResponse = authApi.createChallenge(workerId = workerId, certificateFingerprint = certificateFingerprint)
            .mapLeft { WorkerAuthManagerError.AuthApi(it) }
            .bind()
        val challenge = challengeResponse.challenge

        // A blank challenge would make signing meaningless, so treat it as a logical flow failure.
        ensure(challenge.challenge.isNotBlank()) {
            WorkerAuthManagerError.BlankChallengePayload
        }

        val now = nowProvider()
        ensure(challenge.expiresAt > now) {
            WorkerAuthManagerError.ExpiredChallenge(
                challengeId = challenge.challengeId,
                expiresAt = challenge.expiresAt,
                now = now
            )
        }

        // Sign the challenge payload with the private key stored for this worker instance.
        logger.debug("Signing worker challenge (workerId={}, challengeId={})", workerId, challenge.challengeId)
        val signature = signer.sign(challenge.challenge)
            .mapLeft { WorkerAuthManagerError.ChallengeSigner(it) }
            .bind()

        // Exchange the signed challenge for the actual short-lived service token.
        logger.debug("Submitting signed worker challenge (workerId={}, challengeId={})", workerId, challenge.challengeId)
        val tokenResponse = authApi.exchangeServiceToken(
            workerId = workerId,
            challengeId = challenge.challengeId,
            signatureBase64 = signature
        ).mapLeft { WorkerAuthManagerError.AuthApi(it) }.bind()

        val storedToken = StoredServiceToken(
            accessToken = tokenResponse.accessToken,
            expiresAt = tokenResponse.expiresAt
        )
        tokenStore.save(storedToken).mapLeft { WorkerAuthManagerError.TokenStore(it) }.bind()
        logger.info("Stored new worker token (workerId={}, expiresAt={})", workerId, storedToken.expiresAt)
        storedToken
    }

    /**
     * Returns true when the token is already too close to expiry for safe reuse.
     *
     * @param token Token being evaluated.
     * @return `true` when the token should be refreshed, otherwise `false`.
     */
    private fun shouldRefresh(token: StoredServiceToken): Boolean {
        val nowWithSkew = nowProvider().plus(refreshSkew)
        return token.expiresAt <= nowWithSkew
    }

    companion object {
        private val logger: Logger = LogManager.getLogger(WorkerAuthManagerImpl::class.java)
    }
}


