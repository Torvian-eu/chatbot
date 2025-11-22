package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails
import eu.torvian.chatbot.common.models.api.access.ResourceAccessDetails
import eu.torvian.chatbot.common.models.api.access.ResourceAccessInfo
import eu.torvian.chatbot.common.models.api.access.toOwnerInfo
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.ModelAccessDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.ModelOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.*
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.access.GrantResourceAccessError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePrivateError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePublicError
import eu.torvian.chatbot.server.service.core.error.access.RevokeResourceAccessError
import eu.torvian.chatbot.server.service.core.error.model.AddModelError
import eu.torvian.chatbot.server.service.core.error.model.DeleteModelError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.model.UpdateModelError
import eu.torvian.chatbot.server.service.core.error.usergroup.GetGroupByNameError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import kotlinx.serialization.json.JsonObject
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError as DaoUpdateModelError

/**
 * Implementation of the [LLMModelService] interface.
 */
class LLMModelServiceImpl(
    private val modelDao: ModelDao,
    private val llmProviderDao: LLMProviderDao,
    private val transactionScope: TransactionScope,
    private val modelOwnershipDao: ModelOwnershipDao,
    private val modelAccessDao: ModelAccessDao,
    private val userGroupService: UserGroupService,
    private val userService: UserService
) : LLMModelService {

    override suspend fun getAllModels(): List<LLMModel> {
        return transactionScope.transaction {
            modelDao.getAllModels()
        }
    }

    override suspend fun getModelById(id: Long): Either<GetModelError, LLMModel> =
        transactionScope.transaction {
            either {
                withError({ daoError: ModelError.ModelNotFound ->
                    GetModelError.ModelNotFound(daoError.id)
                }) {
                    modelDao.getModelById(id).bind()
                }
            }
        }

    override suspend fun getModelsByProviderId(providerId: Long): List<LLMModel> =
        transactionScope.transaction {
            modelDao.getModelsByProviderId(providerId)
        }

    override suspend fun getAllAccessibleModels(userId: Long, accessMode: AccessMode): List<LLMModel> {
        return transactionScope.transaction {
            modelDao.getAllAccessibleModels(userId, accessMode)
        }
    }

    override suspend fun getAccessibleModelsByProviderId(
        userId: Long,
        providerId: Long,
        accessMode: AccessMode
    ): List<LLMModel> {
        return transactionScope.transaction {
            modelDao.getAccessibleModelsByProviderId(userId, providerId, accessMode)
        }
    }

    override suspend fun addModel(
        ownerId: Long,
        name: String,
        providerId: Long,
        type: LLMModelType,
        active: Boolean,
        displayName: String?,
        capabilities: JsonObject?
    ): Either<AddModelError, LLMModel> =
        transactionScope.transaction {
            either {
                ensure(!name.isBlank()) {
                    AddModelError.InvalidInput("Model name cannot be blank.")
                }

                val createdModel = withError({ daoError: InsertModelError ->
                    when (daoError) {
                        is InsertModelError.ProviderNotFound -> AddModelError.ProviderNotFound(daoError.providerId)
                        is InsertModelError.ModelNameAlreadyExists -> AddModelError.ModelNameAlreadyExists(daoError.name)
                    }
                }) {
                    modelDao.insertModel(name, providerId, type, active, displayName, capabilities).bind()
                }

                // Set ownership for the newly created model
                withError({ daoError: SetOwnerError ->
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation -> AddModelError.OwnershipError("Failed to set model ownership")
                        is SetOwnerError.AlreadyOwned -> AddModelError.OwnershipError("Model ownership conflict")
                    }
                }) {
                    modelOwnershipDao.setOwner(createdModel.id, ownerId).bind()
                }

                createdModel
            }
        }

    override suspend fun updateModel(model: LLMModel): Either<UpdateModelError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!model.name.isBlank()) {
                    UpdateModelError.InvalidInput("Model name cannot be blank.")
                }

                withError({ daoError: DaoUpdateModelError ->
                    when (daoError) {
                        is DaoUpdateModelError.ModelNotFound -> UpdateModelError.ModelNotFound(daoError.id)
                        is DaoUpdateModelError.ProviderNotFound -> UpdateModelError.ProviderNotFound(daoError.providerId)
                        is DaoUpdateModelError.ModelNameAlreadyExists -> UpdateModelError.ModelNameAlreadyExists(
                            daoError.name
                        )
                    }
                }) {
                    modelDao.updateModel(model).bind()
                }
            }
        }

    override suspend fun deleteModel(id: Long): Either<DeleteModelError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: ModelError.ModelNotFound ->
                    DeleteModelError.ModelNotFound(daoError.id)
                }) {
                    modelDao.deleteModel(id).bind()
                }
            }
        }

    override suspend fun isApiKeyConfiguredForModel(modelId: Long): Boolean {
        return transactionScope.transaction {
            modelDao.getModelById(modelId)
                .map { model ->
                    // Get the provider and check if it has an API key
                    llmProviderDao.getProviderById(model.providerId)
                        .map { provider ->
                            provider.apiKeyId != null
                        }
                        .getOrElse { false }
                }
                .getOrElse { false }
        }
    }

    // --- Access Management ---

    override suspend fun grantModelAccess(
        modelId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<GrantResourceAccessError, Unit> =
        transactionScope.transaction {
            either {
                withError({ error: GrantAccessError ->
                    when (error) {
                        is GrantAccessError.AlreadyGranted ->
                            GrantResourceAccessError.AlreadyGranted(modelId, groupId, accessMode.key)

                        is GrantAccessError.ForeignKeyViolation ->
                            GrantResourceAccessError.InvalidRelatedEntity(modelId, groupId)
                    }
                }) {
                    modelAccessDao.grantAccess(modelId, groupId, accessMode.key).bind()
                }
            }
        }

    override suspend fun revokeModelAccess(
        modelId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<RevokeResourceAccessError, Unit> =
        transactionScope.transaction {
            either {
                withError({ error: RevokeAccessError ->
                    when (error) {
                        is RevokeAccessError.AccessNotGranted ->
                            RevokeResourceAccessError.AccessNotFound(modelId, groupId, accessMode.key)
                    }
                }) {
                    modelAccessDao.revokeAccess(modelId, groupId, accessMode.key).bind()
                }
            }
        }

    override suspend fun getModelDetails(modelId: Long): Either<GetModelError, LLMModelDetails> =
        transactionScope.transaction {
            either {
                // Verify model exists
                val model = withError({ daoError: ModelError.ModelNotFound ->
                    GetModelError.ModelNotFound(daoError.id)
                }) {
                    modelDao.getModelById(modelId).bind()
                }

                // Get owner user details if present
                val owner = modelOwnershipDao.getOwner(modelId).getOrNull()
                    ?.let { ownerId ->
                        userService.getUserById(ownerId).getOrNull()
                    }

                // Get all access entries grouped by access mode
                val allAccessGroups = modelAccessDao.getAccessGroups(modelId)

                // Combine into access info list
                val accessList = buildList {
                    allAccessGroups.forEach { (accessMode, groups) ->
                        groups.forEach { group ->
                            add(
                                ResourceAccessInfo(
                                    groupId = group.id,
                                    groupName = group.name,
                                    accessMode = accessMode
                                )
                            )
                        }
                    }
                }

                LLMModelDetails(
                    model = model,
                    accessDetails = ResourceAccessDetails(
                        resourceId = modelId,
                        owner = owner?.toOwnerInfo(),
                        accessList = accessList
                    )
                )
            }
        }

    // --- Convenience Methods ---

    override suspend fun makeModelPublic(modelId: Long): Either<MakeResourcePublicError, Unit> =
        transactionScope.transaction {
            either {
                // Get the "All Users" group
                val allUsersGroup =
                    withError({ error: GetGroupByNameError ->
                        when (error) {
                            is GetGroupByNameError.NotFound -> MakeResourcePublicError.AllUsersGroupNotFound
                        }
                    }) {
                        userGroupService.getAllUsersGroup().bind()
                    }

                // Grant READ access to "All Users" group
                modelAccessDao.grantAccess(modelId, allUsersGroup.id, AccessMode.READ.key).fold(
                    ifLeft = { error ->
                        when (error) {
                            is GrantAccessError.AlreadyGranted -> {
                                // Already public - idempotent
                            }

                            is GrantAccessError.ForeignKeyViolation -> {
                                raise(MakeResourcePublicError.InvalidRelatedEntity(modelId, allUsersGroup.id))
                            }
                        }
                    },
                    ifRight = { /* Success */ }
                )
            }
        }

    override suspend fun makeModelPrivate(modelId: Long): Either<MakeResourcePrivateError, Unit> =
        transactionScope.transaction {
            either {
                // Get the "All Users" group
                val allUsersGroup =
                    withError({ error: GetGroupByNameError ->
                        when (error) {
                            is GetGroupByNameError.NotFound -> MakeResourcePrivateError.AllUsersGroupNotFound
                        }
                    }) {
                        userGroupService.getAllUsersGroup().bind()
                    }

                // Revoke any access that "All Users" group has for this model (all modes)
                modelAccessDao.revokeAllAccess(modelId, allUsersGroup.id)
            }
        }

}
