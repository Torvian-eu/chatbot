package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ModelPresetService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ListModelPresetsTool].
 *
 * Covers the complete [ModelPresetDto] projection, the empty-list case, the user-scoping
 * description, the preservation (no re-sorting) of the service's `id`-ascending order, and strict
 * rejection of input parameters.
 */
class ListModelPresetsToolTest {

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

    /**
     * Creates a model-preset fixture with configurable properties.
     *
     * @param id Preset identifier.
     * @param name Preset name.
     * @param displayName Optional display name.
     * @param description Free-form description.
     * @param modelId Referenced model id, or `null` when unset.
     * @param modelSettingsId Referenced settings profile id, or `null` when unset.
     * @param createdAt Creation timestamp.
     * @param updatedAt Last-update timestamp.
     * @return A preset DTO suitable for list-tool assertions.
     */
    private fun samplePreset(
        id: Long = 3L,
        name: String = "smart_model",
        displayName: String? = "Smart model",
        description: String = "Bundles the smart model with the default settings profile",
        modelId: Long? = 11L,
        modelSettingsId: Long? = 21L,
        createdAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        updatedAt: Instant = Instant.parse("2024-01-02T00:00:00Z")
    ) = ModelPresetDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    /**
     * Verifies that every preset is emitted with exactly the [ModelPresetDto] wire shape, including
     * the ISO-8601 timestamps and the null references, and in the service's order.
     */
    @Test
    fun `returns all preset DTO properties for every preset in the service order`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        // Deliberately "unnatural" ordering input (id 9 before id 3) to prove the tool returns the
        // service order verbatim: ordering is the DAO/service contract and must not be re-implemented
        // (or second-guessed) here.
        coEvery { modelPresetService.getAllPresetsForUser(userId) } returns listOf(
            samplePreset(id = 9L, name = "second"),
            samplePreset(
                id = 3L,
                name = "first",
                displayName = null,
                modelId = null,
                modelSettingsId = null
            )
        )
        val tool = ListModelPresetsTool(modelPresetService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))
        val presets = json.parseToJsonElement(output).jsonArray
        val expectedKeys = setOf(
            "id",
            "name",
            "displayName",
            "description",
            "modelId",
            "modelSettingsId",
            "createdAt",
            "updatedAt"
        )

        assertEquals(2, presets.size)

        val first = presets[0].jsonObject
        assertEquals(expectedKeys, first.keys)
        assertEquals(9L, first.getValue("id").jsonPrimitive.long)
        assertEquals("second", first.getValue("name").jsonPrimitive.content)

        val second = presets[1].jsonObject
        assertEquals(expectedKeys, second.keys)
        assertEquals(3L, second.getValue("id").jsonPrimitive.long)
        assertEquals("first", second.getValue("name").jsonPrimitive.content)
        // A null reference and a null display name are emitted as explicit nulls (shared codec).
        assertTrue(second.getValue("displayName").jsonPrimitive.content == "null")
        assertTrue(second.getValue("modelId").jsonPrimitive.content == "null")
        assertTrue(second.getValue("modelSettingsId").jsonPrimitive.content == "null")
        assertEquals("2024-01-01T00:00:00Z", second.getValue("createdAt").jsonPrimitive.content)
        assertEquals("2024-01-02T00:00:00Z", second.getValue("updatedAt").jsonPrimitive.content)
    }

    /**
     * Verifies that no presets are still represented by the exact empty JSON array.
     */
    @Test
    fun `returns an empty array when the user has no presets`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        coEvery { modelPresetService.getAllPresetsForUser(userId) } returns emptyList()
        val tool = ListModelPresetsTool(modelPresetService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))

        assertEquals("[]", output)
    }

    /**
     * Verifies that the tool description documents user scoping and the returned properties.
     */
    @Test
    fun `describes user scoping and output properties`() {
        val tool = ListModelPresetsTool(mockk(), json)

        assertTrue(tool.description.contains("owned by the current user"))
        assertTrue(tool.description.contains("ordered by id"))
        assertTrue(tool.description.contains("settings profile"))
    }

    /**
     * Verifies that unknown parameters fail validation before the user-scoped service is called.
     */
    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val modelPresetService = mockk<ModelPresetService>()
        val tool = ListModelPresetsTool(modelPresetService, json)

        val result = tool.execute(buildJsonObject { put("name", "smart_model") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'name'"))
        coVerify(exactly = 0) { modelPresetService.getAllPresetsForUser(any()) }
    }

    /**
     * Verifies that the parameterless schema advertises no parameters, matching the handler's
     * rejection of every argument.
     */
    @Test
    fun `declares a parameterless input schema`() {
        val tool = ListModelPresetsTool(mockk(), json)

        assertTrue(tool.inputSchema["properties"]!!.jsonObject.isEmpty())
        assertNull(tool.inputSchema["required"])
    }
}
