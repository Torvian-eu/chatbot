package eu.torvian.chatbot.common.models.api.credential

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies serialization of the credential DTOs and the absence of a plaintext secret field.
 */
class CredentialDtosTest {

    /**
     * Ensures a profile survives a JSON round trip including both environment-carrying fields.
     */
    @Test
    fun `profile round-trips environment and askpass variables`() {
        val profile = CredentialProfileDto(
            id = 1L,
            workerId = 2L,
            name = "vps",
            description = "prod shell",
            secretRef = "vps_password",
            command = "/bin/sh",
            args = listOf("-c", "true"),
            channel = CredentialChannel.ASKPASS,
            askpassUsername = "root",
            environmentVariables = mapOf("SSH_ASKPASS_REQUIRE" to "force"),
            askpassVariables = listOf("SSH_ASKPASS"),
            updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        )

        val encoded = Json.encodeToString(CredentialProfileDto.serializer(), profile)
        val decoded = Json.decodeFromString(CredentialProfileDto.serializer(), encoded)

        assertEquals(profile, decoded)
        assertEquals(CredentialChannel.ASKPASS, decoded.channel)
    }

    /**
     * Ensures channels use their documented lower-case wire names.
     */
    @Test
    fun `channel wire names are lower case`() {
        assertEquals("\"askpass\"", Json.encodeToString(CredentialChannel.serializer(), CredentialChannel.ASKPASS))
        assertEquals("\"env\"", Json.encodeToString(CredentialChannel.serializer(), CredentialChannel.ENV))
        assertEquals("\"file\"", Json.encodeToString(CredentialChannel.serializer(), CredentialChannel.FILE))
        assertEquals(CredentialChannel.all.size, CredentialChannel.entries.size)
    }

    /**
     * Ensures the operator-facing secret view has no field that could carry the value.
     */
    @Test
    fun `secret metadata carries no ciphertext or plaintext`() {
        val metadata = CredentialSecretMetadataDto(
            id = 1L,
            workerId = 2L,
            reference = "vps_password",
            algorithm = "RSA-OAEP-256+A256GCM",
            workerCertificateFingerprint = "AB:CD",
            createdAt = Instant.fromEpochMilliseconds(1L),
            updatedAt = Instant.fromEpochMilliseconds(2L),
        )

        val encoded = Json.encodeToString(CredentialSecretMetadataDto.serializer(), metadata)

        assertTrue("encryptedSecret" !in encoded)
        assertTrue("encryptedDek" !in encoded)
        assertTrue("value" !in encoded)
    }

    /**
     * Ensures the delete and write results treat a null failure as the delivered case.
     */
    @Test
    fun `dispatch outcome reports delivery through a null failure`() {
        val delivered = WorkerDispatchOutcomeDto(workerId = 7L)
        val degraded = WorkerDispatchOutcomeDto(workerId = 7L, failure = WorkerDispatchFailure.WORKER_OFFLINE)

        assertFalse("failure" in Json.encodeToString(WorkerDispatchOutcomeDto.serializer(), delivered))
        assertTrue(
            "WORKER_OFFLINE" in Json.encodeToString(WorkerDispatchOutcomeDto.serializer(), degraded)
        )
        assertEquals(WorkerDispatchFailure.WORKER_REJECTED, WorkerDispatchFailure.entries[3])
    }

    /**
     * Ensures the withdrawal outcome of a move is additive: absent from a same-worker write, present and
     * decodable once a profile changes worker.
     */
    @Test
    fun `profile write result carries the previous worker outcome only on a move`() {
        val profile = CredentialProfileDto(
            id = 1L,
            workerId = 18L,
            name = "vps",
            secretRef = "ref",
            command = "/bin/sh",
            channel = CredentialChannel.ENV,
            envName = "TOKEN",
            updatedAt = Instant.fromEpochMilliseconds(0L),
        )
        val sameWorker = CredentialProfileWriteResultDto(
            profile = profile,
            dispatch = WorkerDispatchOutcomeDto(workerId = 18L),
        )
        val moved = sameWorker.copy(
            previousWorkerDispatch = WorkerDispatchOutcomeDto(
                workerId = 17L,
                failure = WorkerDispatchFailure.WORKER_OFFLINE,
            )
        )
        val serializer = CredentialProfileWriteResultDto.serializer()

        assertFalse("previousWorkerDispatch" in Json.encodeToString(serializer, sameWorker))
        assertEquals(moved, Json.decodeFromString(serializer, Json.encodeToString(serializer, moved)))
    }
}
