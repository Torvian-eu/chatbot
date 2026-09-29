package eu.torvian.chatbot.common.models.api.agent

import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Serialization tests for [InstructionSlot] and the role write requests carrying it.
 *
 * Pins the three strictly-typed variants and the fail-loud guard: a payload naming a removed link-set
 * property or the legacy `instructions` property must be rejected by strict decoding instead of
 * silently decoding to "no links".
 */
class InstructionSlotSerializationTest {

    /** Strict codec mirroring the REST content negotiation: unknown keys are rejected. */
    private val json = Json { encodeDefaults = true }

    @Test
    fun `all three variants round-trip under the polymorphic discriminator`() {
        val slots: List<InstructionSlot> = listOf(
            InstructionSlot.Link(12L),
            InstructionSlot.Create(
                CreateInstructionRequest(
                    type = AgentInstructionTypes.ROLE,
                    name = "Role",
                    message = "You are a writer."
                )
            ),
            InstructionSlot.Update(
                UpdateInstructionRequest(
                    id = 15L,
                    type = AgentInstructionTypes.MAIN,
                    name = "Main",
                    message = "Rewritten text."
                )
            )
        )

        val encoded = json.encodeToString(slots)
        assertEquals(slots, json.decodeFromString<List<InstructionSlot>>(encoded))
        // The variant tag rides on the slot object; the wrapped content nests its own `type` field, so
        // the two never share a JSON object.
        assertTrue(encoded.contains("\"type\":\"create\""), encoded)
    }

    @Test
    fun `role requests round-trip with an ordered spec list`() {
        val create = CreateAgentRoleRequest(
            name = "writer",
            instructionSpecs = listOf(
                InstructionSlot.Create(
                    CreateInstructionRequest(
                        type = AgentInstructionTypes.ROLE,
                        name = "Role",
                        message = "You are a writer."
                    )
                ),
                InstructionSlot.Link(12L),
                InstructionSlot.Update(
                    UpdateInstructionRequest(
                        id = 15L,
                        type = AgentInstructionTypes.MAIN,
                        name = "Main",
                        message = "Rewritten text."
                    )
                )
            )
        )
        val update = UpdateAgentRoleRequest(name = "writer", instructionSpecs = listOf(InstructionSlot.Link(3L)))

        assertEquals(create, json.decodeFromString<CreateAgentRoleRequest>(json.encodeToString(create)))
        assertEquals(update, json.decodeFromString<UpdateAgentRoleRequest>(json.encodeToString(update)))
    }

    @Test
    fun `a payload carrying the removed instructionIds key is rejected`() {
        // The key is rejected regardless of its value: an empty array would otherwise decode as
        // "clear every link" and silently destroy the role's link set.
        val createBody = """{"name":"writer","instructionIds":[]}"""
        val updateBody = """{"name":"writer","instructionIds":[3]}"""

        assertFailsWith<SerializationException> { json.decodeFromString<CreateAgentRoleRequest>(createBody) }
        assertFailsWith<SerializationException> { json.decodeFromString<UpdateAgentRoleRequest>(updateBody) }
    }

    @Test
    fun `a payload carrying the legacy instructions key is rejected`() {
        // Same fail-loud guard for the pre-normalization shape: stale entries must never decode as
        // "clear every link".
        val createBody = """{"name":"writer","instructions":[]}"""
        val updateBody =
            """{"name":"writer","instructions":[{"type":"role","name":"Role","message":"x"}]}"""

        assertFailsWith<SerializationException> { json.decodeFromString<CreateAgentRoleRequest>(createBody) }
        assertFailsWith<SerializationException> { json.decodeFromString<UpdateAgentRoleRequest>(updateBody) }
    }

    @Test
    fun `an unknown tag or missing variant fields is rejected`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InstructionSlot>("""{"type":"delete","id":1}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<InstructionSlot>("""{"type":"link"}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<InstructionSlot>("""{"type":"create"}""")
        }
    }

    @Test
    fun `absent instructionSpecs decodes to an empty link set`() {
        // Absent means "no links" by contract, which is also the shape a minimal payload produces.
        assertEquals(emptyList(), json.decodeFromString<CreateAgentRoleRequest>("""{"name":"writer"}""").instructionSpecs)
        assertEquals(emptyList(), json.decodeFromString<UpdateAgentRoleRequest>("""{"name":"writer"}""").instructionSpecs)
    }
}
