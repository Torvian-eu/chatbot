package eu.torvian.chatbot.worker.credential

import arrow.core.Either
import eu.torvian.chatbot.common.models.api.credential.CreateCredentialProfileRequest
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialCommandErrorResultData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialProfileUpdateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretCreateCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretDeleteCommandData
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.WorkerCredentialSecretUpdateCommandData
import eu.torvian.chatbot.common.security.AsymmetricKeyPair
import eu.torvian.chatbot.common.security.JvmAsymmetricCryptoProvider
import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.security.buildCanonicalRequestSigningString
import eu.torvian.chatbot.worker.config.TrustedSigner
import eu.torvian.chatbot.worker.service.security.DefaultVerificationService
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Verifies how the six credential sync notifications are applied to the worker cache.
 *
 * The configured worker-id pin is the only context a notification is checked against, so a row bound
 * to another worker must be refused here exactly as it is at bootstrap.
 */
class CredentialSyncCommandExecutorTest {

    /** Crypto provider used to sign the profile payloads the commands relay. */
    private val cryptoProvider = JvmAsymmetricCryptoProvider()

    /** Serializer matching the server's payload encoding. */
    private val json = Json { ignoreUnknownKeys = true }

    /** Identifier the trusted signer is registered under. */
    private val signerId = "trusted-device-1"

    /** Worker id the owner pinned in the tests. */
    private val pinnedWorkerId = 2L

    /** Cache the commands are applied to. */
    private val store = InMemoryWorkerCredentialStore()

    /** Key pair whose public half the worker trusts. */
    private lateinit var keyPair: AsymmetricKeyPair

    /**
     * Builds the executor and its trust store around a fresh key pair.
     *
     * @param pin Worker id the executor checks notifications against, or null when no pin is configured.
     */
    private suspend fun executorWithPin(pin: Long? = pinnedWorkerId): CredentialSyncCommandExecutor {
        keyPair = generateKeyPair()
        return CredentialSyncCommandExecutorImpl(
            profileValidator = DefaultCredentialProfileValidator(
                verificationService = DefaultVerificationService(
                    trustedSigners = listOf(TrustedSigner(signerId, keyPair.publicKey, listOf("credentials:write"))),
                    cryptoProvider = cryptoProvider,
                )
            ),
            store = store,
            pinnedWorkerId = pin,
        )
    }

    /** Verifies that a profile notification bound to another worker never enters the cache. */
    @Test
    fun `a profile notification for another worker is rejected`() = runTest {
        val executor = executorWithPin()
        val request = profileRequest(workerId = FOREIGN_WORKER_ID)

        val rejection = assertIs<WorkerCredentialCommandErrorResultData>(
            executor.createProfile(
                WorkerCredentialProfileCreateCommandData(
                    SignedCredentialProfileDto(profileFixture(request), signedRequest(request))
                )
            ).swap().getOrNull()
        )

        assertEquals("CREDENTIAL_PROFILE_FOREIGN_WORKER", rejection.code)
        assertEquals(PROFILE_ID, rejection.entityId)
        assertTrue(store.profiles().isEmpty(), "a foreign-worker profile was cached")
    }

    /** Verifies that a profile notification for this worker is applied. */
    @Test
    fun `a profile notification for this worker is cached`() = runTest {
        val executor = executorWithPin()
        val request = profileRequest(workerId = pinnedWorkerId)

        val result = executor.updateProfile(
            WorkerCredentialProfileUpdateCommandData(
                SignedCredentialProfileDto(profileFixture(request), signedRequest(request))
            )
        )

        assertTrue(result.isRight(), "Unexpected rejection: ${result.swap().getOrNull()}")
        assertNotNull(store.profile("vps"), "the accepted profile was not cached")
    }

    /** Verifies that neither secret handler caches a row bound to another worker. */
    @Test
    fun `secret notifications for another worker are rejected`() = runTest {
        val executor = executorWithPin()
        val foreign = secret(workerId = FOREIGN_WORKER_ID)

        val created = executor.createSecret(WorkerCredentialSecretCreateCommandData(foreign))
        val updated = executor.updateSecret(WorkerCredentialSecretUpdateCommandData(foreign))

        listOf(created, updated).forEach { outcome ->
            val rejection = assertIs<WorkerCredentialCommandErrorResultData>(
                outcome.swap().getOrNull()
            )
            assertEquals("CREDENTIAL_SECRET_FOREIGN_WORKER", rejection.code)
            assertTrue(rejection.details.orEmpty().contains("expected=$pinnedWorkerId"), rejection.details.orEmpty())
        }
        assertNull(store.secret(SECRET_REF), "a foreign-worker secret was cached")
    }

    /** Verifies that a secret notification for this worker is applied by both handlers. */
    @Test
    fun `secret notifications for this worker are cached`() = runTest {
        val executor = executorWithPin()

        assertTrue(
            executor.createSecret(WorkerCredentialSecretCreateCommandData(secret(workerId = pinnedWorkerId)))
                .isRight()
        )
        assertNotNull(store.secret(SECRET_REF), "the accepted secret was not cached")

        assertTrue(
            executor.updateSecret(
                WorkerCredentialSecretUpdateCommandData(
                    secret(workerId = pinnedWorkerId, id = REPLACED_SECRET_ID)
                )
            ).isRight()
        )
        assertEquals(REPLACED_SECRET_ID, store.secret(SECRET_REF)?.id)
    }

    /**
     * Verifies that without a configured pin the per-row check is inactive for both row kinds.
     *
     * An absent pin is the documented "protection not configured" state, so a row the server labels
     * with any worker id is cached exactly as it was before the check existed.
     */
    @Test
    fun `without a pin both row kinds are accepted`() = runTest {
        val executor = executorWithPin(pin = null)
        val request = profileRequest(workerId = FOREIGN_WORKER_ID)

        assertTrue(
            executor.createProfile(
                WorkerCredentialProfileCreateCommandData(
                    SignedCredentialProfileDto(profileFixture(request), signedRequest(request))
                )
            ).isRight()
        )
        assertTrue(
            executor.createSecret(WorkerCredentialSecretCreateCommandData(secret(workerId = FOREIGN_WORKER_ID)))
                .isRight()
        )
        assertNotNull(store.profile("vps"), "the profile was not cached")
        assertNotNull(store.secret(SECRET_REF), "the secret was not cached")
    }

    /**
     * Verifies that the delete notifications carry no worker id and still remove the cached row.
     *
     * A delete names a row this worker already holds or holds nothing, so there is nothing else to check.
     */
    @Test
    fun `delete notifications remove the cached row`() = runTest {
        val executor = executorWithPin()
        val request = profileRequest(workerId = pinnedWorkerId)
        assertTrue(
            executor.createProfile(
                WorkerCredentialProfileCreateCommandData(
                    SignedCredentialProfileDto(profileFixture(request), signedRequest(request))
                )
            ).isRight()
        )
        assertTrue(executor.createSecret(WorkerCredentialSecretCreateCommandData(secret(pinnedWorkerId))).isRight())

        assertTrue(executor.deleteProfile(WorkerCredentialProfileDeleteCommandData(PROFILE_ID)).isRight())
        assertTrue(executor.deleteSecret(WorkerCredentialSecretDeleteCommandData(CACHED_SECRET_ID)).isRight())

        assertNull(store.profile("vps"), "the deleted profile stayed cached")
        assertNull(store.secret(SECRET_REF), "the deleted secret stayed cached")
    }

    /**
     * @param workerId Worker id the notification row claims.
     * @return A create request whose fields mirror the relayed profile.
     */
    private fun profileRequest(workerId: Long): CreateCredentialProfileRequest = CreateCredentialProfileRequest(
        workerId = workerId,
        name = "vps",
        description = "prod shell",
        secretRef = SECRET_REF,
        command = "/bin/sh",
        args = listOf("-c", "true"),
        channel = CredentialChannel.ENV,
        envName = "GH_TOKEN",
    )

    /**
     * @param request Request whose fields the relayed profile must mirror.
     * @return The relayed profile DTO.
     */
    private fun profileFixture(request: CreateCredentialProfileRequest): CredentialProfileDto =
        CredentialProfileDto(
            id = PROFILE_ID,
            workerId = request.workerId,
            name = request.name,
            description = request.description,
            secretRef = request.secretRef,
            command = request.command,
            args = request.args,
            channel = request.channel,
            envName = request.envName,
            fileName = request.fileName,
            askpassUsername = request.askpassUsername,
            isEnabled = request.isEnabled,
            environmentVariables = request.environmentVariables,
            askpassVariables = request.askpassVariables,
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

    /**
     * @param workerId Worker id the secret row claims.
     * @param id Server-side identifier of the row.
     * @return An encrypted secret as the server relays it; no plaintext is involved.
     */
    private fun secret(workerId: Long, id: Long = CACHED_SECRET_ID): WorkerCredentialSecretDto =
        WorkerCredentialSecretDto(
            id = id,
            workerId = workerId,
            reference = SECRET_REF,
            encryptedSecret = "ciphertext",
            encryptedDek = "dek",
            algorithm = "RSA-OAEP-256+A256GCM",
            workerCertificateFingerprint = "fingerprint",
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

    /**
     * @param request Request serialized verbatim into the signed payload.
     * @return Detached signed request accepted by the worker validator.
     */
    private suspend fun signedRequest(request: CreateCredentialProfileRequest): SignedRequest {
        val unsignedRequest = SignedRequest(
            payload = json.encodeToString(request),
            signature = "",
            signerId = signerId,
            timestamp = Clock.System.now().toEpochMilliseconds(),
            nonce = "nonce-1",
        )
        val signature = cryptoProvider.sign(
            buildCanonicalRequestSigningString(unsignedRequest),
            keyPair.privateKey,
        )
        assertTrue(signature is Either.Right, "Signing should succeed")
        return unsignedRequest.copy(signature = signature.value)
    }

    /**
     * @return A generated key pair.
     */
    private suspend fun generateKeyPair(): AsymmetricKeyPair {
        val result = cryptoProvider.generateKeyPair()
        assertTrue(result is Either.Right, "Key-pair generation should succeed")
        return result.value
    }

    private companion object {
        /** Reference the cached secret uses. */
        const val SECRET_REF = "vps_password"

        /** Server-side id of the profile the commands target. */
        const val PROFILE_ID = 10L

        /** Server-side id of the secret row the fixture caches. */
        const val CACHED_SECRET_ID = 20L

        /** Server-side id of the row a test substitutes for [CACHED_SECRET_ID]. */
        const val REPLACED_SECRET_ID = 21L

        /** Worker id that is not the one the bootstrap resolved. */
        const val FOREIGN_WORKER_ID = 99L
    }
}
