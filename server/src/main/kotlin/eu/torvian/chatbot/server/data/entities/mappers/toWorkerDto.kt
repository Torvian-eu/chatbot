package eu.torvian.chatbot.server.data.entities.mappers

import eu.torvian.chatbot.common.models.worker.WorkerDto
import eu.torvian.chatbot.server.data.entities.WorkerEntity

/**
 * Maps [WorkerEntity] to API-facing [WorkerDto].
 */
fun WorkerEntity.toWorkerDto(): WorkerDto = WorkerDto(
    id = this.id,
    workerUid = this.workerUid,
    ownerUserId = this.ownerUserId,
    displayName = this.displayName,
    certificateFingerprint = this.certificateFingerprint,
    allowedScopes = this.allowedScopes,
    createdAt = this.createdAt,
    lastSeenAt = this.lastSeenAt
)


