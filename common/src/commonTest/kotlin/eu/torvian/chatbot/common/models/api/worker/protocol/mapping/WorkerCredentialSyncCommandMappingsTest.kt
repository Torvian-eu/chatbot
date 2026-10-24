package eu.torvian.chatbot.common.models.api.worker.protocol.mapping

import arrow.core.getOrElse
import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.SignedCredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.common.models.api.worker.protocol.codec.decodeProtocolPayload
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerCommandResultStatuses
import eu.torvian.chatbot.common.models.api.worker.protocol.constants.WorkerProtocolCommandTypes
import eu.torvian.chatbot.common.models.api.worker.protocol.payload.*
import eu.torvian.chatbot.common.security.SignedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Verifies the credential sync command mappings round-trip through the worker protocol codec.
 */
class WorkerCredentialSyncCommandMappingsTest {

    /**
     * Ensures both profile command payloads encode and decode with their own command types.
     */
    @Test
    fun `profile create and update commands round-trip`() {
        val signedProfile = SignedCredentialProfileDto(
            profile = profile(),
            signedRequest = SignedRequest(
                payload = "{}",
                signature = "sig",
                signerId = "device",
                timestamp = 1L,
                nonce = "nonce"
            )
        )

        val created = WorkerCredentialProfileCreateCommandData(signedProfile)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("create mapping failed: $it") }
        assertEquals(WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_CREATE, created.commandType)
        assertEquals(
            signedProfile,
            created.toWorkerCredentialProfileCreateCommandData().getOrElse { error("create decode failed: $it") }
                .signedProfile
        )

        val updated = WorkerCredentialProfileUpdateCommandData(signedProfile)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("update mapping failed: $it") }
        assertEquals(WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_UPDATE, updated.commandType)
        assertEquals(
            signedProfile,
            updated.toWorkerCredentialProfileUpdateCommandData().getOrElse { error("update decode failed: $it") }
                .signedProfile
        )
    }

    /**
     * Ensures both secret command payloads encode and decode with their own command types.
     */
    @Test
    fun `secret create and update commands round-trip`() {
        val secret = secret()

        val created = WorkerCredentialSecretCreateCommandData(secret)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("secret create mapping failed: $it") }
        assertEquals(WorkerProtocolCommandTypes.CREDENTIAL_SECRET_CREATE, created.commandType)
        assertEquals(
            secret,
            created.toWorkerCredentialSecretCreateCommandData().getOrElse { error("create decode failed: $it") }.secret
        )

        val updated = WorkerCredentialSecretUpdateCommandData(secret)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("secret update mapping failed: $it") }
        assertEquals(WorkerProtocolCommandTypes.CREDENTIAL_SECRET_UPDATE, updated.commandType)
        assertEquals(
            secret,
            updated.toWorkerCredentialSecretUpdateCommandData().getOrElse { error("update decode failed: $it") }.secret
        )
    }

    /**
     * Ensures the delete commands carry the identifier the worker cache keys on.
     */
    @Test
    fun `delete commands round-trip`() {
        val profilePayload = WorkerCredentialProfileDeleteCommandData(profileId = 5L)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("profile delete mapping failed: $it") }
        assertEquals(WorkerProtocolCommandTypes.CREDENTIAL_PROFILE_DELETE, profilePayload.commandType)
        assertEquals(
            5L,
            profilePayload.toWorkerCredentialProfileDeleteCommandData()
                .getOrElse { error("profile delete decode failed: $it") }
                .profileId
        )

        val secretPayload = WorkerCredentialSecretDeleteCommandData(secretId = 6L)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("secret delete mapping failed: $it") }
        assertEquals(WorkerProtocolCommandTypes.CREDENTIAL_SECRET_DELETE, secretPayload.commandType)
        assertEquals(
            6L,
            secretPayload.toWorkerCredentialSecretDeleteCommandData()
                .getOrElse { error("secret delete decode failed: $it") }
                .secretId
        )
    }

    /**
     * Ensures a result payload carries the status the dispatching side classifies on.
     *
     * The server reads only the status of a credential result: a success is a delivery, an error
     * status is a worker rejection, and nothing the worker wrote reaches the operator. The mapping's
     * job therefore ends at encoding, and the encoded data still round-trips through the codec.
     */
    @Test
    fun `result payloads encode the status the dispatching side classifies`() {
        val success = WorkerCredentialProfileCreateResultData(profileId = 9L)
            .toWorkerCommandResultPayload()
            .getOrElse { error("result mapping failed: $it") }
        assertEquals(WorkerCommandResultStatuses.SUCCESS, success.status)
        assertEquals(
            9L,
            decodeProtocolPayload<WorkerCredentialProfileCreateResultData>(
                payload = success.data,
                targetType = "WorkerCredentialProfileCreateResultData"
            ).getOrElse { error("result decode failed: $it") }
                .profileId
        )

        val failure = WorkerCredentialCommandErrorResultData(
            entityId = 9L,
            code = "INVALID_SIGNATURE",
            message = "profile rejected"
        ).toWorkerCommandResultPayload().getOrElse { error("error mapping failed: $it") }
        assertEquals(WorkerCommandResultStatuses.ERROR, failure.status)
        assertEquals(
            "INVALID_SIGNATURE",
            decodeProtocolPayload<WorkerCredentialCommandErrorResultData>(
                payload = failure.data,
                targetType = "WorkerCredentialCommandErrorResultData"
            ).getOrElse { error("error decode failed: $it") }
                .code
        )
    }

    /**
     * Ensures a payload of the wrong command type is rejected rather than decoded.
     */
    @Test
    fun `mismatched command type is rejected`() {
        val payload = WorkerCredentialProfileDeleteCommandData(profileId = 1L)
            .toWorkerCommandRequestPayload()
            .getOrElse { error("mapping failed: $it") }

        val decoded = payload.toWorkerCredentialProfileCreateCommandData()

        assertIs<WorkerMcpRuntimeCommandProtocolMappingError.InvalidCommandType>(decoded.leftOrNull())
    }

    /**
     * @return A profile with every optional field populated.
     */
    private fun profile(): CredentialProfileDto = CredentialProfileDto(
        id = 1L,
        workerId = 2L,
        name = "vps",
        description = "shell",
        secretRef = "vps_password",
        command = "/bin/sh",
        args = listOf("-c", "true"),
        channel = CredentialChannel.ENV,
        envName = "TOKEN",
        environmentVariables = mapOf("A" to "b"),
        updatedAt = Instant.fromEpochMilliseconds(10L),
    )

    /**
     * @return An encrypted secret row.
     */
    private fun secret(): WorkerCredentialSecretDto = WorkerCredentialSecretDto(
        id = 3L,
        workerId = 2L,
        reference = "vps_password",
        encryptedSecret = "ciphertext",
        encryptedDek = "dek",
        algorithm = "RSA-OAEP-256+A256GCM",
        workerCertificateFingerprint = "AB:CD",
        updatedAt = Instant.fromEpochMilliseconds(10L),
    )
}
