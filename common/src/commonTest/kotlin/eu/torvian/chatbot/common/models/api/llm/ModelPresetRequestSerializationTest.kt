package eu.torvian.chatbot.common.models.api.llm

import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.llm.MAX_MODEL_PRESET_NAME_LENGTH
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the shared model-preset wire contract and the agent-role write payloads' move to a single
 * preset reference.
 *
 * The preset DTO/requests must round-trip with the same codec the server and every client use, and the
 * role write requests must carry `modelPresetId` while no longer declaring `modelId`/`modelSettingsId`
 * (the two stay derived, read-only fields on the response DTO only).
 */
class ModelPresetRequestSerializationTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val preset = ModelPresetDto(
        id = 7L,
        name = "smart_model",
        displayName = "Smart model",
        description = "Bundles the smart model",
        modelId = 1L,
        modelSettingsId = 2L,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
        updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_500L)
    )

    @Test
    fun `ModelPresetDto round-trips including the server-managed timestamps`() {
        val wire = json.encodeToString(ModelPresetDto.serializer(), preset)
        assertTrue(wire.contains("\"name\":\"smart_model\""))
        assertTrue(wire.contains("\"createdAt\""))
        assertTrue(wire.contains("\"updatedAt\""))

        assertEquals(preset, json.decodeFromString(ModelPresetDto.serializer(), wire))
    }

    @Test
    fun `ModelPresetDto exposes both references as explicit nulls when unset`() {
        val wire = json.encodeToString(
            ModelPresetDto.serializer(),
            preset.copy(modelId = null, modelSettingsId = null, displayName = null)
        )

        // A null reference is a legal preset state (the SET NULL outcome), so it must be transmitted
        // explicitly rather than omitted: the client uses it to flag a non-sendable configuration.
        assertTrue(wire.contains("\"modelId\":null"))
        assertTrue(wire.contains("\"modelSettingsId\":null"))
    }

    @Test
    fun `create and update preset requests round-trip with optional references`() {
        val create = CreateModelPresetRequest(
            name = "cheap_model",
            displayName = null,
            description = "",
            modelId = 1L,
            modelSettingsId = null
        )
        assertEquals(
            create,
            json.decodeFromString(CreateModelPresetRequest.serializer(), json.encodeToString(create))
        )

        val update = UpdateModelPresetRequest(name = "cheap_model", modelId = null, modelSettingsId = 2L)
        assertEquals(
            update,
            json.decodeFromString(UpdateModelPresetRequest.serializer(), json.encodeToString(update))
        )
        // The timestamps are server-managed, so the write requests must not carry them at all.
        assertFalse(json.encodeToString(update).contains("createdAt"))
        assertFalse(json.encodeToString(update).contains("updatedAt"))
    }

    @Test
    fun `agent-role write requests carry only modelPresetId`() {
        val create = CreateAgentRoleRequest(name = "architect", modelPresetId = 7L)
        val createWire = json.encodeToString(create)
        assertTrue(createWire.contains("\"modelPresetId\":7"))
        assertFalse(createWire.contains("\"modelId\""))
        assertFalse(createWire.contains("\"modelSettingsId\""))

        val update = UpdateAgentRoleRequest(name = "architect", modelPresetId = 7L)
        val updateWire = json.encodeToString(update)
        assertTrue(updateWire.contains("\"modelPresetId\":7"))
        assertFalse(updateWire.contains("\"modelId\""))
        assertFalse(updateWire.contains("\"modelSettingsId\""))
    }

    @Test
    fun `a preset-less role is expressible on the wire`() {
        // The preset is optional on both writers (U-30/U-36): a null must survive as an explicit null.
        val request = CreateAgentRoleRequest(name = "architect")

        assertEquals(null, request.modelPresetId)
        assertTrue(json.encodeToString(request).contains("\"modelPresetId\":null"))
    }

    @Test
    fun `the shared preset name bound mirrors the database column`() {
        assertEquals(255, MAX_MODEL_PRESET_NAME_LENGTH)
    }
}
