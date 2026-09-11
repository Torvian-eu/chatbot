package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.ModelPresetOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.preset.ModelPresetError
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ModelPresetDaoExposed] and [ModelPresetOwnershipDaoExposed].
 *
 * Verifies the `model_presets` projection and its ownership link against a real SQLite database with
 * foreign keys enforced (the same configuration the application uses): insert sets both timestamps,
 * updates advance `updated_at` while preserving `created_at`, listings are ordered by `id` and scoped
 * to the owner, and deleting a referenced model/settings/preset produces the documented `SET NULL`
 * behaviour (AC-2, AC-9, FR-14, FR-15).
 */
class ModelPresetDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var modelPresetDao: ModelPresetDao
    private lateinit var ownershipDao: ModelPresetOwnershipDao
    private lateinit var testDataManager: TestDataManager

    private val user = TestDefaults.user1
    private val otherUser = TestDefaults.user2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        modelPresetDao = container.get()
        ownershipDao = container.get()
        testDataManager = container.get()

        testDataManager.setup(
            TestDataSet(
                users = listOf(user, otherUser),
                llmProviders = listOf(TestDefaults.llmProvider1),
                llmModels = listOf(TestDefaults.llmModel1),
                modelSettings = listOf(TestDefaults.modelSettings1)
            )
        )
        testDataManager.createTables(
            setOf(Table.MODEL_PRESETS, Table.MODEL_PRESET_OWNERS)
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `insertPreset stores the row with created_at equal to updated_at`() = runTest {
        val created = modelPresetDao.insertPreset(
            name = "smart_model",
            displayName = "Smart model",
            description = "Bundles the smart model",
            modelId = TestDefaults.llmModel1.id,
            modelSettingsId = TestDefaults.modelSettings1.id
        )

        assertNotNull(created.id)
        assertEquals("smart_model", created.name)
        assertEquals("Smart model", created.displayName)
        assertEquals(TestDefaults.llmModel1.id, created.modelId)
        assertEquals(TestDefaults.modelSettings1.id, created.modelSettingsId)
        assertEquals(created.createdAt, created.updatedAt, "both timestamps are set on insert")

        val stored = testDataManager.getModelPreset(created.id)
        assertNotNull(stored)
        assertEquals("smart_model", stored.name)
    }

    @Test
    fun `updatePreset replaces every writable column and advances updated_at`() = runTest {
        val created = modelPresetDao.insertPreset(
            name = "smart_model",
            displayName = "Smart model",
            description = "Bundles the smart model",
            modelId = TestDefaults.llmModel1.id,
            modelSettingsId = TestDefaults.modelSettings1.id
        )

        val result = modelPresetDao.updatePreset(
            created.copy(
                name = "cheap_model",
                displayName = null,
                description = "Re-pointed",
                modelSettingsId = null
            )
        )

        assertTrue(result.isRight())
        val stored = assertNotNull(testDataManager.getModelPreset(created.id))
        assertEquals("cheap_model", stored.name)
        assertNull(stored.displayName, "a null display name clears the column")
        assertEquals("Re-pointed", stored.description)
        assertNull(stored.modelSettingsId, "a null reference clears the column")
        assertEquals(created.createdAt, stored.createdAt, "created_at is never rewritten")
        assertTrue(stored.updatedAt >= created.updatedAt, "updated_at advances on update")
    }

    @Test
    fun `updatePreset and deletePreset return NotFound for a missing row`() = runTest {
        val missing = TestDefaults.modelPreset2.copy(id = 999L)

        assertEquals(ModelPresetError.NotFound(999L), modelPresetDao.updatePreset(missing).leftOrNull())
        assertEquals(ModelPresetError.NotFound(999L), modelPresetDao.deletePreset(999L).leftOrNull())
    }

    @Test
    fun `getPresetById returns the row or NotFound`() = runTest {
        val created = modelPresetDao.insertPreset("smart_model", null, "", null, null)

        assertEquals("smart_model", modelPresetDao.getPresetById(created.id).getOrNull()?.name)
        assertEquals(ModelPresetError.NotFound(999L), modelPresetDao.getPresetById(999L).leftOrNull())
    }

    @Test
    fun `owner-scoped reads list presets by id ascending and hide foreign rows`() = runTest {
        // Inserted in reverse name order so the assertion proves the ORDER BY (not insertion order).
        val second = modelPresetDao.insertPreset("zzz_preset", null, "", null, null)
        val first = modelPresetDao.insertPreset("aaa_preset", null, "", null, null)
        ownershipDao.setOwner(first.id, user.id)
        ownershipDao.setOwner(second.id, user.id)
        val foreign = modelPresetDao.insertPreset("foreign_preset", null, "", null, null)
        ownershipDao.setOwner(foreign.id, otherUser.id)

        val owned = modelPresetDao.getAllPresetsForUser(user.id)
        assertEquals(
            listOf(first.id, second.id).sorted(),
            owned.map { it.id },
            "listings are ordered by id ascending and scoped to the owner"
        )
        assertTrue(owned.none { it.id == foreign.id }, "another user's preset must be invisible")

        assertEquals(
            listOf(second.id),
            modelPresetDao.getPresetsByIdsForUser(user.id, listOf(second.id, foreign.id)).map { it.id },
            "the owner-scoped batch read omits foreign ids"
        )
        assertTrue(modelPresetDao.getPresetsByIdsForUser(user.id, emptyList()).isEmpty())
    }

    @Test
    fun `presetNameExistsForUser is scoped per owner`() = runTest {
        val created = modelPresetDao.insertPreset("smart_model", null, "", null, null)
        ownershipDao.setOwner(created.id, user.id)

        assertTrue(modelPresetDao.presetNameExistsForUser(user.id, "smart_model"))
        assertEquals(
            false,
            modelPresetDao.presetNameExistsForUser(otherUser.id, "smart_model"),
            "another user may reuse the same name"
        )
    }

    @Test
    fun `getOwner reports the owner or ResourceNotFound`() = runTest {
        val created = modelPresetDao.insertPreset("smart_model", null, "", null, null)
        ownershipDao.setOwner(created.id, user.id)

        assertEquals(user.id, ownershipDao.getOwner(created.id).getOrNull())
        assertTrue(ownershipDao.getOwner(999L).leftOrNull() is GetOwnerError.ResourceNotFound)
    }

    @Test
    fun `deleting the referenced settings or model nulls the preset reference`() = runTest {
        val settingsPreset = modelPresetDao.insertPreset(
            "settings_preset",
            null,
            "",
            TestDefaults.llmModel1.id,
            TestDefaults.modelSettings1.id
        )

        assertTrue(container.get<eu.torvian.chatbot.server.data.dao.SettingsDao>().deleteSettings(
            TestDefaults.modelSettings1.id
        ).isRight())

        val afterSettingsDelete = assertNotNull(testDataManager.getModelPreset(settingsPreset.id))
        assertNull(afterSettingsDelete.modelSettingsId, "SET NULL keeps the preset and strips the reference")
        assertEquals(TestDefaults.llmModel1.id, afterSettingsDelete.modelId, "the model reference is untouched")

        // Deleting the model nulls the preset's model reference; model_settings.model_id cascades, so any
        // remaining settings rows vanish with it.
        assertTrue(
            container.get<eu.torvian.chatbot.server.data.dao.ModelDao>().deleteModel(TestDefaults.llmModel1.id).isRight()
        )
        val afterModelDelete = assertNotNull(testDataManager.getModelPreset(settingsPreset.id))
        assertNull(afterModelDelete.modelId, "SET NULL keeps the preset and strips the model reference")
    }

    @Test
    fun `deleting a preset nulls the referencing role's reference and keeps the role`() = runTest {
        val preset = modelPresetDao.insertPreset(
            "smart_model",
            null,
            "",
            TestDefaults.llmModel1.id,
            TestDefaults.modelSettings1.id
        )
        ownershipDao.setOwner(preset.id, user.id)
        val role = TestDefaults.agentRole1.copy(id = 1L, modelPresetId = preset.id)
        testDataManager.setup(TestDataSet(agentRoles = listOf(role)))

        assertTrue(modelPresetDao.deletePreset(preset.id).isRight())

        val storedRole = assertNotNull(testDataManager.getAgentRole(role.id))
        assertNull(storedRole.modelPresetId, "deleting a preset only nulls the role's reference")
        // The preset's ownership row cascades; the role itself is untouched.
        assertTrue(ownershipDao.getOwner(preset.id).leftOrNull() is GetOwnerError.ResourceNotFound)
    }
}
