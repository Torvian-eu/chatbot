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
 * @property workerUid Stable worker UID used for auth requests.
 * @property certificateFingerprint Fingerprint proving which worker identity is being authenticated.
 * @property refreshSkew Amount of time to refresh before expiry.
 * @property tokenStore Persistence abstraction for the cached service token.
 * @property authApi Worker authentication HTTP API client.
 * @property signer Signs the server-issued challenge with the worker private key.
 * @property nowProvider Clock abstraction used to evaluate expiry in tests.
 */
class WorkerAuthManagerImpl(
    private val workerUid: String,
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

    /**
     * In-memory cache of the last-loaded or last-issued service token.
     *
     * Used to avoid repeated disk reads for the common case of repeated `getValidToken()` calls
     * within the token's validity window. Cleared when:
     * - `forceReauthenticate()` is called (to force fresh auth)
     * - Disk token corruption is detected and recovered (to prevent stale state)
     *
     * Access is thread-safe under the [authMutex] lock during critical sections.
     */
    @Volatile
    private var cachedToken: StoredServiceToken? = null

    override
    suspend fun getValidToken(): Either<WorkerAuthManagerError, StoredServiceToken> = either {
        // Fast path: check in-memory cache first to avoid disk access.
        val inMemory = cachedToken
        if (inMemory != null && !shouldRefresh(inMemory)) {
            logger.debug("Using in-memory cached worker token (workerUid={})", workerUid)
            return@either inMemory
        }

        // Slow path: acquire lock to safely load from disk or authenticate.
        // This ensures concurrent callers do not redundantly read from disk.
        authMutex.withLock {
            either<WorkerAuthManagerError, StoredServiceToken> {
                // Re-check in-memory cache under lock in case another caller just populated it.
                val reloadedInMemory = cachedToken
                if (reloadedInMemory != null && !shouldRefresh(reloadedInMemory)) {
                    logger.debug("Using in-memory cached worker token after lock re-check (workerUid={})", workerUid)
                    return@either reloadedInMemory
                }

                val reloaded = loadCachedTokenRecoveringCorruption().bind()
                if (reloaded != null && !shouldRefresh(reloaded)) {
                    logger.debug("Using disk-backed cached worker token after lock re-check (workerUid={})", workerUid)
                    cachedToken = reloaded
                    reloaded
                } else {
                    logger.info("Cached worker token missing or expired; starting challenge flow (workerUid={})", workerUid)
                    authenticateWithChallengeFlow().bind()
                }
            }
        }.bind()
    }

    override
    suspend fun forceReauthenticate(): Either<WorkerAuthManagerError, StoredServiceToken> = either {
        authMutex.withLock {
            either {
                logger.info("Forcing worker re-authentication (workerUid={})", workerUid)
                // Clear both in-memory and persisted token state to force fresh challenge flow.
                cachedToken = null
                tokenStore.clear().mapLeft { WorkerAuthManagerError.TokenStore(it) }.bind()
                authenticateWithChallengeFlow().bind()
            }
        }.bind()
    }

    /**
     * Loads the cached token and treats corruption as recoverable by clearing the cache once.
     *
     * When corruption is detected, clears both the in-memory cache and persisted store to ensure
     * no stale token state survives recovery.
     *
     * @return Either a token-store/auth-manager logical error or the cached token (or `null` if absent/reset).
     */
    private suspend fun loadCachedTokenRecoveringCorruption(): Either<WorkerAuthManagerError, StoredServiceToken?> {
        return when (val loadResult = tokenStore.load()) {
            is Either.Right -> Either.Right(loadResult.value)
            is Either.Left -> {
                val error = loadResult.value
                if (error is ServiceTokenStoreError.TokenCacheCorrupt) {
                    logger.warn("Worker token cache is corrupt; clearing in-memory and persisted state (workerUid={})", workerUid)
                    // Clear in-memory cache first to ensure stale state cannot survive recovery.
                    cachedToken = null
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
        val challengeResponse = authApi.createChallenge(workerUid = workerUid, certificateFingerprint = certificateFingerprint)
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
        logger.debug("Signing worker challenge (workerUid={}, challengeId={})", workerUid, challenge.challengeId)
        val signature = signer.sign(challenge.challenge)
            .mapLeft { WorkerAuthManagerError.ChallengeSigner(it) }
            .bind()

        // Exchange the signed challenge for the actual short-lived service token.
        logger.debug("Submitting signed worker challenge (workerUid={}, challengeId={})", workerUid, challenge.challengeId)
        val tokenResponse = authApi.exchangeServiceToken(
            workerUid = workerUid,
            challengeId = challenge.challengeId,
            signatureBase64 = signature
        ).mapLeft { WorkerAuthManagerError.AuthApi(it) }.bind()

        val storedToken = StoredServiceToken(
            accessToken = tokenResponse.accessToken,
            expiresAt = tokenResponse.expiresAt
        )
        tokenStore.save(storedToken).mapLeft { WorkerAuthManagerError.TokenStore(it) }.bind()
        logger.info("Stored new worker token (workerUid={}, expiresAt={})", workerUid, storedToken.expiresAt)
        // Populate in-memory cache with the newly issued token.
        cachedToken = storedToken
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


