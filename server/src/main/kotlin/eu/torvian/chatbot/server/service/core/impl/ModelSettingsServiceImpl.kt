package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.models.api.access.IsPublicResponse
import eu.torvian.chatbot.common.models.api.access.ResourceAccessInfo
import eu.torvian.chatbot.common.models.api.access.ResourceAccessResponse
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsAccessDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.error.access.GetResourceAccessError
import eu.torvian.chatbot.server.service.core.error.access.GrantResourceAccessError
import eu.torvian.chatbot.server.service.core.error.access.RevokeResourceAccessError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePublicError
import eu.torvian.chatbot.server.service.core.error.access.MakeResourcePrivateError
import eu.torvian.chatbot.server.service.core.error.access.CheckResourcePublicError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.settings.AddSettingsError
import eu.torvian.chatbot.server.service.core.error.settings.DeleteSettingsError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.core.error.settings.UpdateSettingsError
import eu.torvian.chatbot.server.service.core.error.usergroup.GetGroupByNameError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError

/**
 * Implementation of the [ModelSettingsService] interface.
 */
class ModelSettingsServiceImpl(
    private val settingsDao: SettingsDao,
    private val llmModelService: LLMModelService,
    private val transactionScope: TransactionScope,
    private val settingsOwnershipDao: SettingsOwnershipDao,
    private val settingsAccessDao: SettingsAccessDao,
    private val userGroupService: UserGroupService
) : ModelSettingsService {

    override suspend fun getSettingsById(id: Long): Either<GetSettingsByIdError, ModelSettings> =
        transactionScope.transaction {
            either {
                withError({ daoError: SettingsError.SettingsNotFound ->
                    GetSettingsByIdError.SettingsNotFound(daoError.id)
                }) {
                    settingsDao.getSettingsById(id).bind()
                }
            }
        }

    override suspend fun getAllSettings(): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAllSettings()
        }
    }

    override suspend fun getSettingsByModelId(modelId: Long): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getSettingsByModelId(modelId)
        }
    }

    override suspend fun getAllAccessibleSettings(userId: Long, accessMode: AccessMode): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAllAccessibleSettings(userId, accessMode)
        }
    }

    override suspend fun getAccessibleSettingsByModelId(
        userId: Long,
        modelId: Long,
        accessMode: AccessMode
    ): List<ModelSettings> {
        return transactionScope.transaction {
            settingsDao.getAccessibleSettingsByModelId(userId, modelId, accessMode)
        }
    }

    override suspend fun addSettings(ownerId: Long, settings: ModelSettings): Either<AddSettingsError, ModelSettings> =
        transactionScope.transaction {
            either {
                // Get the associated LLMModel to verify type consistency
                val llmModel = withError({ getModelError: GetModelError ->
                    when (getModelError) {
                        is GetModelError.ModelNotFound -> AddSettingsError.ModelNotFound(getModelError.id)
                    }
                }) {
                    llmModelService.getModelById(settings.modelId).bind()
                }
                ensure(settings.modelType == llmModel.type) {
                    AddSettingsError.InvalidInput(
                        "Model settings type (${settings.modelType}) does not match the associated LLM Model's type (${llmModel.type})."
                    )
                }

                // Insert the settings
                val createdModelSettings = withError({ daoError: SettingsError.ModelNotFound ->
                    AddSettingsError.ModelNotFound(daoError.modelId)
                }) {
                    settingsDao.insertSettings(settings).bind()
                }

                // Set ownership for the newly created settings
                withError({ daoError: SetOwnerError ->
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation -> AddSettingsError.OwnershipError("Failed to set settings ownership")
                        is SetOwnerError.AlreadyOwned -> AddSettingsError.OwnershipError("Settings ownership conflict")
                    }
                }) {
                    settingsOwnershipDao.setOwner(createdModelSettings.id, ownerId).bind()
                }

                createdModelSettings
            }
        }

    override suspend fun updateSettings(settings: ModelSettings): Either<UpdateSettingsError, Unit> =
        transactionScope.transaction {
            either {
                // Get the associated LLMModel to verify type consistency
                val llmModel = withError({ getModelError: GetModelError ->
                    when (getModelError) {
                        is GetModelError.ModelNotFound -> UpdateSettingsError.ModelNotFound(getModelError.id)
                    }
                }) {
                    llmModelService.getModelById(settings.modelId).bind()
                }

                // Verify that the ModelSettings type matches the LLMModel's type
                ensure(settings.modelType == llmModel.type) {
                    UpdateSettingsError.InvalidInput(
                        "Model settings type (${settings.modelType}) does not match the associated LLM Model's type (${llmModel.type})."
                    )
                }

                // Update the settings
                withError({ daoError: SettingsError ->
                    when (daoError) {
                        is SettingsError.SettingsNotFound -> UpdateSettingsError.SettingsNotFound(daoError.id)
                        is SettingsError.ModelNotFound -> UpdateSettingsError.ModelNotFound(daoError.modelId)
                    }
                }) {
                    settingsDao.updateSettings(settings).bind()
                }
            }
        }

    override suspend fun deleteSettings(id: Long): Either<DeleteSettingsError, Unit> =
        transactionScope.transaction {
            either {
                withError({ daoError: SettingsError.SettingsNotFound ->
                    DeleteSettingsError.SettingsNotFound(daoError.id)
                }) {
                    settingsDao.deleteSettings(id).bind()
                }
            }
        }

    // --- Access Management ---

    override suspend fun grantSettingsAccess(
        settingsId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<GrantResourceAccessError, Unit> = transactionScope.transaction {
        either {
            withError({ error: GrantAccessError ->
                when (error) {
                    is GrantAccessError.AlreadyGranted -> GrantResourceAccessError.AlreadyGranted(
                        settingsId,
                        groupId,
                        accessMode.key
                    )

                    is GrantAccessError.ForeignKeyViolation -> {
                        GrantResourceAccessError.InvalidRelatedEntity(settingsId, groupId)
                    }
                }
            }) {
                settingsAccessDao.grantAccess(settingsId, groupId, accessMode.key).bind()
            }
        }
    }

    override suspend fun revokeSettingsAccess(
        settingsId: Long,
        groupId: Long,
        accessMode: AccessMode
    ): Either<RevokeResourceAccessError, Unit> = transactionScope.transaction {
        either {
            withError({ error: RevokeAccessError ->
                when (error) {
                    is RevokeAccessError.AccessNotGranted ->
                        RevokeResourceAccessError.AccessNotFound(settingsId, groupId, accessMode.key)
                }
            }) {
                settingsAccessDao.revokeAccess(settingsId, groupId, accessMode.key).bind()
            }
        }
    }

    override suspend fun getSettingsAccess(settingsId: Long): Either<GetResourceAccessError, ResourceAccessResponse> =
        transactionScope.transaction {
            either {
                // Verify settings exists
                withError({ daoError: SettingsError.SettingsNotFound ->
                    GetResourceAccessError.ResourceNotFound(daoError.id)
                }) {
                    settingsDao.getSettingsById(settingsId).bind()
                }

                // Get owner ID
                val ownerId = withError({ error: GetOwnerError ->
                    when (error) {
                        is GetOwnerError.ResourceNotFound -> GetResourceAccessError.OwnerNotFound(settingsId)
                    }
                }) {
                    settingsOwnershipDao.getOwner(settingsId).bind()
                }

                // Get all access entries for all access modes
                val allAccessGroups = settingsAccessDao.getAccessGroups(settingsId)

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

                ResourceAccessResponse(
                    resourceId = settingsId,
                    ownerId = ownerId,
                    accessList = accessList
                )
            }
        }

    // --- Convenience Methods ---

    override suspend fun makeSettingsPublic(settingsId: Long): Either<MakeResourcePublicError, Unit> =
        transactionScope.transaction {
            either {
                // Get the "All Users" group
                val allUsersGroup = withError({ error: GetGroupByNameError ->
                    when (error) {
                        is GetGroupByNameError.NotFound -> MakeResourcePublicError.AllUsersGroupNotFound
                    }
                }) {
                    userGroupService.getAllUsersGroup().bind()
                }

                // Grant READ access to "All Users" group
                settingsAccessDao.grantAccess(settingsId, allUsersGroup.id, AccessMode.READ.key).fold(
                    ifLeft = { error ->
                        when (error) {
                            is GrantAccessError.AlreadyGranted -> {
                                // Already public - idempotent
                            }
                            is GrantAccessError.ForeignKeyViolation -> {
                                raise(MakeResourcePublicError.InvalidRelatedEntity(settingsId, allUsersGroup.id))
                            }
                        }
                    },
                    ifRight = { /* Success */ }
                )
            }
        }

    override suspend fun makeSettingsPrivate(settingsId: Long): Either<MakeResourcePrivateError, Unit> =
        transactionScope.transaction {
            either {
                // Get the "All Users" group
                val allUsersGroup = withError({ error: GetGroupByNameError ->
                    when (error) {
                        is GetGroupByNameError.NotFound -> MakeResourcePrivateError.AllUsersGroupNotFound
                    }
                }) {
                    userGroupService.getAllUsersGroup().bind()
                }

                // Revoke any access that "All Users" group has for this settings profile (all modes)
                settingsAccessDao.revokeAllAccess(settingsId, allUsersGroup.id)
            }
        }

    override suspend fun isSettingsPublic(settingsId: Long): Either<CheckResourcePublicError, IsPublicResponse> =
        transactionScope.transaction {
            either {
                val accessResponse = withError({ error: GetResourceAccessError ->
                    when (error) {
                        is GetResourceAccessError.ResourceNotFound, is GetResourceAccessError.OwnerNotFound ->
                            CheckResourcePublicError.ResourceNotFound(settingsId)
                    }
                }) {
                    getSettingsAccess(settingsId).bind()
                }

                val allUsersAccess = accessResponse.accessList.filter { it.groupName == CommonUserGroups.ALL_USERS }

                IsPublicResponse(
                    hasAllUsersReadAccess = allUsersAccess.any { it.accessMode == AccessMode.READ.key },
                    hasAllUsersWriteAccess = allUsersAccess.any { it.accessMode == AccessMode.WRITE.key },
                    hasAllUsersOtherAccess = allUsersAccess.any { it.accessMode != AccessMode.READ.key && it.accessMode != AccessMode.WRITE.key }
                )
            }
        }
}
