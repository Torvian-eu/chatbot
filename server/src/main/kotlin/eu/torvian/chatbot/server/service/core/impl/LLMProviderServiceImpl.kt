package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.*
import arrow.core.raise.*
import arrow.core.raise.ensure
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.LLMProviderType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.server.utils.transactions.TransactionScope

/**
 * Implementation of the [LLMProviderService] interface.
 */
class LLMProviderServiceImpl(
    private val llmProviderDao: LLMProviderDao,
    private val modelDao: ModelDao,
    private val credentialManager: CredentialManager,
    private val transactionScope: TransactionScope,
) : LLMProviderService {

    override suspend fun getAllProviders(): List<LLMProvider> {
        return transactionScope.transaction {
            llmProviderDao.getAllProviders()
        }
    }

    override suspend fun getProviderById(id: Long): Either<GetProviderError.ProviderNotFound, LLMProvider> =
        transactionScope.transaction {
            either {
                withError({ daoError: LLMProviderError.LLMProviderNotFound ->
                    GetProviderError.ProviderNotFound(daoError.id)
                }) {
                    llmProviderDao.getProviderById(id).bind()
                }
            }
        }

    override suspend fun addProvider(name: String, description: String, baseUrl: String, type: LLMProviderType, credential: String?): Either<AddProviderError, LLMProvider> =
        transactionScope.transaction {
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
                withError({ daoError: LLMProviderError.ApiKeyAlreadyInUse ->
                    throw IllegalStateException("API key already in use: ${daoError.apiKeyId}")
                }) {
                    llmProviderDao.insertProvider(alias, name, description, baseUrl, type).bind()
                }
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

    override suspend fun updateProviderCredential(providerId: Long, newCredential: String?): Either<UpdateProviderCredentialError, Unit> =
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
