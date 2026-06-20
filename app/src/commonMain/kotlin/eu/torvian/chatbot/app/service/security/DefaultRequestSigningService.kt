package eu.torvian.chatbot.app.service.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import eu.torvian.chatbot.app.service.auth.DeviceIdentityService
import eu.torvian.chatbot.common.security.AsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.buildCanonicalRequestSigningString
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Default implementation of [RequestSigningService] that signs exact request payloads without wrapping them in a transport envelope.
 *
 * The resulting [SignedRequest] keeps the canonical JSON payload string plus detached signature metadata so callers
 * can send the body normally and propagate authorization metadata separately at the protocol layer.
 *
 * @property deviceIdentityService Source of the stable signer ID and persistent signing key pair.
 * @property cryptoProvider Asymmetric signer used to produce the detached signature.
 * @property json JSON serializer used to encode request DTOs into their exact signed payload strings.
 * @property currentTimeProvider Supplies epoch-millisecond timestamps for new signatures.
 * @property nonceGenerator Supplies unique nonces for replay protection.
 */
@OptIn(ExperimentalUuidApi::class)
class DefaultRequestSigningService(
    private val deviceIdentityService: DeviceIdentityService,
    private val cryptoProvider: AsymmetricCryptoProvider,
    private val json: Json,
    private val currentTimeProvider: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val nonceGenerator: () -> String = { Uuid.random().toString() }
) : RequestSigningService {

    override suspend fun <T : Any> signRequest(
        request: T,
        serializer: KSerializer<T>
    ): Either<RequestSigningError, SignedRequest> = either {
        val payload = catch({
            json.encodeToString(serializer, request)
        }) { e: SerializationException ->
            raise(RequestSigningError.SerializationFailure(
                message = "Failed to serialize request for signing: ${e.message}",
                cause = e
            ))
        }
        signPayload(payload).bind()
    }

    override suspend fun signPayload(payload: String): Either<RequestSigningError, SignedRequest> = either {
        val signerId = resolveSignerId().bind()
        val privateKey = resolvePrivateKey().bind()
        val timestamp = currentTimeProvider()
        val nonce = nonceGenerator()

        // The canonical string must stay byte-for-byte aligned with downstream verification logic.
        val canonicalSigningString = buildCanonicalRequestSigningString(
            timestamp = timestamp,
            nonce = nonce,
            signerId = signerId,
            payload = payload
        )

        val signature = cryptoProvider.sign(canonicalSigningString, privateKey).mapLeft { error ->
            RequestSigningError.CryptoFailure(
                message = "Failed to sign canonical request payload: ${error.message}",
                cause = error.cause,
                cryptoError = error
            )
        }.bind()

        SignedRequest(
            payload = payload,
            signature = signature,
            signerId = signerId,
            timestamp = timestamp,
            nonce = nonce
        )
    }

    /**
     * Resolves the stable signer ID that should be embedded in detached request-signing metadata.
     *
     * @return Either identity error or signer/device identifier.
     */
    private suspend fun resolveSignerId(): Either<RequestSigningError, String> =
        deviceIdentityService.getOrCreateDeviceId().mapLeft { error ->
            RequestSigningError.IdentityMissing(
                message = "Failed to get signer ID: ${error.message}",
                cause = Throwable(error.message)
            )
        }

    /**
     * Resolves the persistent private key used to sign canonical request strings.
     *
     * @return Either identity error or encoded private-key bytes.
     */
    private suspend fun resolvePrivateKey(): Either<RequestSigningError, ByteArray> =
        deviceIdentityService.getOrCreateSigningKeyPair().mapLeft { error ->
            RequestSigningError.IdentityMissing(
                message = "Failed to get signing key pair: ${error.message}",
                cause = Throwable(error.message)
            )
        }.map { keyPair ->
            keyPair.privateKey
        }
}
