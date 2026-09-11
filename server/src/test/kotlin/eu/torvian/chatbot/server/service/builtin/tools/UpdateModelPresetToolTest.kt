package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.ModelPresetError
import eu.torvian.chatbot.server.service.core.error.preset.UpdateModelPresetError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [UpdateModelPresetTool].
 *
 * Covers the PATCH semantics (ownership-checked load, merge of only the provided fields over the
 * persisted preset, the explicit clear sentinels, full-replacement update with the merged state),
 * the load failure aborting before the update, every update-error mapping, and input validation.
 */
class UpdateModelPresetToolTest {

    private val userId = 7L

    /**
     * Fully-populated execution context for handler-level tests: the handlers ignore the session
     * fields, so fixed non-null values keep the fixture simple while matching the real contract.
     */
    private fun context(userId: Long = this.userId): ToolCallExecutionContext =
        ToolCallExecutionContext(
            userId = userId,
            sessionId = 1L,
            sessionName = "Session",
            agentRoleId = 1L
        )

    /**
     * Creates a preset fixture carrying the persisted state that the PATCH merge is tested against.
     *
     * @param name Persisted preset name.
     * @param displayName Persisted display name (nullable).
     * @param description Persisted description.
     * @param modelId Persisted model reference (nullable).
     * @param modelSettingsId Persisted settings reference (nullable).
     * @return The fixture preset.
     */
    private fun samplePreset(
        name: String = "smart_model",
        displayName: String? = "Smart model",
        description: String = "Bundles the smart model with the default settings profile",
        modelId: Long? = 11L,
        modelSettingsId: Long? = 21L
    ) = ModelPresetDto(
        id = 3L,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-01-02T00:00:00Z")
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    /**
     * Runs one update against [persisted] and returns the [UpdateModelPresetRequest] the tool
     * handed to the service, so the merge matrix can be asserted on the merged request itself
     * (the mocked service accepts any request and echoes the persisted preset).
     *
     * @param input The raw tool input to execute.
     * @param persisted The preset the mocked ownership-checked load returns.
     * @return The merged request the tool passed to the model-preset service.
     */
    private suspend fun capturedUpdateRequest(
        input: JsonObject,
        persisted: ModelPresetDto = samplePreset()
    ): UpdateModelPresetRequest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.getPresetById(userId, 3L) } returns persisted.right()
        val captured = slot<UpdateModelPresetRequest>()
        coEvery { modelPresetService.updatePreset(userId, 3L, capture(captured)) } returns persisted.right()

        UpdateModelPresetTool(modelPresetService).execute(input, context())
        return captured.captured
    }

    @Test
    fun `requires the model_preset_id property`() = runTest {
        val tool = UpdateModelPresetTool(mockk())

        val result = tool.execute(buildJsonObject { put("name", "x") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: model_preset_id"))
    }

    @Test
    fun `rejects a non-integer model_preset_id`() = runTest {
        val tool = UpdateModelPresetTool(mockk())

        val result = tool.execute(buildJsonObject { put("model_preset_id", 1.5) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'model_preset_id' must be an integer"))
    }

    /**
     * Verifies that a single provided field is patched while every other field — including both
     * references — is carried over from the persisted preset, and that success returns a one-line
     * summary mentioning the name and id (never JSON).
     */
    @Test
    fun `merges provided fields over the persisted preset`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val persisted = samplePreset()
        coEvery { modelPresetService.getPresetById(userId, 3L) } returns persisted.right()
        coEvery { modelPresetService.updatePreset(userId, 3L, any()) } returns
            persisted.copy(name = "renamed_model").right()
        val tool = UpdateModelPresetTool(modelPresetService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject { put("model_preset_id", 3L); put("name", "renamed_model") },
                context()
            )
        )

        assertTrue(output.contains("Updated model preset 'renamed_model' (id: 3)"))
        assertTrue(!output.contains("\"id\":"))
        coVerify(exactly = 1) {
            modelPresetService.updatePreset(
                userId,
                3L,
                match<UpdateModelPresetRequest> { request ->
                    request.name == "renamed_model" &&
                        request.displayName == persisted.displayName &&
                        request.description == persisted.description &&
                        request.modelId == persisted.modelId &&
                        request.modelSettingsId == persisted.modelSettingsId
                }
            )
        }
    }

    /**
     * Verifies that explicitly-null fields, like omitted ones, preserve the persisted values
     * (`parseOptionalString`/`parseOptionalLong` cannot distinguish absent from null).
     */
    @Test
    fun `explicit null fields preserve the persisted values`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val persisted = samplePreset()
        coEvery { modelPresetService.getPresetById(userId, 3L) } returns persisted.right()
        coEvery { modelPresetService.updatePreset(userId, 3L, any()) } returns persisted.right()
        val tool = UpdateModelPresetTool(modelPresetService)

        assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("model_preset_id", 3L)
                    put("name", JsonNull)
                    put("display_name", JsonNull)
                    put("description", JsonNull)
                    put("model_id", JsonNull)
                    put("model_settings_id", JsonNull)
                },
                context()
            )
        )

        coVerify(exactly = 1) {
            modelPresetService.updatePreset(
                userId,
                3L,
                match<UpdateModelPresetRequest> { request ->
                    request.name == persisted.name &&
                        request.displayName == persisted.displayName &&
                        request.description == persisted.description &&
                        request.modelId == persisted.modelId &&
                        request.modelSettingsId == persisted.modelSettingsId
                }
            )
        }
    }

    /**
     * Verifies that an explicit empty string clears the description and that an empty
     * `display_name` clears the display name while a non-empty one sets it (the documented
     * string-clear sentinels).
     */
    @Test
    fun `empty strings clear the description and the display name`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val persisted = samplePreset()
        coEvery { modelPresetService.getPresetById(userId, 3L) } returns persisted.right()
        coEvery { modelPresetService.updatePreset(userId, 3L, any()) } returns
            persisted.copy(displayName = null, description = "").right()
        val tool = UpdateModelPresetTool(modelPresetService)

        assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("model_preset_id", 3L)
                    put("display_name", "")
                    put("description", "")
                },
                context()
            )
        )

        coVerify(exactly = 1) {
            modelPresetService.updatePreset(
                userId,
                3L,
                match<UpdateModelPresetRequest> { request ->
                    request.displayName == null &&
                        request.description == "" &&
                        request.name == persisted.name &&
                        request.modelId == persisted.modelId
                }
            )
        }

        // A non-empty display_name replaces the persisted value.
        coEvery { modelPresetService.updatePreset(userId, 3L, any()) } returns
            persisted.copy(displayName = "Renamed preset").right()
        assertSuccess(
            tool.execute(
                buildJsonObject { put("model_preset_id", 3L); put("display_name", "Renamed preset") },
                context()
            )
        )
        coVerify(exactly = 1) {
            modelPresetService.updatePreset(
                userId,
                3L,
                match<UpdateModelPresetRequest> { it.displayName == "Renamed preset" }
            )
        }
    }

    /**
     * Verifies the `0` clear sentinel for the two optional references and that positive ids
     * replace the persisted references.
     */
    @Test
    fun `zero clears a reference and positive ids replace them`() = runTest {
        val persisted = samplePreset()

        // model_id = 0 clears only the model reference (0 is never a valid model id).
        val clearedModel = capturedUpdateRequest(
            buildJsonObject { put("model_preset_id", 3L); put("model_id", 0L) }
        )
        assertNull(clearedModel.modelId)
        assertEquals(persisted.modelSettingsId, clearedModel.modelSettingsId)

        // model_settings_id = 0 clears only the settings reference.
        val clearedSettings = capturedUpdateRequest(
            buildJsonObject { put("model_preset_id", 3L); put("model_settings_id", 0L) }
        )
        assertEquals(persisted.modelId, clearedSettings.modelId)
        assertNull(clearedSettings.modelSettingsId)

        // Positive ids replace the persisted references (the service validates their agreement).
        val replaced = capturedUpdateRequest(
            buildJsonObject {
                put("model_preset_id", 3L)
                put("model_id", 12L)
                put("model_settings_id", 22L)
            }
        )
        assertEquals(12L, replaced.modelId)
        assertEquals(22L, replaced.modelSettingsId)
    }

    /**
     * Verifies that a load not-found aborts before the update is ever called.
     */
    @Test
    fun `collapses a load not-found before calling updatePreset`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.getPresetById(userId, 99L) } returns ModelPresetError.NotFound(99L).left()
        val tool = UpdateModelPresetTool(modelPresetService)

        val result = tool.execute(
            buildJsonObject { put("model_preset_id", 99L); put("name", "x") },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { modelPresetService.updatePreset(any(), any(), any()) }
    }

    /**
     * Verifies the mapping of the update-service failures: the not-found variant collapses into
     * NotFoundOrNotAccessible and the validation variants surface machine-readable codes.
     */
    @Test
    fun `maps every update error to a readable handler error`() = runTest {
        val persisted = samplePreset()
        val cases = mapOf(
            UpdateModelPresetError.NotFound(99L) to "NotFoundOrNotAccessible",
            UpdateModelPresetError.InvalidName("", "name must not be blank") to "invalid_name",
            UpdateModelPresetError.NameAlreadyExists("smart_model") to "name_already_exists",
            UpdateModelPresetError.ModelNotFound(12L) to "model_not_found",
            UpdateModelPresetError.SettingsNotFound(22L) to "settings_not_found",
            UpdateModelPresetError.SettingsModelMismatch(
                settingsId = 21L,
                settingsModelId = 12L,
                presetModelId = 11L
            ) to "settings_model_mismatch"
        )
        cases.forEach { (serviceError, expected) ->
            val modelPresetService = mockk<ModelPresetService>()
            coEvery { modelPresetService.getPresetById(userId, 3L) } returns persisted.right()
            coEvery { modelPresetService.updatePreset(userId, 3L, any()) } returns serviceError.left()
            val tool = UpdateModelPresetTool(modelPresetService)

            val result = tool.execute(buildJsonObject { put("model_preset_id", 3L) }, context())

            if (expected == "NotFoundOrNotAccessible") {
                assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
            } else {
                val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
                assertEquals(expected, error.code)
            }
        }
    }

    @Test
    fun `rejects unknown parameters without touching the persisted preset`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val tool = UpdateModelPresetTool(modelPresetService)

        val result = tool.execute(
            buildJsonObject { put("model_preset_id", 3L); put("roles", 5L) },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'roles'"))
        coVerify(exactly = 0) { modelPresetService.getPresetById(any(), any()) }
        coVerify(exactly = 0) { modelPresetService.updatePreset(any(), any(), any()) }
    }

    /**
     * Verifies that the tool description documents the patch semantics and the clear sentinels the
     * LLM must use.
     */
    @Test
    fun `describes the patch semantics and the clear sentinels`() {
        val tool = UpdateModelPresetTool(mockk())

        assertTrue(tool.description.contains("patch semantics"))
        assertTrue(tool.description.contains("pass 0"))
        assertTrue(tool.description.contains("empty string"))
    }
}
