package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.InsertModelError
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError
import eu.torvian.chatbot.server.data.tables.LLMModelAccessTable
import eu.torvian.chatbot.server.data.tables.LLMModelOwnersTable
import eu.torvian.chatbot.server.data.tables.LLMModelTable
import eu.torvian.chatbot.server.data.tables.UserGroupMembershipsTable
import eu.torvian.chatbot.server.data.tables.mappers.toLLMModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed implementation of the [ModelDao].
 */
class ModelDaoExposed(
    private val transactionScope: TransactionScope
) : ModelDao {
    override suspend fun getAllModels(): List<LLMModel> =
        transactionScope.transaction {
            LLMModelTable.selectAll()
                .map { it.toLLMModel() }
        }

    override suspend fun getModelById(id: Long): Either<ModelError.ModelNotFound, LLMModel> =
        transactionScope.transaction {
            LLMModelTable.selectAll().where { LLMModelTable.id eq id }
                .singleOrNull()
                ?.toLLMModel()
                ?.right()
                ?: ModelError.ModelNotFound(id).left()
        }

    override suspend fun getModelsByProviderId(providerId: Long): List<LLMModel> =
        transactionScope.transaction {
            LLMModelTable.selectAll().where { LLMModelTable.providerId eq providerId }
                .map { it.toLLMModel() }
        }

    override suspend fun getAllAccessibleModels(userId: Long, accessMode: AccessMode): List<LLMModel> =
        transactionScope.transaction {
            // Models accessible via group memberships
            val groupAccessSubquery = LLMModelTable
                .innerJoin(LLMModelAccessTable, { id }, { modelId })
                .innerJoin(UserGroupMembershipsTable, { LLMModelAccessTable.userGroupId }, { groupId })
                .select(LLMModelTable.columns)
                .where {
                    (UserGroupMembershipsTable.userId eq userId) and
                            (LLMModelAccessTable.accessMode eq accessMode.key)
                }

            // Models directly owned by the user
            val directOwnershipSubquery = LLMModelTable
                .innerJoin(LLMModelOwnersTable, { id }, { modelId })
                .select(LLMModelTable.columns)
                .where { LLMModelOwnersTable.userId eq userId }

            groupAccessSubquery.union(directOwnershipSubquery)
                .map { it.toLLMModel() }
        }

    override suspend fun getAccessibleModelsByProviderId(
        userId: Long,
        providerId: Long,
        accessMode: AccessMode
    ): List<LLMModel> =
        transactionScope.transaction {
            // Models accessible via group memberships filtered by provider
            val groupAccessSubquery = LLMModelTable
                .innerJoin(LLMModelAccessTable, { id }, { modelId })
                .innerJoin(UserGroupMembershipsTable, { LLMModelAccessTable.userGroupId }, { groupId })
                .select(LLMModelTable.columns)
                .where {
                    (UserGroupMembershipsTable.userId eq userId) and
                            (LLMModelAccessTable.accessMode eq accessMode.key) and
                            (LLMModelTable.providerId eq providerId)
                }

            // Models directly owned by the user filtered by provider
            val directOwnershipSubquery = LLMModelTable
                .innerJoin(LLMModelOwnersTable, { id }, { modelId })
                .select(LLMModelTable.columns)
                .where { (LLMModelOwnersTable.userId eq userId) and (LLMModelTable.providerId eq providerId) }

            groupAccessSubquery.union(directOwnershipSubquery)
                .map { it.toLLMModel() }
        }

    override suspend fun insertModel(
        name: String,
        providerId: Long,
        type: LLMModelType,
        active: Boolean,
        displayName: String?,
        capabilities: JsonObject?
    ): Either<InsertModelError, LLMModel> =
        transactionScope.transaction {
            either {
                catch({
                    val insertStatement = LLMModelTable.insert {
                        it[LLMModelTable.name] = name
                        it[LLMModelTable.providerId] = providerId
                        it[LLMModelTable.type] = type
                        it[LLMModelTable.active] = active
                        it[LLMModelTable.displayName] = displayName
                        it[LLMModelTable.capabilities] = capabilities?.let { cap ->
                            Json.encodeToString(cap)
                        }
                    }
                    insertStatement.resultedValues?.first()?.toLLMModel()
                        ?: throw IllegalStateException("Failed to retrieve newly inserted model")
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() -> raise(InsertModelError.ProviderNotFound(providerId))
                        e.isUniqueConstraintViolation() -> raise(InsertModelError.ModelNameAlreadyExists(name))
                        else -> throw e
                    }
                }
            }
        }

    override suspend fun updateModel(model: LLMModel): Either<UpdateModelError, Unit> =
        transactionScope.transaction {
            either {
                catch({
                    val updatedRowCount = LLMModelTable.update({ LLMModelTable.id eq model.id }) {
                        it[name] = model.name
                        it[providerId] = model.providerId
                        it[type] = model.type
                        it[active] = model.active
                        it[displayName] = model.displayName
                        it[capabilities] = model.capabilities?.let { cap ->
                            Json.encodeToString(JsonObject.serializer(), cap)
                        }
                    }
                    ensure(updatedRowCount != 0) { UpdateModelError.ModelNotFound(model.id) }
                }) { e: ExposedSQLException ->
                    when {
                        e.isForeignKeyViolation() -> raise(UpdateModelError.ProviderNotFound(model.providerId))
                        e.isUniqueConstraintViolation() -> raise(UpdateModelError.ModelNameAlreadyExists(model.name))
                        else -> throw e
                    }
                }
            }
        }

    override suspend fun deleteModel(id: Long): Either<ModelError.ModelNotFound, Unit> =
        transactionScope.transaction {
            either {
                val deletedCount = LLMModelTable.deleteWhere { LLMModelTable.id eq id }
                ensure(deletedCount != 0) { ModelError.ModelNotFound(id) }
            }
        }


}
