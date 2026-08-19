package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serialization tests for the flat [AgentInstructionDto] data class.
 */
class AgentInstructionDtoSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun `all built-in subtypes round-trip through the codec`() {
        val instructions: List<AgentInstructionDto> = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are an architect."),
            AgentInstructionDto(AgentInstructionTypes.MAIN, "Main instruction", "Project context"),
            AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Tone", "Be concise"),
            AgentInstructionDto(AgentInstructionTypes.MODEL_SETTINGS, "Model instruction", ""),
            AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "Swift mode", "Write idiomatic Swift",
                custom = buildJsonObject { put("modelId", 5L) }),
            AgentInstructionDto(AgentInstructionTypes.SPAWNABLE_AGENTS, "Available agents", "")
        )

        val encoded = json.encodeToString(instructions)
        val decoded = json.decodeFromString<List<AgentInstructionDto>>(encoded)

        assertEquals(instructions, decoded)
    }

    @Test
    fun `model_specific serializes with its modelId in custom`() {
        val instruction = AgentInstructionDto(AgentInstructionTypes.MODEL_SPECIFIC, "Swift", "Write Swift",
                custom = buildJsonObject { put("modelId", 5L) })
        val encoded = json.encodeToString(instruction)
        assertTrue(encoded.contains("\"type\":\"model_specific\""), "expected discriminator, got: $encoded")
        assertTrue(encoded.contains("\"modelId\":5"), "expected modelId field, got: $encoded")
    }

    @Test
    fun `legacy stored shapes decode into the matching subtypes without migration`() {
        val payload = """
            [
                {"type":"role","name":"Role","message":"You are a senior architect."},
                {"type":"main","name":"Main instruction","message":"AGENTS.md context"},
                {"type":"custom","name":"Tone","message":"Be concise"},
                {"type":"model_settings","name":"Model instruction","message":""},
                {"type":"spawnable_agents","name":"Available agents","message":""}
            ]
        """.trimIndent()

        val decoded = json.decodeFromString<List<AgentInstructionDto>>(payload)

        assertEquals(
            listOf(
                AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a senior architect."),
                AgentInstructionDto(AgentInstructionTypes.MAIN, "Main instruction", "AGENTS.md context"),
                AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Tone", "Be concise"),
                AgentInstructionDto(AgentInstructionTypes.MODEL_SETTINGS, "Model instruction", ""),
                AgentInstructionDto(AgentInstructionTypes.SPAWNABLE_AGENTS, "Available agents", "")
            ),
            decoded
        )
    }

    @Test
    fun `empty instructions list decodes correctly`() {
        val payload = """[]"""
        val decoded = json.decodeFromString<List<AgentInstructionDto>>(payload)
        assertTrue(decoded.isEmpty())
    }
}
