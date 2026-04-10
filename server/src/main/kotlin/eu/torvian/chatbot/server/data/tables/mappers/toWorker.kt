package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.common.models.worker.Worker
import eu.torvian.chatbot.server.data.tables.WorkersTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Converts an Exposed row from `workers` into the shared [Worker] model.
 *
 * @receiver Result row that includes `WorkersTable` columns.
 * @return Mapped [Worker].
 */
fun ResultRow.toWorker(): Worker = Worker(
    id = this[WorkersTable.id].value,
    ownerUserId = this[WorkersTable.ownerUserId].value,
    displayName = this[WorkersTable.displayName],
    certificatePem = this[WorkersTable.certificatePem],
    certificateFingerprint = this[WorkersTable.certificateFingerprint],
    allowedScopes = runCatching { Json.decodeFromString<List<String>>(this[WorkersTable.allowedScopesJson]) }
        .getOrDefault(emptyList()),
    createdAt = Instant.fromEpochMilliseconds(this[WorkersTable.createdAt]),
    lastSeenAt = this[WorkersTable.lastSeenAt]?.let(Instant::fromEpochMilliseconds)
)


