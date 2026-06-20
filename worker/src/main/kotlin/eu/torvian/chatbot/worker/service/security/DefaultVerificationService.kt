package eu.torvian.chatbot.worker.service.security

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.security.AsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.buildCanonicalRequestSigningString
import eu.torvian.chatbot.worker.config.TrustedSigner
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Default trust-store-backed implementation of [VerificationService].
 *
 * @property trustedSigners Authorized signers supplied directly from worker runtime configuration.
 * @property cryptoProvider Asymmetric verifier used to validate the reconstructed signature base string.
 */
class DefaultVerificationService(
    private val trustedSigners: List<TrustedSigner>,
    private val cryptoProvider: AsymmetricCryptoProvider
) : VerificationService {
    override suspend fun verify(
        signedRequest: SignedRequest,
        options: VerificationOptions
    ): Either<VerificationError, List<String>> = either {
        val signer = trustedSigners.firstOrNull { it.signerId == signedRequest.signerId }
            ?: raise(VerificationError.UnknownSigner(signedRequest.signerId))

        checkExpiration(signedRequest, options)

        verifySignature(signedRequest, signer)

        signer.permissions
    }

    /**
     * Converts crypto-provider outcomes into a boolean trust decision for this service.
     *
     * Operational crypto failures are intentionally treated as invalid signatures because callers
     * only need to know that the signed request could not be trusted.
     *
     * @param signedRequest Detached signed request whose metadata and payload form the verified base string.
     * @param signer Trusted signer providing the public key for verification.
     */
    private suspend fun Raise<VerificationError>.verifySignature(signedRequest: SignedRequest, signer: TrustedSigner) {
        val isValid = cryptoProvider.verify(
            data = buildCanonicalRequestSigningString(signedRequest),
            signature = signedRequest.signature,
            publicKey = signer.publicKey
        ).mapLeft {
            VerificationError.InvalidSignature(it)
        }.bind()

        ensure(isValid) { VerificationError.InvalidSignature() }
    }

    /**
     * Rejects the signed request if its timestamp is outside the configured expiration window.
     *
     * The absolute timestamp skew is checked so requests too far in the future are rejected the
     * same way as stale requests, while the reported age keeps its sign for diagnostics.
     *
     * @param signedRequest Detached signed request carrying the timestamp to evaluate.
     * @param options Verification settings that define whether checks are enabled and the accepted time window.
     */
    private fun Raise<VerificationError>.checkExpiration(signedRequest: SignedRequest, options: VerificationOptions) {
        if (!options.checkExpiration) return

        val ageMillis = Clock.System.now().toEpochMilliseconds() - signedRequest.timestamp
        val windowMillis = options.expirationWindowSeconds * MILLIS_PER_SECOND

        ensure(abs(ageMillis) <= windowMillis) {
            VerificationError.Expired(
                timestamp = signedRequest.timestamp,
                ageSeconds = ageMillis / MILLIS_PER_SECOND
            )
        }
    }

    /**
     * Constants used by verification calculations.
     */
    private companion object {
        /** Number of milliseconds in one second for expiration-window conversion. */
        private const val MILLIS_PER_SECOND = 1_000L
    }
}