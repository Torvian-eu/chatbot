package eu.torvian.chatbot.server.data.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests for [eu.torvian.chatbot.server.data.dao.exposed.ModelDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ModelDao]:
 * - Getting all models
 * - Getting a model by ID
 * - Getting a model by API key ID
 * - Inserting a new model
 * - Updating an existing model
 * - Deleting a model
 * - Handling error cases (model not found)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ModelDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var modelDao: ModelDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testModel1 = TestDefaults.llmModel1
    private val testModel2 = TestDefaults.llmModel2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        modelDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.LLM_MODELS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllModels should return empty list when no models exist`() = runTest {
        val models = modelDao.getAllModels()
        assertTrue(models.isEmpty(), "Expected empty list when no models exist")
    }

    @Test
    fun `getAllModels should return all models when models exist`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1, testModel2)
            )
        )

        // Get all models
        val models = modelDao.getAllModels()

        // Verify
        assertEquals(2, models.size, "Expected 2 models")
        assertTrue(models.any { it.id == testModel1.id }, "Expected to find model with ID ${testModel1.id}")
        assertTrue(models.any { it.id == testModel2.id }, "Expected to find model with ID ${testModel2.id}")

        // Verify model properties
        val model1 = models.find { it.id == testModel1.id }
        assertNotNull(model1, "Expected to find model1")
        assertEquals(testModel1.name, model1.name)
        assertEquals(testModel1.baseUrl, model1.baseUrl)
        assertEquals(testModel1.type, model1.type)
        assertEquals(testModel1.apiKeyId, model1.apiKeyId)
    }

    @Test
    fun `getModelById should return model when it exists`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1)
            )
        )

        // Get the model by ID
        val result = modelDao.getModelById(testModel1.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing model")
        val model = result.getOrNull()
        assertNotNull(model, "Expected non-null model")
        assertEquals(testModel1.id, model.id, "Expected matching ID")
        assertEquals(testModel1.name, model.name, "Expected matching name")
        assertEquals(testModel1.baseUrl, model.baseUrl, "Expected matching baseUrl")
        assertEquals(testModel1.type, model.type, "Expected matching type")
        assertEquals(testModel1.apiKeyId, model.apiKeyId, "Expected matching apiKeyId")
    }

    @Test
    fun `getModelById should return ModelNotFound when model does not exist`() = runTest {
        // Get a non-existent model
        val result = modelDao.getModelById(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is ModelError.ModelNotFound, "Expected ModelNotFound error")
        assertEquals(999, (error as ModelError.ModelNotFound).id, "Expected error with correct ID")
    }

    @Test
    fun `getModelByApiKeyId should return model when it exists`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1, testModel2)
            )
        )

        // Get the model by API key ID
        val model = modelDao.getModelByApiKeyId(testModel1.apiKeyId!!)

        // Verify
        assertNotNull(model, "Expected non-null model for existing API key ID")
        assertEquals(testModel1.id, model.id, "Expected matching ID")
        assertEquals(testModel1.name, model.name, "Expected matching name")
        assertEquals(testModel1.baseUrl, model.baseUrl, "Expected matching baseUrl")
        assertEquals(testModel1.type, model.type, "Expected matching type")
        assertEquals(testModel1.apiKeyId, model.apiKeyId, "Expected matching apiKeyId")
    }

    @Test
    fun `getModelByApiKeyId should return null when no model has that API key ID`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1, testModel2)
            )
        )

        // Get a model with non-existent API key ID
        val model = modelDao.getModelByApiKeyId("non-existent-key-id")

        // Verify
        assertNull(model, "Expected null for non-existent API key ID")
    }

    @Test
    fun `insertModel should insert a new model`() = runTest {
        // Insert a new model
        val model = modelDao.insertModel(
            name = testModel1.name,
            baseUrl = testModel1.baseUrl,
            type = testModel1.type,
            apiKeyId = testModel1.apiKeyId
        )

        // Verify
        assertNotNull(model, "Expected non-null model")
        assertEquals(testModel1.name, model.name, "Expected matching name")
        assertEquals(testModel1.baseUrl, model.baseUrl, "Expected matching baseUrl")
        assertEquals(testModel1.type, model.type, "Expected matching type")
        assertEquals(testModel1.apiKeyId, model.apiKeyId, "Expected matching apiKeyId")
        assertNotNull(model.id, "Expected non-null ID")

        // Verify model was actually inserted in the database
        val retrievedModel = modelDao.getModelById(model.id)
        assertTrue(retrievedModel.isRight(), "Expected to find the newly inserted model")
        assertEquals(model, retrievedModel.getOrNull(), "Expected retrieved model to match inserted model")
    }

    @Test
    fun `updateModel should update an existing model`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1)
            )
        )

        // Update the model
        val updatedModel = testModel1.copy(
            name = "Updated Model Name",
            baseUrl = "https://updated-url.example.com",
            type = "updated-type",
            apiKeyId = "updated-key-id"
        )

        val result = modelDao.updateModel(updatedModel)

        // Verify update was successful
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the model was actually updated
        val retrievedResult = modelDao.getModelById(testModel1.id)
        assertTrue(retrievedResult.isRight(), "Expected to find the updated model")
        val retrievedModel = retrievedResult.getOrNull()
        assertNotNull(retrievedModel, "Expected non-null model")
        assertEquals(updatedModel.name, retrievedModel.name, "Expected updated name")
        assertEquals(updatedModel.baseUrl, retrievedModel.baseUrl, "Expected updated baseUrl")
        assertEquals(updatedModel.type, retrievedModel.type, "Expected updated type")
        assertEquals(updatedModel.apiKeyId, retrievedModel.apiKeyId, "Expected updated apiKeyId")
    }

    @Test
    fun `updateModel should return ModelNotFound when model does not exist`() = runTest {
        // Try to update a non-existent model
        val nonExistentModel = LLMModel(
            id = 999,
            name = "Non-existent Model",
            baseUrl = "https://non-existent.example.com",
            type = "non-existent",
            apiKeyId = "non-existent-key"
        )

        val result = modelDao.updateModel(nonExistentModel)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is ModelError.ModelNotFound, "Expected ModelNotFound error")
        assertEquals(999, (error as ModelError.ModelNotFound).id, "Expected error with correct ID")
    }

    @Test
    fun `deleteModel should delete an existing model`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1)
            )
        )

        // Delete the model
        val result = modelDao.deleteModel(testModel1.id)

        // Verify deletion was successful
        assertTrue(result.isRight(), "Expected Right result for successful deletion")

        // Verify the model was actually deleted
        val retrievedResult = modelDao.getModelById(testModel1.id)
        assertTrue(retrievedResult.isLeft(), "Expected Left result for deleted model")
        val error = retrievedResult.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is ModelError.ModelNotFound, "Expected ModelNotFound error")
    }

    @Test
    fun `deleteModel should return ModelNotFound when model does not exist`() = runTest {
        // Try to delete a non-existent model
        val result = modelDao.deleteModel(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is ModelError.ModelNotFound, "Expected ModelNotFound error")
        assertEquals(999, (error as ModelError.ModelNotFound).id, "Expected error with correct ID")
    }
}
