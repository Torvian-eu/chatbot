package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.InstructionEntity
import eu.torvian.chatbot.server.data.tables.InstructionTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps an Exposed [ResultRow] from `instructions` to an [InstructionEntity].
 *
 * Content columns are copied verbatim (including the nullable generated-message marker and the raw
 * `custom` JSON text); the owning user and the linking roles are separate relations loaded
 * elsewhere.
 *
 * @receiver The result row produced by a query against [InstructionTable].
 * @return The corresponding [InstructionEntity].
 */
fun ResultRow.toInstructionEntity(): InstructionEntity = InstructionEntity(
    id = this[InstructionTable.id].value,
    type = this[InstructionTable.type],
    name = this[InstructionTable.name],
    message = this[InstructionTable.message],
    custom = this[InstructionTable.custom],
    createdAt = Instant.fromEpochMilliseconds(this[InstructionTable.createdAt]),
    updatedAt = Instant.fromEpochMilliseconds(this[InstructionTable.updatedAt])
)
