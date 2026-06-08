package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.LocalMCPServerSignatureDao
import eu.torvian.chatbot.server.data.entities.LocalMCPServerSignatureEntity
import eu.torvian.chatbot.server.data.tables.LocalMCPServerSignaturesTable
import eu.torvian.chatbot.server.data.tables.mappers.toLocalMCPServerSignatureEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Exposed ORM implementation of [LocalMCPServerSignatureDao].
 *
 * Manages signature metadata for Local MCP servers in the `local_mcp_server_signatures` table.
 *
 * @property transactionScope The transaction scope for database access within coroutines.
 */
class LocalMCPServerSignatureDaoExposed(
    private val transactionScope: TransactionScope
) : LocalMCPServerSignatureDao {

    override suspend fun upsertSignature(entity: LocalMCPServerSignatureEntity): LocalMCPServerSignatureEntity =
        transactionScope.transaction {
            val now = Clock.System.now().toEpochMilliseconds()

            // Try to update existing signature first
            val updatedRows = LocalMCPServerSignaturesTable.update(
                where = {
                    (LocalMCPServerSignaturesTable.serverId eq entity.serverId) and
                        (LocalMCPServerSignaturesTable.userDeviceId eq entity.userDeviceId)
                }
            ) {
                it[signature] = entity.signature
                it[timestamp] = entity.timestamp
                it[nonce] = entity.nonce
                it[payloadJson] = entity.payloadJson
                it[updatedAt] = now
            }

            if (updatedRows > 0) {
                // Return the updated entity
                LocalMCPServerSignatureEntity(
                    serverId = entity.serverId,
                    userDeviceId = entity.userDeviceId,
                    signature = entity.signature,
                    timestamp = entity.timestamp,
                    nonce = entity.nonce,
                    payloadJson = entity.payloadJson,
                    createdAt = entity.createdAt,
                    updatedAt = Instant.fromEpochMilliseconds(now)
                )
            } else {
                // Insert new signature
                LocalMCPServerSignaturesTable.insert {
                    it[serverId] = entity.serverId
                    it[userDeviceId] = entity.userDeviceId
                    it[signature] = entity.signature
                    it[timestamp] = entity.timestamp
                    it[nonce] = entity.nonce
                    it[payloadJson] = entity.payloadJson
                    it[createdAt] = now
                    it[updatedAt] = now
                }

                // Return the inserted entity
                LocalMCPServerSignatureEntity(
                    serverId = entity.serverId,
                    userDeviceId = entity.userDeviceId,
                    signature = entity.signature,
                    timestamp = entity.timestamp,
                    nonce = entity.nonce,
                    payloadJson = entity.payloadJson,
                    createdAt = Instant.fromEpochMilliseconds(now),
                    updatedAt = Instant.fromEpochMilliseconds(now)
                )
            }
        }

    override suspend fun getSignaturesByServerId(serverId: Long): List<LocalMCPServerSignatureEntity> =
        transactionScope.transaction {
            LocalMCPServerSignaturesTable
                .selectAll()
                .where { LocalMCPServerSignaturesTable.serverId eq serverId }
                .map { it.toLocalMCPServerSignatureEntity() }
        }

    override suspend fun getSignature(serverId: Long, userDeviceId: Long): LocalMCPServerSignatureEntity? =
        transactionScope.transaction {
            LocalMCPServerSignaturesTable
                .selectAll()
                .where {
                    (LocalMCPServerSignaturesTable.serverId eq serverId) and
                        (LocalMCPServerSignaturesTable.userDeviceId eq userDeviceId)
                }
                .singleOrNull()
                ?.toLocalMCPServerSignatureEntity()
        }

    override suspend fun deleteSignature(serverId: Long, userDeviceId: Long) {
        transactionScope.transaction {
            LocalMCPServerSignaturesTable.deleteWhere {
                (LocalMCPServerSignaturesTable.serverId eq serverId) and
                    (LocalMCPServerSignaturesTable.userDeviceId eq userDeviceId)
            }
        }
    }

    override suspend fun deleteSignaturesByServerId(serverId: Long) {
        transactionScope.transaction {
            LocalMCPServerSignaturesTable.deleteWhere {
                LocalMCPServerSignaturesTable.serverId eq serverId
            }
        }
    }
}
