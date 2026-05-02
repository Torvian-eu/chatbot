package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.data.tables.WorkersTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Converts an Exposed row from `workers` into [WorkerEntity].
 *
 * @receiver Result row that includes `WorkersTable` columns.
 * @return Mapped [WorkerEntity].
 */
fun ResultRow.toWorkerEntity(): WorkerEntity = WorkerEntity(
    id = this[WorkersTable.id].value,
    workerUid = this[WorkersTable.workerUid],
    ownerUserId = this[WorkersTable.ownerUserId].value,
    displayName = this[WorkersTable.displayName],
    certificatePem = this[WorkersTable.certificatePem],
    certificateFingerprint = this[WorkersTable.certificateFingerprint],
    allowedScopes = runCatching { Json.decodeFromString<List<String>>(this[WorkersTable.allowedScopesJson]) }
        .getOrDefault(emptyList()),
    createdAt = Instant.fromEpochMilliseconds(this[WorkersTable.createdAt]),
    lastSeenAt = this[WorkersTable.lastSeenAt]?.let(Instant::fromEpochMilliseconds)
)


