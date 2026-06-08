package eu.torvian.chatbot.server.data.tables.mappers

import eu.torvian.chatbot.server.data.entities.LocalMCPServerSignatureEntity
import eu.torvian.chatbot.server.data.tables.LocalMCPServerSignaturesTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.time.Instant

/**
 * Maps a database result row into a [LocalMCPServerSignatureEntity].
 *
 * @receiver Result row containing `LocalMCPServerSignaturesTable` columns.
 * @return Parsed [LocalMCPServerSignatureEntity].
 */
fun ResultRow.toLocalMCPServerSignatureEntity(): LocalMCPServerSignatureEntity {
    return LocalMCPServerSignatureEntity(
        serverId = this[LocalMCPServerSignaturesTable.serverId].value,
        userDeviceId = this[LocalMCPServerSignaturesTable.userDeviceId].value,
        signature = this[LocalMCPServerSignaturesTable.signature],
        timestamp = this[LocalMCPServerSignaturesTable.timestamp],
        nonce = this[LocalMCPServerSignaturesTable.nonce],
        payloadJson = this[LocalMCPServerSignaturesTable.payloadJson],
        createdAt = Instant.fromEpochMilliseconds(this[LocalMCPServerSignaturesTable.createdAt]),
        updatedAt = Instant.fromEpochMilliseconds(this[LocalMCPServerSignaturesTable.updatedAt])
    )
}
