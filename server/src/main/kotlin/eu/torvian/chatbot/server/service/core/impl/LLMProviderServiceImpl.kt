package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.ProviderOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of the [LLMProviderService] interface.
 */
class LLMProviderServiceImpl(
    private val llmProviderDao: LLMProviderDao,
    private val providerOwnershipDao: ProviderOwnershipDao,
    private val modelDao: ModelDao,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope
) : LLMProviderService {

    companion object {
        private val logger: Logger = LogManager.getLogger(LLMProviderServiceImpl::class.java)
    }
    override suspend fun getAllProviders(): List<LLMProvider> {
        return transactionScope.transaction {
            llmProviderDao.getAllProviders()
        }
    }

    override suspend fun getAllAccessibleProviders(userId: Long, accessMode: AccessMode): List<LLMProvider> {
        return transactionScope.transaction {
            llmProviderDao.getAllAccessibleProviders(userId, accessMode)
        }
    }

    override suspend fun getProviderById(id: Long): Either<GetProviderError, LLMProvider> =
        transactionScope.transaction {
            either {
                withError({ daoError: LLMProviderError.LLMProviderNotFound ->
                    GetProviderError.ProviderNotFound(daoError.id)
                }) {
                    llmProviderDao.getProviderById(id).bind()
                }
            }
        }

    override suspend fun addProvider(
        ownerId: Long,
        name: String,
        description: String,
        baseUrl: String,
        type: LLMProviderType,
        credential: String?
    ): Either<AddProviderError, LLMProvider> =
        transactionScope.transaction {
            logger.info("Adding new provider: $name")

            either {
                ensure(!name.isBlank()) {
                    AddProviderError.InvalidInput("Provider name cannot be blank.")
                }
                ensure(!baseUrl.isBlank()) {
                    AddProviderError.InvalidInput("Provider base URL cannot be blank.")
                }

                // Validate credential if provided
                credential?.let { cred ->
                    ensure(cred.isNotBlank()) {
                        AddProviderError.InvalidInput("Provider credential cannot be blank when provided.")
                    }
                }

                // Store the credential securely and get the alias (if credential is provided)
                val alias = credential?.let { cred ->
                    credentialManager.storeCredential(cred)
                }

                // Create the provider metadata entry
                val provider = withError({ daoError: LLMProviderError.ApiKeyAlreadyInUse ->
                    throw IllegalStateException("API key already in use: ${daoError.apiKeyId}")
                }) {
                    llmProviderDao.insertProvider(alias, name, description, baseUrl, type).bind()
                }

                // Set the ownership of the newly created provider
                withError({ daoError: SetOwnerError ->
                    when (daoError) {
                        is SetOwnerError.ForeignKeyViolation ->
                            AddProviderError.OwnershipError("Failed to set provider ownership")

                        is SetOwnerError.AlreadyOwned ->
                            AddProviderError.OwnershipError("Provider ownership conflict")
                    }
                }) {
                    providerOwnershipDao.setOwner(provider.id, ownerId).bind()
                }

                logger.info("Successfully added new provider: $name (ID: ${provider.id})")

                provider
            }
        }

    override suspend fun updateProvider(provider: LLMProvider): Either<UpdateProviderError, Unit> =
        transactionScope.transaction {
            either {
                ensure(!provider.name.isBlank()) {
                    UpdateProviderError.InvalidInput("Provider name cannot be blank.")
                }
                ensure(!provider.baseUrl.isBlank()) {
                    UpdateProviderError.InvalidInput("Provider base URL cannot be blank.")
                }

                withError({ daoError: LLMProviderError ->
                    when (daoError) {
                        is LLMProviderError.LLMProviderNotFound -> UpdateProviderError.ProviderNotFound(daoError.id)
                        is LLMProviderError.ApiKeyAlreadyInUse -> UpdateProviderError.ApiKeyAlreadyInUse(daoError.apiKeyId)
                    }
                }) {
                    llmProviderDao.updateProvider(provider).bind()
                }
            }
        }

    override suspend fun deleteProvider(id: Long): Either<DeleteProviderError, Unit> =
        transactionScope.transaction {
            either {
                // Check if the provider is still in use by any models
                val modelsUsingProvider = modelDao.getModelsByProviderId(id)
                ensure(modelsUsingProvider.isEmpty()) {
                    DeleteProviderError.ProviderInUse(id, modelsUsingProvider.map { it.name })
                }

                // Get the provider to get the API key ID for credential deletion
                val provider = withError({ daoError: LLMProviderError.LLMProviderNotFound ->
                    DeleteProviderError.ProviderNotFound(daoError.id)
                }) {
                    llmProviderDao.getProviderById(id).bind()
                }

                // Delete the provider metadata
                withError({ daoError: LLMProviderError.LLMProviderNotFound ->
                    throw IllegalStateException("Provider not found when deleting: ${daoError.id}")
                }) {
                    llmProviderDao.deleteProvider(id).bind()
                }

                // Delete the associated credential
                provider.apiKeyId?.let { keyId ->
                    credentialManager.deleteCredential(keyId).getOrElse {
                        throw IllegalStateException("Failed to delete credential for provider ID $id: ${it.alias}")
                    }
                }
            }
        }

    override suspend fun updateProviderCredential(
        providerId: Long,
        newCredential: String?
    ): Either<UpdateProviderCredentialError, Unit> =
        transactionScope.transaction {
            either {
                ensure(newCredential == null || !newCredential.isBlank()) {
                    UpdateProviderCredentialError.InvalidInput("Provider credential cannot be blank.")
                }

                // Get the existing provider
                val existingProvider = withError({ daoError: LLMProviderError.LLMProviderNotFound ->
                    UpdateProviderCredentialError.ProviderNotFound(daoError.id)
                }) {
                    llmProviderDao.getProviderById(providerId).bind()
                }

                // Store the new credential securely and get the alias
                val newAlias = newCredential?.let { credentialManager.storeCredential(newCredential) }

                // Update the provider with the new API key ID
                val updatedProvider = existingProvider.copy(apiKeyId = newAlias)
                withError({ daoError: LLMProviderError ->
                    when (daoError) {
                        is LLMProviderError.LLMProviderNotFound -> throw IllegalStateException("Provider not found when updating credential: ${daoError.id}")
                        is LLMProviderError.ApiKeyAlreadyInUse -> throw IllegalStateException("API key already in use: ${daoError.apiKeyId}")
                    }
                }) {
                    llmProviderDao.updateProvider(updatedProvider).bind()
                }

                // Delete the old credential if it exists
                existingProvider.apiKeyId?.let { oldKeyId ->
                    credentialManager.deleteCredential(oldKeyId).getOrElse {
                        throw IllegalStateException("Failed to delete old credential for provider ID $providerId: ${it.alias}")
                    }
                }
            }
        }
}
