package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.models.api.credential.CredentialProfileDto
import eu.torvian.chatbot.common.models.api.credential.CredentialSecretMetadataDto
import eu.torvian.chatbot.common.models.api.credential.WorkerCredentialSecretDto
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileEntity
import eu.torvian.chatbot.server.data.entities.WorkerCredentialSecretEntity

/**
 * Converts a stored profile row to the shared profile DTO.
 *
 * @receiver Stored profile entity.
 * @param agentRoleIds Agent roles linked to the profile.
 * @return Profile DTO carrying no secret material.
 */
internal fun WorkerCredentialProfileEntity.toDto(agentRoleIds: Set<Long>): CredentialProfileDto = CredentialProfileDto(
    id = id,
    workerId = workerId,
    name = name,
    description = description,
    secretRef = secretRef,
    command = command,
    args = args,
    channel = channel,
    envName = envName,
    fileName = fileName,
    askpassUsername = askpassUsername,
    isEnabled = isEnabled,
    environmentVariables = environmentVariables,
    askpassVariables = askpassVariables,
    agentRoleIds = agentRoleIds,
    updatedAt = updatedAt,
)

/**
 * Converts a stored secret row to the operator-facing metadata DTO.
 *
 * @receiver Stored secret entity.
 * @return Metadata for the settings surface, with no ciphertext and no value.
 */
internal fun WorkerCredentialSecretEntity.toMetadataDto(): CredentialSecretMetadataDto =
    CredentialSecretMetadataDto(
        id = id,
        workerId = workerId,
        reference = reference,
        algorithm = algorithm,
        workerCertificateFingerprint = workerCertificateFingerprint,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/**
 * Converts a stored secret row to the worker-facing DTO.
 *
 * @receiver Stored secret entity.
 * @return Ciphertext and metadata for the worker bootstrap and notifications.
 */
internal fun WorkerCredentialSecretEntity.toWorkerDto(): WorkerCredentialSecretDto = WorkerCredentialSecretDto(
    id = id,
    workerId = workerId,
    reference = reference,
    encryptedSecret = encryptedSecret,
    encryptedDek = encryptedDek,
    algorithm = algorithm,
    workerCertificateFingerprint = workerCertificateFingerprint,
    updatedAt = updatedAt,
)
