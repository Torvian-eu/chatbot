package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.CreateModelPresetError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [CreateModelPresetTool].
 *
 * Covers input validation (required `name`, optional `display_name`/`description`/`model_id`/
 * `model_settings_id`, accumulated errors, unknown parameters), the mapping of the parsed input
 * into a [CreateModelPresetRequest] (including the empty defaults), the mapping of every
 * [CreateModelPresetError] to an LLM-readable handler error, and the full [ModelPresetDto] JSON
 * output shape.
 */
class CreateModelPresetToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

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

    private fun createdPreset(id: Long = 3L, name: String = "smart_model") = ModelPresetDto(
        id = id,
        name = name,
        displayName = "Smart model",
        description = "Bundles the smart model with the default settings profile",
        modelId = 11L,
        modelSettingsId = 21L,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2024-01-01T00:00:00Z")
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the name property`() = runTest {
        val tool = CreateModelPresetTool(mockk(), json)

        val result = tool.execute(buildJsonObject { put("model_id", 11L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: name"))
    }

    /**
     * Verifies the request defaults (no display name, empty description, no references — the
     * service layer is deliberately permissive about an unconfigured preset) and that success
     * returns the full preset JSON including the server-generated id and timestamps.
     */
    @Test
    fun `creates a preset with default optional fields and returns the full DTO JSON`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.createPreset(userId, any()) } returns createdPreset().right()
        val tool = CreateModelPresetTool(modelPresetService, json)

        val output = assertSuccess(
            tool.execute(buildJsonObject { put("name", "smart_model") }, context())
        )
        // create_model_preset returns the full preset JSON (the create_project precedent) so the
        // caller learns the new id without an extra read_model_preset call.
        val decoded = json.parseToJsonElement(output).jsonObject
        assertEquals(3L, decoded.getValue("id").jsonPrimitive.long)
        assertEquals("smart_model", decoded.getValue("name").jsonPrimitive.content)
        assertEquals("2024-01-01T00:00:00Z", decoded.getValue("createdAt").jsonPrimitive.content)
        assertEquals(11L, decoded.getValue("modelId").jsonPrimitive.long)

        coVerify(exactly = 1) {
            modelPresetService.createPreset(
                userId,
                match<CreateModelPresetRequest> { request ->
                    request.name == "smart_model" &&
                        request.displayName == null &&
                        request.description == "" &&
                        request.modelId == null &&
                        request.modelSettingsId == null
                }
            )
        }
    }

    /**
     * Verifies that present optional values are parsed and forwarded into the request.
     */
    @Test
    fun `parses the optional fields into the request`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.createPreset(userId, any()) } returns createdPreset().right()
        val tool = CreateModelPresetTool(modelPresetService, json)

        val input = buildJsonObject {
            put("name", "smart_model")
            put("display_name", "Smart model")
            put("description", "Bundles the smart model with the default settings profile")
            put("model_id", 11L)
            put("model_settings_id", 21L)
        }
        tool.execute(input, context())

        coVerify(exactly = 1) {
            modelPresetService.createPreset(
                userId,
                match<CreateModelPresetRequest> { request ->
                    request.name == "smart_model" &&
                        request.displayName == "Smart model" &&
                        request.description == "Bundles the smart model with the default settings profile" &&
                        request.modelId == 11L &&
                        request.modelSettingsId == 21L
                }
            )
        }
    }

    /**
     * Verifies every create-error mapping to its machine-readable handler code and that the
     * accessibility failures keep the collapsed no-existence-leak wording.
     */
    @Test
    fun `maps every create error to a readable handler error`() = runTest {
        val cases = mapOf(
            CreateModelPresetError.InvalidName("", "name must not be blank") to "invalid_name",
            CreateModelPresetError.NameAlreadyExists("smart_model") to "name_already_exists",
            CreateModelPresetError.ModelNotFound(11L) to "model_not_found",
            CreateModelPresetError.SettingsNotFound(21L) to "settings_not_found",
            CreateModelPresetError.SettingsModelMismatch(
                settingsId = 21L,
                settingsModelId = 12L,
                presetModelId = 11L
            ) to "settings_model_mismatch",
            CreateModelPresetError.OwnerInsertFailed("constraint violation") to "owner_insert_failed"
        )
        cases.forEach { (serviceError, expectedCode) ->
            val modelPresetService = mockk<ModelPresetService>()
            coEvery { modelPresetService.createPreset(userId, any()) } returns serviceError.left()
            val tool = CreateModelPresetTool(modelPresetService, json)

            val result = tool.execute(buildJsonObject { put("name", "smart_model") }, context())

            val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
            assertEquals(expectedCode, error.code)
        }
    }

    /**
     * Verifies the collapsed accessibility wording and that the mismatch message names both models.
     */
    @Test
    fun `reports inaccessible references and mismatching settings without leaking existence`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.createPreset(userId, any()) } returns
            CreateModelPresetError.ModelNotFound(11L).left()
        val tool = CreateModelPresetTool(modelPresetService, json)

        val notFound = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(
            tool.execute(buildJsonObject { put("name", "smart_model"); put("model_id", 11L) }, context())
                .leftOrNull()
        )
        assertTrue(notFound.message.contains("not found or not accessible by the current user"))

        val mismatchService = mockk<ModelPresetService>()
        coEvery { mismatchService.createPreset(userId, any()) } returns
            CreateModelPresetError.SettingsModelMismatch(
                settingsId = 21L,
                settingsModelId = 12L,
                presetModelId = 11L
            ).left()
        val mismatchTool = CreateModelPresetTool(mismatchService, json)

        val mismatch = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(
            mismatchTool.execute(
                buildJsonObject {
                    put("name", "smart_model")
                    put("model_id", 11L)
                    put("model_settings_id", 21L)
                },
                context()
            ).leftOrNull()
        )
        assertTrue(mismatch.message.contains("belongs to model 12, not 11"))
    }

    /**
     * Verifies that all issues are accumulated into one error so the LLM can fix them at once, and
     * that the service is never called for an invalid input.
     */
    @Test
    fun `accumulates every validation error before failing without calling the service`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val tool = CreateModelPresetTool(modelPresetService, json)

        val result = tool.execute(
            buildJsonObject {
                put("name", 123)
                put("model_id", "oops")
                put("unknown", true)
            },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("3 error(s)"))
        assertTrue(error.message.contains("Argument 'name' must be a string"))
        assertTrue(error.message.contains("Argument 'model_id' must be an integer"))
        assertTrue(error.message.contains("Unknown parameter: 'unknown'"))
        coVerify(exactly = 0) { modelPresetService.createPreset(any(), any()) }
    }
}
