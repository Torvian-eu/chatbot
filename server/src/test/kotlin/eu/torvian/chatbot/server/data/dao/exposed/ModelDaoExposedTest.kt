package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [ModelDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ModelDao]:
 * - Getting all models
 * - Getting a model by ID
 * - Getting models by provider ID
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
    private val testProvider1 = TestDefaults.llmProvider1
    private val testProvider2 = TestDefaults.llmProvider2
    private val testModel1 = TestDefaults.llmModel1
    private val testModel2 = TestDefaults.llmModel2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        modelDao = container.get()
        testDataManager = container.get()

        // Ensure all tables required by access/ownership logic are created for these tests
        testDataManager.createTables(
            setOf(
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.LLM_MODEL_ACCESS,
                Table.LLM_MODEL_OWNERS,
                Table.USER_GROUPS,
                Table.USER_GROUP_MEMBERSHIPS,
                Table.USERS
            )
        )
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
                llmProviders = listOf(testProvider1, testProvider2),
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
        assertEquals(testModel1.providerId, model1.providerId)
        assertEquals(testModel1.active, model1.active)
        assertEquals(testModel1.displayName, model1.displayName)
    }

    @Test
    fun `getModelById should return model when it exists`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
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
        assertEquals(testModel1.providerId, model.providerId, "Expected matching providerId")
        assertEquals(testModel1.active, model.active, "Expected matching active")
        assertEquals(testModel1.displayName, model.displayName, "Expected matching displayName")
    }

    @Test
    fun `getModelById should return ModelNotFound when model does not exist`() = runTest {
        // Get a non-existent model
        val result = modelDao.getModelById(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `getModelsByProviderId should return models when they exist`() = runTest {
        // Setup test data
        val testModel3 = testModel1.copy(id = 3L, name = "model3")
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2),
                llmModels = listOf(testModel1, testModel3) // Both use provider1
            )
        )

        // Get models by provider ID
        val models = modelDao.getModelsByProviderId(testProvider1.id)

        // Verify
        assertEquals(2, models.size, "Expected 2 models for provider ${testProvider1.id}")
        assertTrue(models.any { it.id == testModel1.id }, "Expected to find model1")
        assertTrue(models.any { it.id == testModel3.id }, "Expected to find model3")
        assertTrue(models.all { it.providerId == testProvider1.id }, "All models should belong to provider1")
    }

    @Test
    fun `getModelsByProviderId should return empty list when no models exist for provider`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2),
                llmModels = listOf(testModel1) // Only model1 uses provider1
            )
        )

        // Get models for provider2 (which has no models)
        val models = modelDao.getModelsByProviderId(testProvider2.id)

        // Verify
        assertTrue(models.isEmpty(), "Expected empty list for provider with no models")
    }

    @Test
    fun `insertModel should insert a new model`() = runTest {
        // Setup provider first
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1)
            )
        )

        // Insert a new model
        val result = modelDao.insertModel(
            name = testModel1.name,
            providerId = testModel1.providerId,
            type = testModel1.type,
            active = testModel1.active,
            displayName = testModel1.displayName,
            capabilities = testModel1.capabilities
        )

        // Verify
        assertTrue(result.isRight(), "Expected Right result for successful insert")
        val model = result.getOrNull()
        assertNotNull(model, "Expected non-null model")
        assertEquals(testModel1.name, model.name, "Expected matching name")
        assertEquals(testModel1.providerId, model.providerId, "Expected matching providerId")
        assertEquals(testModel1.type, model.type, "Expected matching type")
        assertEquals(testModel1.active, model.active, "Expected matching active")
        assertEquals(testModel1.displayName, model.displayName, "Expected matching displayName")
        assertEquals(testModel1.capabilities, model.capabilities, "Expected matching capabilities")
        assertTrue(model.id > 0, "Expected positive ID")

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
                llmProviders = listOf(testProvider1, testProvider2),
                llmModels = listOf(testModel1)
            )
        )

        // Update the model
        val updatedModel = testModel1.copy(
            name = "updated-model-name",
            providerId = testProvider2.id, // Change to different provider
            active = false,
            displayName = "Updated Model Display Name"
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
        assertEquals(updatedModel.providerId, retrievedModel.providerId, "Expected updated providerId")
        assertEquals(updatedModel.type, retrievedModel.type, "Expected updated type")
        assertEquals(updatedModel.active, retrievedModel.active, "Expected updated active")
        assertEquals(updatedModel.displayName, retrievedModel.displayName, "Expected updated displayName")
        assertEquals(updatedModel.capabilities, retrievedModel.capabilities, "Expected updated capabilities")
    }

    @Test
    fun `updateModel should return ModelNotFound when model does not exist`() = runTest {
        // Setup provider first
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1)
            )
        )

        // Try to update a non-existent model
        val nonExistentModel = LLMModel(
            id = 999,
            name = "non-existent-model",
            providerId = testProvider1.id,
            active = true,
            displayName = "Non-existent Model",
            type = LLMModelType.CHAT
        )

        val result = modelDao.updateModel(nonExistentModel)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertIs<UpdateModelError.ModelNotFound>(error, "Expected UpdateModelError.ModelNotFound error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `deleteModel should delete an existing model`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
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
    }

    @Test
    fun `deleteModel should return ModelNotFound when model does not exist`() = runTest {
        // Try to delete a non-existent model
        val result = modelDao.deleteModel(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertEquals(999, error.id, "Expected error with correct ID")
    }

    @Test
    fun `getAllAccessibleModels should return models accessible via group membership for requested access mode`() =
        runTest {
            // Setup providers, models and user
            testDataManager.setup(
                TestDataSet(
                    llmProviders = listOf(testProvider1, testProvider2),
                    llmModels = listOf(testModel1, testModel2),
                    users = listOf(TestDefaults.user1)
                )
            )

            val user = TestDefaults.user1
            val userGroup = TestDefaults.userGroup1

            // Create user group and membership, then grant read access to model1
            testDataManager.insertUserGroup(userGroup)
            testDataManager.insertUserGroupMembership(user.id, userGroup.id)
            testDataManager.insertModelAccess(
                testModel1.id,
                userGroup.id,
                AccessMode.READ
            )

            // Query accessible models for READ
            val accessibleRead = modelDao.getAllAccessibleModels(user.id, AccessMode.READ)

            assertTrue(
                accessibleRead.any { it.id == testModel1.id },
                "Expected model1 to be accessible via group read access"
            )
            assertTrue(accessibleRead.none { it.id == testModel2.id }, "Expected model2 to NOT be accessible")
        }

    @Test
    fun `getAllAccessibleModels should return models owned by the user regardless of access mode`() = runTest {
        // Setup provider, model and user
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider2),
                llmModels = listOf(testModel2),
                users = listOf(TestDefaults.user1)
            )
        )

        val user = TestDefaults.user1

        // Make user the owner of model2
        testDataManager.insertModelOwnership(testModel2.id, user.id)

        // Query accessible models for WRITE (owner should still see it)
        val accessibleWrite = modelDao.getAllAccessibleModels(user.id, AccessMode.WRITE)

        assertTrue(
            accessibleWrite.any { it.id == testModel2.id },
            "Expected owned model to be accessible for WRITE mode"
        )
    }

    @Test
    fun `accessibleModelsByProviderId should respect access mode and ownership`() = runTest {
        // Setup provider, model and user
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1),
                users = listOf(TestDefaults.user1)
            )
        )

        val user = TestDefaults.user1
        val userGroup = TestDefaults.userGroup1

        // Create user group and membership, grant WRITE access only
        testDataManager.insertUserGroup(userGroup)
        testDataManager.insertUserGroupMembership(user.id, userGroup.id)
        testDataManager.insertModelAccess(testModel1.id, userGroup.id, AccessMode.WRITE)

        // Query for READ should NOT return the model
        val accessibleRead = modelDao.getAccessibleModelsByProviderId(
            user.id,
            testProvider1.id,
            AccessMode.READ
        )
        assertTrue(
            accessibleRead.none { it.id == testModel1.id },
            "Expected model not accessible for READ when only WRITE granted"
        )

        // Query for WRITE should return the model
        val accessibleWrite = modelDao.getAccessibleModelsByProviderId(
            user.id,
            testProvider1.id,
            AccessMode.WRITE
        )
        assertTrue(
            accessibleWrite.any { it.id == testModel1.id },
            "Expected model accessible for WRITE when WRITE granted"
        )

        // Now give ownership to user and ensure owner sees it regardless of mode
        testDataManager.insertModelOwnership(testModel1.id, user.id)
        val accessibleReadAfterOwnership = modelDao.getAccessibleModelsByProviderId(
            user.id,
            testProvider1.id,
            AccessMode.READ
        )
        assertTrue(
            accessibleReadAfterOwnership.any { it.id == testModel1.id },
            "Expected owned model to be accessible for READ after ownership granted"
        )
    }
}
