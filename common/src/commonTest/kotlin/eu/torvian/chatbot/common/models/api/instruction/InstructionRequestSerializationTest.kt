package eu.torvian.chatbot.common.models.api.instruction

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Serialization tests for the instruction authoring requests.
 *
 * A created row has no identity yet, while an update has to name the row it replaces, so the two
 * request shapes differ in exactly that field.
 */
class InstructionRequestSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `create carries no row id while an update names the row it replaces`() {
        val create = CreateInstructionRequest(
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise"
        )
        val update = UpdateInstructionRequest(
            id = 5L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents"
        )

        assertEquals(create, json.decodeFromString<CreateInstructionRequest>(json.encodeToString(create)))
        assertEquals(update, json.decodeFromString<UpdateInstructionRequest>(json.encodeToString(update)))
        // The server assigns the id of a created row, while an update has to name its target.
        assertFalse(json.encodeToString(create).contains("\"id\""))
        assertTrue(json.encodeToString(update).contains("\"id\":5"), "expected the target row id")
    }

    @Test
    fun `the generated-message kind omits its message in both requests`() {
        val create = CreateInstructionRequest(
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents"
        )
        val update = UpdateInstructionRequest(
            id = 8L,
            type = AgentInstructionTypes.SPAWNABLE_AGENTS,
            name = "Available agents"
        )

        assertEquals(create, json.decodeFromString<CreateInstructionRequest>(json.encodeToString(create)))
        assertEquals(update, json.decodeFromString<UpdateInstructionRequest>(json.encodeToString(update)))
    }
}
