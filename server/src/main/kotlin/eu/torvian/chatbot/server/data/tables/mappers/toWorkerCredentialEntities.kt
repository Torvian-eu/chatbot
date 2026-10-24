package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.api.credential.CredentialChannel
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileSignatureEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity
import eu.torvian.chatbot.server.data.tables.WorkerCredentialProfileSignaturesTable
import eu.torvian.chatbot.server.data.tables.WorkerCredentialProfileTable
import eu.torvian.chatbot.server.data.tables.WorkerCredentialSecretTable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Converts an Exposed row from `worker_credential_profiles` to [WorkerCredentialProfileEntity].
 *
 * Invalid JSON payloads degrade to empty collections rather than failing the read, matching the MCP
 * mapper's tolerance so one malformed row cannot break the whole listing.
 *
 * @receiver Result row containing [WorkerCredentialProfileTable] columns.
 * @return Parsed profile entity.
 */
fun ResultRow.toWorkerCredentialProfileEntity(): WorkerCredentialProfileEntity =
    WorkerCredentialProfileEntity(
        id = this[WorkerCredentialProfileTable.id].value,
        workerId = this[WorkerCredentialProfileTable.workerId].value,
        name = this[WorkerCredentialProfileTable.name],
        description = this[WorkerCredentialProfileTable.description],
        secretRef = this[WorkerCredentialProfileTable.secretRef],
        command = this[WorkerCredentialProfileTable.command],
        args = runCatching {
            Json.decodeFromString<List<String>>(this[WorkerCredentialProfileTable.argsJson])
        }.getOrDefault(emptyList()),
        channel = runCatching {
            CredentialChannel.valueOf(this[WorkerCredentialProfileTable.channel])
        }.getOrDefault(CredentialChannel.ASKPASS),
        envName = this[WorkerCredentialProfileTable.envName],
        fileName = this[WorkerCredentialProfileTable.fileName],
        askpassUsername = this[WorkerCredentialProfileTable.askpassUsername],
        environmentVariables = runCatching {
            Json.decodeFromString<Map<String, String>>(this[WorkerCredentialProfileTable.environmentJson])
        }.getOrDefault(emptyMap()),
        askpassVariables = runCatching {
            Json.decodeFromString<List<String>>(this[WorkerCredentialProfileTable.askpassVariablesJson])
        }.getOrDefault(emptyList()),
        isEnabled = this[WorkerCredentialProfileTable.isEnabled],
        createdAt = Instant.fromEpochMilliseconds(this[WorkerCredentialProfileTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[WorkerCredentialProfileTable.updatedAt]),
    )

/**
 * Converts an Exposed row from `worker_credential_secrets` to [WorkerCredentialSecretEntity].
 *
 * @receiver Result row containing [WorkerCredentialSecretTable] columns.
 * @return Parsed secret entity; the columns are ciphertext, never the value.
 */
fun ResultRow.toWorkerCredentialSecretEntity(): WorkerCredentialSecretEntity =
    WorkerCredentialSecretEntity(
        id = this[WorkerCredentialSecretTable.id].value,
        workerId = this[WorkerCredentialSecretTable.workerId].value,
        reference = this[WorkerCredentialSecretTable.reference],
        encryptedSecret = this[WorkerCredentialSecretTable.encryptedSecret],
        encryptedDek = this[WorkerCredentialSecretTable.encryptedDek],
        algorithm = this[WorkerCredentialSecretTable.algorithm],
        workerCertificateFingerprint = this[WorkerCredentialSecretTable.workerCertificateFingerprint],
        createdAt = Instant.fromEpochMilliseconds(this[WorkerCredentialSecretTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[WorkerCredentialSecretTable.updatedAt]),
    )

/**
 * Converts an Exposed row from `worker_credential_profile_signatures` to
 * [WorkerCredentialProfileSignatureEntity].
 *
 * @receiver Result row containing [WorkerCredentialProfileSignaturesTable] columns.
 * @return Parsed signature entity.
 */
fun ResultRow.toWorkerCredentialProfileSignatureEntity(): WorkerCredentialProfileSignatureEntity =
    WorkerCredentialProfileSignatureEntity(
        profileId = this[WorkerCredentialProfileSignaturesTable.profileId].value,
        userDeviceId = this[WorkerCredentialProfileSignaturesTable.userDeviceId].value,
        signature = this[WorkerCredentialProfileSignaturesTable.signature],
        timestamp = this[WorkerCredentialProfileSignaturesTable.timestamp],
        nonce = this[WorkerCredentialProfileSignaturesTable.nonce],
        payloadJson = this[WorkerCredentialProfileSignaturesTable.payloadJson],
        createdAt = Instant.fromEpochMilliseconds(this[WorkerCredentialProfileSignaturesTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[WorkerCredentialProfileSignaturesTable.updatedAt]),
    )
