package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.ModelPresetDao
import eu.torvian.chatbot.server.data.dao.ModelPresetOwnershipDao
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.dao.error.preset.ModelPresetError as ModelPresetDaoError
import eu.torvian.chatbot.server.data.entities.ModelPresetEntity
import eu.torvian.chatbot.server.service.core.error.preset.CreateModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.DeleteModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.ModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.UpdateModelPresetError
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ModelPresetServiceImpl].
 *
 * Verifies the owner-scoped CRUD behaviour, the name rules (trimmed, non-blank, ≤255, per-owner
 * uniqueness with self-exclusion on rename), the reference validation (existence **and** `READ`
 * accessibility, the model↔settings agreement, no `LLMModelType` restriction), the ownership collapse
 * (a foreign preset is reported as not found), the "nothing is persisted on validation failure"
 * guarantee, and the server-managed timestamps surfaced on the DTO.
 */
class ModelPresetServiceImplTest {

    private lateinit var modelPresetDao: ModelPresetDao
    private lateinit var modelPresetOwnershipDao: ModelPresetOwnershipDao
    private lateinit var modelDao: ModelDao
    private lateinit var settingsDao: SettingsDao
    private lateinit var transactionScope: TransactionScope
    private lateinit var service: ModelPresetServiceImpl

    private val userId = 7L
    private val otherUserId = 8L

    private val model = TestDefaults.llmModel1
    private val model2 = TestDefaults.llmModel2
    private val chatSettings = TestDefaults.modelSettings1
    private val chatSettings2 = TestDefaults.modelSettings2

    /** The row a read path returns for `getPresetsByIdsForUser`. */
    private val existingPreset = ModelPresetEntity(
        id = 1L,
        name = "smart_model",
        displayName = "Smart model",
        description = "Bundles the smart model",
        modelId = model.id,
        modelSettingsId = chatSettings.id,
        createdAt = Instant.fromEpochMilliseconds(1_000L),
        updatedAt = Instant.fromEpochMilliseconds(1_000L)
    )

    @BeforeEach
    fun setUp() {
        modelPresetDao = mockk()
        modelPresetOwnershipDao = mockk()
        modelDao = mockk()
        settingsDao = mockk()
        transactionScope = mockk()

        service = ModelPresetServiceImpl(
            modelPresetDao = modelPresetDao,
            modelPresetOwnershipDao = modelPresetOwnershipDao,
            modelDao = modelDao,
            settingsDao = settingsDao,
            transactionScope = transactionScope
        )

        // The preset being managed is owned by the requesting user (owner-scoped read) and the
        // referenced model/settings are READ-accessible to them.
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(existingPreset.id)) } returns
            listOf(existingPreset)
        coEvery { modelDao.getAllAccessibleModels(userId, AccessMode.READ) } returns listOf(model, model2)
        coEvery { settingsDao.getAllAccessibleSettings(userId, AccessMode.READ) } returns
            listOf(chatSettings, chatSettings2)
        coEvery { modelPresetDao.presetNameExistsForUser(any(), any()) } returns false
        coEvery { modelPresetDao.insertPreset(any(), any(), any(), any(), any()) } returns existingPreset
        coEvery { modelPresetOwnershipDao.setOwner(any(), any()) } returns Unit.right()
        coEvery { modelPresetDao.updatePreset(any()) } returns Unit.right()
        coEvery { modelPresetDao.deletePreset(any()) } returns Unit.right()

        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
        coEvery { transactionScope.transaction(any<suspend () -> Any?>()) } coAnswers {
            val block = firstArg<suspend () -> Any?>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        clearMocks(
            modelPresetDao,
            modelPresetOwnershipDao,
            modelDao,
            settingsDao,
            transactionScope
        )
    }

    // --- Reads ---

    @Test
    fun `getAllPresetsForUser maps the rows to DTOs with their timestamps`() = runTest {
        coEvery { modelPresetDao.getAllPresetsForUser(userId) } returns listOf(existingPreset)

        val presets = service.getAllPresetsForUser(userId)

        assertEquals(1, presets.size)
        val dto = presets.single()
        assertEquals(existingPreset.id, dto.id)
        assertEquals("smart_model", dto.name)
        assertEquals("Smart model", dto.displayName)
        assertEquals("Bundles the smart model", dto.description)
        assertEquals(model.id, dto.modelId)
        assertEquals(chatSettings.id, dto.modelSettingsId)
        // The epoch-millis columns are exposed as Instants (OQ-2).
        assertEquals(existingPreset.createdAt, dto.createdAt)
        assertEquals(existingPreset.updatedAt, dto.updatedAt)
    }

    @Test
    fun `getPresetById returns the owned preset or collapses a foreign id to NotFound`() = runTest {
        assertEquals(existingPreset.id, service.getPresetById(userId, existingPreset.id).getOrNull()?.id)

        // The owner-scoped read omits a foreign preset, which must not leak its existence.
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(99L)) } returns emptyList()

        val error = assertIs<ModelPresetError.NotFound>(service.getPresetById(userId, 99L).leftOrNull())
        assertEquals(99L, error.id)
    }

    // --- Create ---

    @Test
    fun `createPreset persists the row with its ownership link and returns the DTO`() = runTest {
        val result = service.createPreset(
            userId,
            CreateModelPresetRequest(
                name = "smart_model",
                displayName = "Smart model",
                description = "Bundles the smart model",
                modelId = model.id,
                modelSettingsId = chatSettings.id
            )
        )

        assertTrue(result.isRight())
        val dto = assertNotNull(result.getOrNull())
        assertEquals(existingPreset.createdAt, dto.createdAt)
        assertEquals(existingPreset.updatedAt, dto.updatedAt)
        coVerify(exactly = 1) {
            modelPresetDao.insertPreset("smart_model", "Smart model", "Bundles the smart model", model.id, chatSettings.id)
        }
        coVerify(exactly = 1) { modelPresetOwnershipDao.setOwner(existingPreset.id, userId) }
    }

    @Test
    fun `createPreset trims the name before validating and storing it`() = runTest {
        val result = service.createPreset(userId, CreateModelPresetRequest(name = "  smart_model  "))

        assertTrue(result.isRight())
        coVerify(exactly = 1) { modelPresetDao.presetNameExistsForUser(userId, "smart_model") }
        coVerify(exactly = 1) { modelPresetDao.insertPreset("smart_model", null, "", null, null) }
    }

    @Test
    fun `createPreset rejects a blank or too long name without writing`() = runTest {
        val blank = service.createPreset(userId, CreateModelPresetRequest(name = "   "))
        assertIs<CreateModelPresetError.InvalidName>(blank.leftOrNull())

        val tooLong = service.createPreset(userId, CreateModelPresetRequest(name = "a".repeat(256)))
        assertIs<CreateModelPresetError.InvalidName>(tooLong.leftOrNull())

        coVerify(exactly = 0) { modelPresetDao.insertPreset(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { modelPresetOwnershipDao.setOwner(any(), any()) }
    }

    @Test
    fun `createPreset rejects a duplicate name for the same owner`() = runTest {
        coEvery { modelPresetDao.presetNameExistsForUser(userId, "smart_model") } returns true

        val result = service.createPreset(userId, CreateModelPresetRequest(name = "smart_model"))

        val error = assertIs<CreateModelPresetError.NameAlreadyExists>(result.leftOrNull())
        assertEquals("smart_model", error.name)
        coVerify(exactly = 0) { modelPresetDao.insertPreset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPreset accepts an embedding-typed settings profile`() = runTest {
        // The preset layer deliberately imposes no LLMModelType restriction: chat capability is
        // enforced only where a preset drives an agent-role turn (U-12).
        val embeddingSettings = eu.torvian.chatbot.common.models.llm.EmbeddingModelSettings(
            id = 20L,
            modelId = model.id,
            name = "Embeddings"
        )
        coEvery { settingsDao.getAllAccessibleSettings(userId, AccessMode.READ) } returns listOf(embeddingSettings)

        val result = service.createPreset(
            userId,
            CreateModelPresetRequest(name = "embeddings", modelId = model.id, modelSettingsId = embeddingSettings.id)
        )

        assertTrue(result.isRight())
        coVerify(exactly = 1) {
            modelPresetDao.insertPreset("embeddings", null, "", model.id, embeddingSettings.id)
        }
    }

    @Test
    fun `createPreset rejects a model the user cannot read`() = runTest {
        val result = service.createPreset(userId, CreateModelPresetRequest(name = "smart_model", modelId = 99L))

        val error = assertIs<CreateModelPresetError.ModelNotFound>(result.leftOrNull())
        assertEquals(99L, error.modelId)
        coVerify(exactly = 0) { modelPresetDao.insertPreset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPreset rejects a settings profile the user cannot read`() = runTest {
        val result = service.createPreset(userId, CreateModelPresetRequest(name = "smart_model", modelSettingsId = 99L))

        val error = assertIs<CreateModelPresetError.SettingsNotFound>(result.leftOrNull())
        assertEquals(99L, error.settingsId)
        coVerify(exactly = 0) { modelPresetDao.insertPreset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPreset rejects a settings profile belonging to another model`() = runTest {
        // The settings profile exists and is readable, but belongs to model 2 while the preset names model 1.
        val result = service.createPreset(
            userId,
            CreateModelPresetRequest(name = "smart_model", modelId = model.id, modelSettingsId = chatSettings2.id)
        )

        val error = assertIs<CreateModelPresetError.SettingsModelMismatch>(result.leftOrNull())
        assertEquals(chatSettings2.id, error.settingsId)
        assertEquals(chatSettings2.modelId, error.settingsModelId)
        assertEquals(model.id, error.presetModelId)
        coVerify(exactly = 0) { modelPresetDao.insertPreset(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createPreset accepts null references and skips the accessibility reads`() = runTest {
        val result = service.createPreset(userId, CreateModelPresetRequest(name = "later"))

        assertTrue(result.isRight())
        coVerify(exactly = 0) { modelDao.getAllAccessibleModels(any(), any()) }
        coVerify(exactly = 0) { settingsDao.getAllAccessibleSettings(any(), any()) }
    }

    @Test
    fun `createPreset maps an ownership insertion failure to OwnerInsertFailed`() = runTest {
        coEvery { modelPresetOwnershipDao.setOwner(any(), any()) } returns
            SetOwnerError.ForeignKeyViolation(existingPreset.id.toString(), userId).left()

        val result = service.createPreset(userId, CreateModelPresetRequest(name = "smart_model"))

        assertIs<CreateModelPresetError.OwnerInsertFailed>(result.leftOrNull())
    }

    // --- Update ---

    @Test
    fun `updatePreset applies a full replacement and re-reads the advanced timestamp`() = runTest {
        val updatedRow = existingPreset.copy(
            name = "cheap_model",
            displayName = null,
            description = "Re-pointed",
            modelSettingsId = null,
            updatedAt = Instant.fromEpochMilliseconds(2_000L)
        )
        // First read resolves the persisted row (ownership + validation), the second echoes the update.
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(existingPreset.id)) } returnsMany
            listOf(listOf(existingPreset), listOf(updatedRow))

        val result = service.updatePreset(
            userId,
            existingPreset.id,
            UpdateModelPresetRequest(
                name = "cheap_model",
                displayName = null,
                description = "Re-pointed",
                modelId = model.id,
                modelSettingsId = null
            )
        )

        assertTrue(result.isRight())
        val dto = assertNotNull(result.getOrNull())
        assertEquals("cheap_model", dto.name)
        assertEquals(null, dto.displayName)
        assertEquals(null, dto.modelSettingsId)
        assertEquals(updatedRow.updatedAt, dto.updatedAt, "the echoed DTO reports the persisted updated_at")
        coVerify(exactly = 1) {
            modelPresetDao.updatePreset(
                match { it.id == existingPreset.id && it.name == "cheap_model" && it.modelSettingsId == null }
            )
        }
    }

    @Test
    fun `updatePreset skips the rename check when the name is unchanged`() = runTest {
        val result = service.updatePreset(
            userId,
            existingPreset.id,
            UpdateModelPresetRequest(name = existingPreset.name, description = "New description")
        )

        assertTrue(result.isRight())
        // The preset being updated is never its own collision, so no lookup runs.
        coVerify(exactly = 0) { modelPresetDao.presetNameExistsForUser(any(), any()) }
    }

    @Test
    fun `updatePreset rejects a rename that collides with another owned preset`() = runTest {
        coEvery { modelPresetDao.presetNameExistsForUser(userId, "cheap_model") } returns true

        val result = service.updatePreset(
            userId,
            existingPreset.id,
            UpdateModelPresetRequest(name = "cheap_model")
        )

        assertIs<UpdateModelPresetError.NameAlreadyExists>(result.leftOrNull())
        coVerify(exactly = 0) { modelPresetDao.updatePreset(any()) }
    }

    @Test
    fun `updatePreset and deletePreset collapse a foreign preset to NotFound`() = runTest {
        coEvery { modelPresetDao.getPresetsByIdsForUser(userId, listOf(99L)) } returns emptyList()

        val update = service.updatePreset(userId, 99L, UpdateModelPresetRequest(name = "x"))
        assertEquals(99L, assertIs<UpdateModelPresetError.NotFound>(update.leftOrNull()).id)

        val delete = service.deletePreset(userId, 99L)
        assertEquals(99L, assertIs<DeleteModelPresetError.NotFound>(delete.leftOrNull()).id)

        coVerify(exactly = 0) { modelPresetDao.updatePreset(any()) }
        coVerify(exactly = 0) { modelPresetDao.deletePreset(any()) }
    }

    // --- Delete ---

    @Test
    fun `deletePreset removes an owned preset`() = runTest {
        val result = service.deletePreset(userId, existingPreset.id)

        assertTrue(result.isRight())
        coVerify(exactly = 1) { modelPresetDao.deletePreset(existingPreset.id) }
    }

    @Test
    fun `deletePreset does not need the preset service to touch agent roles`() = runTest {
        // Deleting a preset is non-destructive for roles: the runtime FK (ON DELETE SET NULL) detaches
        // the roles, so the service performs no role-side work of its own (no "in use" rejection).
        val result = service.deletePreset(userId, existingPreset.id)

        assertTrue(result.isRight())
        coVerify(exactly = 0) { modelPresetOwnershipDao.setOwner(any(), any()) }
    }

    @Test
    fun `foreign preset rows are invisible even when the id exists`() = runTest {
        // Ownership lives in its own table, so the owner-scoped read is what enforces visibility: for
        // another user the same id resolves to nothing.
        coEvery { modelPresetDao.getPresetsByIdsForUser(otherUserId, listOf(existingPreset.id)) } returns emptyList()

        val result = service.getPresetById(otherUserId, existingPreset.id)

        assertEquals(
            ModelPresetDaoError.NotFound(existingPreset.id).id,
            assertIs<ModelPresetError.NotFound>(result.leftOrNull()).id
        )
    }
}
