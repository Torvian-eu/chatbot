package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.WorkerCredentialProfileSignatureDao
import eu.torvian.chatbot.server.data.entities.WorkerCredentialProfileSignatureEntity
import eu.torvian.chatbot.server.data.tables.WorkerCredentialProfileSignaturesTable
import eu.torvian.chatbot.server.data.tables.mappers.toWorkerCredentialProfileSignatureEntity
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Exposed ORM implementation of [WorkerCredentialProfileSignatureDao].
 *
 * A profile holds at most one signature per device, so a repeated write replaces the existing row
 * instead of accumulating history.
 *
 * @property transactionScope Transaction boundary used for every statement.
 */
class WorkerCredentialProfileSignatureDaoExposed(
    private val transactionScope: TransactionScope,
) : WorkerCredentialProfileSignatureDao {

    override suspend fun upsertSignature(
        entity: WorkerCredentialProfileSignatureEntity,
    ): WorkerCredentialProfileSignatureEntity = transactionScope.transaction {
        val now = Clock.System.now().toEpochMilliseconds()
        val updatedRows = WorkerCredentialProfileSignaturesTable.update(
            where = {
                (WorkerCredentialProfileSignaturesTable.profileId eq entity.profileId) and
                    (WorkerCredentialProfileSignaturesTable.userDeviceId eq entity.userDeviceId)
            }
        ) { row ->
            row[signature] = entity.signature
            row[timestamp] = entity.timestamp
            row[nonce] = entity.nonce
            row[payloadJson] = entity.payloadJson
            row[updatedAt] = now
        }

        if (updatedRows == 0) {
            WorkerCredentialProfileSignaturesTable.insert { row ->
                row[profileId] = entity.profileId
                row[userDeviceId] = entity.userDeviceId
                row[signature] = entity.signature
                row[timestamp] = entity.timestamp
                row[nonce] = entity.nonce
                row[payloadJson] = entity.payloadJson
                row[createdAt] = now
                row[updatedAt] = now
            }
        }

        entity.copy(
            createdAt = if (updatedRows == 0) Instant.fromEpochMilliseconds(now) else entity.createdAt,
            updatedAt = Instant.fromEpochMilliseconds(now)
        )
    }

    override suspend fun getSignaturesByProfileIds(
        profileIds: List<Long>,
    ): List<WorkerCredentialProfileSignatureEntity> = transactionScope.transaction {
        if (profileIds.isEmpty()) {
            emptyList()
        } else {
            WorkerCredentialProfileSignaturesTable
                .selectAll()
                .where { WorkerCredentialProfileSignaturesTable.profileId inList profileIds }
                .map { it.toWorkerCredentialProfileSignatureEntity() }
        }
    }

    override suspend fun deleteSignaturesByProfileId(profileId: Long) {
        transactionScope.transaction {
            WorkerCredentialProfileSignaturesTable.deleteWhere {
                WorkerCredentialProfileSignaturesTable.profileId eq profileId
            }
        }
    }
}
