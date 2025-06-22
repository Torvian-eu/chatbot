package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.ProvidersResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.*
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for Provider API routes.
 *
 * This test suite verifies the HTTP endpoints for LLM provider management:
 * - GET /api/v1/providers - List all providers
 * - POST /api/v1/providers - Add new provider
 * - GET /api/v1/providers/{providerId} - Get provider by ID
 * - PUT /api/v1/providers/{providerId} - Update provider by ID
 * - DELETE /api/v1/providers/{providerId} - Delete provider by ID
 * - PUT /api/v1/providers/{providerId}/credential - Update provider credential
 * - GET /api/v1/providers/{providerId}/models - Get models for this provider
 */
class ProviderRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var providerTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testProvider1 = TestDefaults.llmProvider1
    private val testProvider2 = TestDefaults.llmProvider2
    private val testModel1 = TestDefaults.llmModel1
    private val testModel2 = TestDefaults.llmModel2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        providerTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureProviderRoutes(this)
            }
        )

        testDataManager = container.get()
        // Need ApiSecrets table for credentials, LLMProviders for providers, LLMModels for provider-in-use check
        testDataManager.createTables(setOf(Table.API_SECRETS, Table.LLM_PROVIDERS, Table.LLM_MODELS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // --- GET /api/v1/providers Tests ---

    @Test
    fun `GET providers should return list of providers successfully`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        testDataManager.insertLLMProvider(testProvider2)
        val expectedProviders = listOf(testProvider1, testProvider2)

        // Act & Assert
        val response = client.get(href(ProvidersResource()))
        assertEquals(HttpStatusCode.OK, response.status)
        val providers = response.body<List<LLMProvider>>()
        assertEquals(expectedProviders, providers)
    }

    @Test
    fun `GET providers should return empty list if no providers exist`() = providerTestApplication {
        // Arrange (no providers inserted)

        // Act & Assert
        val response = client.get(href(ProvidersResource()))
        assertEquals(HttpStatusCode.OK, response.status)
        val providers = response.body<List<LLMProvider>>()
        assertEquals(emptyList(), providers)
    }

    // --- POST /api/v1/providers Tests ---

    @Test
    fun `POST providers should add a new provider successfully with credential`() = providerTestApplication {
        // Arrange
        val newProviderName = "New Provider"
        val newProviderDescription = "A brand new provider"
        val newProviderBaseUrl = "https://new.provider.com/api"
        val newProviderType = LLMProviderType.OPENAI
        val newCredential = "sk-new-key"

        val createRequest = AddProviderRequest(
            name = newProviderName,
            description = newProviderDescription,
            baseUrl = newProviderBaseUrl,
            type = newProviderType,
            credential = newCredential
        )

        // Act
        val response = client.post(href(ProvidersResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdProvider = response.body<LLMProvider>()
        assertEquals(newProviderName, createdProvider.name)
        assertEquals(newProviderDescription, createdProvider.description)
        assertEquals(newProviderBaseUrl, createdProvider.baseUrl)
        assertEquals(newProviderType, createdProvider.type)
        assertNotNull(createdProvider.apiKeyId, "API Key ID should be assigned")

        // Verify the provider was actually created in the database
        val retrievedProvider = testDataManager.getLLMProvider(createdProvider.id)
        assertNotNull(retrievedProvider)
        assertEquals(createdProvider, retrievedProvider)

        // Verify the credential was stored (check for ApiSecretEntity with the assigned apiKeyId)
        val apiSecret = testDataManager.getApiSecret(createdProvider.apiKeyId!!)
        assertNotNull(apiSecret, "Credential should be stored as an ApiSecretEntity")
    }

    @Test
    fun `POST providers should add a new provider successfully without credential`() = providerTestApplication {
        // Arrange
        val newProviderName = "Another Provider"
        val newProviderDescription = "Provider without credential"
        val newProviderBaseUrl = "https://another.provider.com/api"
        val newProviderType = LLMProviderType.ANTHROPIC

        val createRequest = AddProviderRequest(
            name = newProviderName,
            description = newProviderDescription,
            baseUrl = newProviderBaseUrl,
            type = newProviderType,
            credential = null // No credential
        )

        // Act
        val response = client.post(href(ProvidersResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdProvider = response.body<LLMProvider>()
        assertEquals(newProviderName, createdProvider.name)
        assertEquals(newProviderDescription, createdProvider.description)
        assertEquals(newProviderBaseUrl, createdProvider.baseUrl)
        assertEquals(newProviderType, createdProvider.type)
        assertNull(createdProvider.apiKeyId, "API Key ID should be null")

        // Verify the provider was actually created in the database
        val retrievedProvider = testDataManager.getLLMProvider(createdProvider.id)
        assertNotNull(retrievedProvider)
        assertEquals(createdProvider, retrievedProvider)
    }

    @Test
    fun `POST providers should return 400 for blank name`() = providerTestApplication {
        // Arrange
        val createRequest = AddProviderRequest(
            name = "   ",
            description = "desc",
            baseUrl = "url",
            type = LLMProviderType.OPENAI,
            credential = null
        )

        // Act
        val response = client.post(href(ProvidersResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid provider input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Provider name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `POST providers should return 400 for blank base URL`() = providerTestApplication {
        // Arrange
        val createRequest = AddProviderRequest(
            name = "Name",
            description = "desc",
            baseUrl = "   ",
            type = LLMProviderType.OPENAI,
            credential = null
        )

        // Act
        val response = client.post(href(ProvidersResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid provider input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Provider base URL cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `POST providers should return 400 for blank credential when provided`() = providerTestApplication {
        // Arrange
        val createRequest = AddProviderRequest(
            name = "Name",
            description = "desc",
            baseUrl = "url",
            type = LLMProviderType.OPENAI,
            credential = "   " // Blank credential
        )

        // Act
        val response = client.post(href(ProvidersResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid provider input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Provider credential cannot be blank when provided.", error.details?.get("reason"))
    }

    // --- GET /api/v1/providers/{providerId} Tests ---

    @Test
    fun `GET provider by ID should return provider successfully`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)

        // Act
        val response = client.get(href(ProvidersResource.ById(providerId = testProvider1.id)))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val provider = response.body<LLMProvider>()
        assertEquals(testProvider1, provider)
    }

    @Test
    fun `GET provider with non-existent ID should return 404`() = providerTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.get(href(ProvidersResource.ById(providerId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Provider not found", error.message)
        assert(error.details?.containsKey("providerId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("providerId"))
    }

    // --- PUT /api/v1/providers/{providerId} Tests ---

    @Test
    fun `PUT provider should update provider successfully`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        val updatedProvider = testProvider1.copy(
            name = "Updated OpenAI",
            description = "Updated description",
            baseUrl = "https://updated.openai.com/v1"
            // apiKeyId and type should not be changed via this endpoint according to the service logic
        )

        // Act
        val response = client.put(href(ProvidersResource.ById(providerId = testProvider1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedProvider)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the provider was actually updated in the database
        val retrievedProvider = testDataManager.getLLMProvider(testProvider1.id)
        assertNotNull(retrievedProvider)
        // Check only the fields that are expected to be updated by this endpoint
        assertEquals(updatedProvider.name, retrievedProvider.name)
        assertEquals(updatedProvider.description, retrievedProvider.description)
        assertEquals(updatedProvider.baseUrl, retrievedProvider.baseUrl)
        // Ensure fields not meant to be updated remain the same
        assertEquals(testProvider1.apiKeyId, retrievedProvider.apiKeyId)
        assertEquals(testProvider1.type, retrievedProvider.type)
    }

    @Test
    fun `PUT provider with non-existent ID should return 404`() = providerTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updatedProvider = testProvider1.copy(id = nonExistentId) // Use a copy with the non-existent ID

        // Act
        val response = client.put(href(ProvidersResource.ById(providerId = nonExistentId))) {
            contentType(ContentType.Application.Json)
            setBody(updatedProvider)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Provider not found", error.message)
        assert(error.details?.containsKey("providerId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("providerId"))
    }

    @Test
    fun `PUT provider with mismatched ID should return 400`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        val mismatchedId = 999L
        val updatedProvider = testProvider1.copy(id = mismatchedId) // Body ID is different from path ID

        // Act
        val response = client.put(href(ProvidersResource.ById(providerId = testProvider1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedProvider)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Provider ID in path and body must match", error.message)
        assert(error.details?.containsKey("pathId") == true)
        assertEquals(testProvider1.id.toString(), error.details?.get("pathId"))
        assert(error.details?.containsKey("bodyId") == true)
        assertEquals(mismatchedId.toString(), error.details?.get("bodyId"))
    }

    @Test
    fun `PUT provider should return 400 for blank name`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        val updatedProvider = testProvider1.copy(name = "   ")

        // Act
        val response = client.put(href(ProvidersResource.ById(providerId = testProvider1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedProvider)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid provider input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Provider name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `PUT provider should return 400 for blank base URL`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        val updatedProvider = testProvider1.copy(baseUrl = "   ")

        // Act
        val response = client.put(href(ProvidersResource.ById(providerId = testProvider1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedProvider)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid provider input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Provider base URL cannot be blank.", error.details?.get("reason"))
    }

    // --- DELETE /api/v1/providers/{providerId} Tests ---

    @Test
    fun `DELETE provider should remove the provider successfully`() = providerTestApplication {
        // Arrange
        // Need to insert the API secret first because the provider references it
        testDataManager.insertApiSecret(TestDefaults.apiSecret1)
        testDataManager.insertLLMProvider(testProvider1)

        // Act
        val response = client.delete(href(ProvidersResource.ById(providerId = testProvider1.id)))

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the provider was actually deleted
        val retrievedProvider = testDataManager.getLLMProvider(testProvider1.id)
        assertNull(retrievedProvider)

        // Verify the associated credential was deleted
        val retrievedSecret = testDataManager.getApiSecret(testProvider1.apiKeyId!!)
        assertNull(retrievedSecret, "Associated API secret should be deleted")
    }

    @Test
    fun `DELETE provider with non-existent ID should return 404`() = providerTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.delete(href(ProvidersResource.ById(providerId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Provider not found", error.message)
        assert(error.details?.containsKey("providerId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("providerId"))
    }

    @Test
    fun `DELETE provider in use by models should return 409 Conflict`() = providerTestApplication {
        // Arrange
        // Need to insert provider and model that uses it
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1) // testModel1 uses testProvider1
            )
        )

        // Act
        val response = client.delete(href(ProvidersResource.ById(providerId = testProvider1.id)))

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status) // 409 Conflict
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.RESOURCE_IN_USE.code, error.code)
        assertEquals(409, error.statusCode)
        assertEquals("Provider is still in use by models", error.message)
        assert(error.details?.containsKey("providerId") == true)
        assertEquals(testProvider1.id.toString(), error.details?.get("providerId"))
        assert(error.details?.containsKey("modelNames") == true)
        assertEquals(testModel1.name, error.details?.get("modelNames")) // Check if model name is included
    }

    // --- PUT /api/v1/providers/{providerId}/credential Tests ---

    @Test
    fun `PUT provider credential should update credential successfully`() = providerTestApplication {
        // Arrange
        // Insert provider with an existing credential
        testDataManager.setup(
            TestDataSet(
                apiSecrets = listOf(TestDefaults.apiSecret1),
                llmProviders = listOf(testProvider1) // testProvider1 uses apiSecret1
            )
        )
        val newCredentialValue = "sk-new-secret-key"
        val updateRequest = UpdateProviderCredentialRequest(credential = newCredentialValue)

        // Act
        val response =
            client.put(href(ProvidersResource.ById.Credential(ProvidersResource.ById(providerId = testProvider1.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
            }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the provider's apiKeyId was updated
        val retrievedProvider = testDataManager.getLLMProvider(testProvider1.id)
        assertNotNull(retrievedProvider)
        assertNotNull(retrievedProvider.apiKeyId)
        assert(retrievedProvider.apiKeyId != testProvider1.apiKeyId) // Should have a new alias

        // Verify the new credential was stored
        val newApiSecret = testDataManager.getApiSecret(retrievedProvider.apiKeyId!!)
        assertNotNull(newApiSecret, "New credential should be stored")

        // Verify the old credential was deleted
        val oldApiSecret = testDataManager.getApiSecret(testProvider1.apiKeyId!!)
        assertNull(oldApiSecret, "Old credential should be deleted")
    }

    @Test
    fun `PUT provider credential should remove credential successfully when null is provided`() =
        providerTestApplication {
            // Arrange
            // Insert provider with an existing credential
            testDataManager.setup(
                TestDataSet(
                    apiSecrets = listOf(TestDefaults.apiSecret1),
                    llmProviders = listOf(testProvider1) // testProvider1 uses apiSecret1
                )
            )
            val updateRequest = UpdateProviderCredentialRequest(credential = null) // Remove credential

            // Act
            val response =
                client.put(href(ProvidersResource.ById.Credential(ProvidersResource.ById(providerId = testProvider1.id)))) {
                    contentType(ContentType.Application.Json)
                    setBody(updateRequest)
                }

            // Assert
            assertEquals(HttpStatusCode.OK, response.status)

            // Verify the provider's apiKeyId is now null
            val retrievedProvider = testDataManager.getLLMProvider(testProvider1.id)
            assertNotNull(retrievedProvider)
            assertNull(retrievedProvider.apiKeyId, "API Key ID should be null after removing credential")

            // Verify the old credential was deleted
            val oldApiSecret = testDataManager.getApiSecret(testProvider1.apiKeyId!!)
            assertNull(oldApiSecret, "Old credential should be deleted")
        }

    @Test
    fun `PUT provider credential with non-existent provider ID should return 404`() = providerTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updateRequest = UpdateProviderCredentialRequest(credential = "sk-new-key")

        // Act
        val response =
            client.put(href(ProvidersResource.ById.Credential(ProvidersResource.ById(providerId = nonExistentId)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
            }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Provider not found", error.message)
        assert(error.details?.containsKey("providerId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("providerId"))
    }

    @Test
    fun `PUT provider credential should return 400 for blank credential when provided`() = providerTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        val updateRequest = UpdateProviderCredentialRequest(credential = "   ") // Blank credential

        // Act
        val response =
            client.put(href(ProvidersResource.ById.Credential(ProvidersResource.ById(providerId = testProvider1.id)))) {
                contentType(ContentType.Application.Json)
                setBody(updateRequest)
            }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid credential input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Provider credential cannot be blank.", error.details?.get("reason"))
    }

    // --- GET /api/v1/providers/{providerId}/models Tests ---

    @Test
    fun `GET provider models should return list of models successfully`() = providerTestApplication {
        // Arrange
        // Insert providers and models, ensuring some models belong to the target provider
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2),
                llmModels = listOf(
                    testModel1,
                    testModel2
                ) // testModel1 uses testProvider1, testModel2 uses testProvider2
            )
        )
        val expectedModels = listOf(testModel1) // Only models for testProvider1

        // Act
        val response =
            client.get(href(ProvidersResource.ById.Models(ProvidersResource.ById(providerId = testProvider1.id))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<LLMModel>>()
        assertEquals(expectedModels, models)
    }

    @Test
    fun `GET provider models should return empty list if provider has no models`() = providerTestApplication {
        // Arrange
        // Insert provider but no models for it
        testDataManager.insertLLMProvider(testProvider1)

        // Act
        val response =
            client.get(href(ProvidersResource.ById.Models(ProvidersResource.ById(providerId = testProvider1.id))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<LLMModel>>()
        assertEquals(emptyList(), models)
    }

    @Test
    fun `GET provider models for non-existent provider ID should return empty list`() = providerTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        // Note: The service layer currently returns an empty list if the provider doesn't exist,
        // rather than a Not Found error. The test reflects this current behavior.
        val response =
            client.get(href(ProvidersResource.ById.Models(ProvidersResource.ById(providerId = nonExistentId))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status) // Expect OK with empty list based on service impl
        val models = response.body<List<LLMModel>>()
        assertEquals(emptyList(), models)
    }
}