package eu.torvian.chatbot.common.models.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            AgentInstructionDto(1L, AgentInstructionTypes.ROLE, "Role", "You are an architect."),
            AgentInstructionDto(2L, AgentInstructionTypes.MAIN, "Main instruction", "Project context"),
            AgentInstructionDto(3L, AgentInstructionTypes.CUSTOM, "Tone", "Be concise"),
            AgentInstructionDto(
                4L,
                AgentInstructionTypes.MODEL_SPECIFIC,
                "Swift mode",
                "Write idiomatic Swift",
                custom = buildJsonObject { put("modelId", 5L) }
            ),
            AgentInstructionDto(5L, AgentInstructionTypes.SPAWNABLE_AGENTS, "Available agents", "")
        )

        val encoded = json.encodeToString(instructions)
        val decoded = json.decodeFromString<List<AgentInstructionDto>>(encoded)

        assertEquals(instructions, decoded)
    }

    @Test
    fun `model_specific serializes with its modelId in custom`() {
        val instruction = AgentInstructionDto(
            5L,
            AgentInstructionTypes.MODEL_SPECIFIC,
            "Swift",
            "Write Swift",
            custom = buildJsonObject { put("modelId", 5L) }
        )
        val encoded = json.encodeToString(instruction)
        assertTrue(encoded.contains("\"type\":\"model_specific\""), "expected discriminator, got: $encoded")
        assertTrue(encoded.contains("\"modelId\":5"), "expected modelId field, got: $encoded")
    }

    @Test
    fun `reported entries decode with their identity and link fields`() {
        val payload = """
            [
                {"id":5,"type":"custom","name":"Tone","message":"Be concise","linkedRoleIds":[1,2,7]},
                {"id":6,"type":"spawnable_agents","name":"Available agents","message":"generated"}
            ]
        """.trimIndent()

        val decoded = json.decodeFromString<List<AgentInstructionDto>>(payload)

        assertEquals(
            listOf(
                AgentInstructionDto(
                    id = 5L,
                    type = AgentInstructionTypes.CUSTOM,
                    name = "Tone",
                    message = "Be concise",
                    linkedRoleIds = setOf(1L, 2L, 7L)
                ),
                AgentInstructionDto(
                    id = 6L,
                    type = AgentInstructionTypes.SPAWNABLE_AGENTS,
                    name = "Available agents",
                    message = "generated"
                )
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

    @Test
    fun `identity fields round-trip through the codec`() {
        val instructions = listOf(
            AgentInstructionDto(
                id = 5L,
                type = AgentInstructionTypes.CUSTOM,
                name = "Tone",
                message = "Be concise",
                linkedRoleIds = setOf(1L, 2L, 7L)
            )
        )

        val encoded = json.encodeToString(instructions)
        val decoded = json.decodeFromString<List<AgentInstructionDto>>(encoded)

        assertEquals(instructions, decoded)
    }

    @Test
    fun `absent linked roles decode as an empty set`() {
        val payload = """[{"id":5,"type":"role","name":"Role","message":"Text"}]"""

        val decoded = json.decodeFromString<List<AgentInstructionDto>>(payload)

        val instruction = decoded.single()
        assertEquals(5L, instruction.id)
        assertEquals(emptySet(), instruction.linkedRoleIds)
    }

    @Test
    fun `an entry without an id does not decode`() {
        // Every reported instruction is backed by a stored row, so the identity is part of the
        // contract rather than an optional field.
        val payload = """[{"type":"role","name":"Role","message":"Text"}]"""

        val decoded = runCatching {
            json.decodeFromString<List<AgentInstructionDto>>(payload)
        }.getOrNull()

        assertNull(decoded)
    }

    @Test
    fun `shared derives from more than one linked role and is never serialized`() {
        val shared = AgentInstructionDto(
            id = 5L,
            type = AgentInstructionTypes.CUSTOM,
            name = "Tone",
            message = "Be concise",
            linkedRoleIds = setOf(2L, 7L)
        )
        val single = shared.copy(linkedRoleIds = setOf(2L))
        val unlinked = shared.copy(linkedRoleIds = emptySet())

        assertTrue(shared.shared)
        assertFalse(single.shared)
        assertFalse(unlinked.shared)
        assertFalse(json.encodeToString(shared).contains("shared"), "derived flag must not be serialized")
    }
}
