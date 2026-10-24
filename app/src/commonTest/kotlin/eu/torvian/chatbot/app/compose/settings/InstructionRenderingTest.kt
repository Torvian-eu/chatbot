package eu.torvian.chatbot.app.compose.settings

import eu.torvian.chatbot.app.domain.contracts.AgentRoleInstructionDraft
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies how the app presents an instruction whose text is generated at read time.
 *
 * A `credential_profiles` row stores no message, so the surfaces have to say where the text comes from
 * instead of showing an empty field the user might think they can fill in.
 */
class InstructionRenderingTest {

    /** Verifies that a credential-profile row names the credential profiles as its message source. */
    @Test
    fun `a credential-profile row says its message is generated from the profiles`() {
        val note = generatedMessageNote(dto(type = AgentInstructionTypes.CREDENTIAL_PROFILES))

        assertEquals("Message generated from the assigned profiles", note)
    }

    /** Verifies that a row which stores its own text gets no generated-message note. */
    @Test
    fun `a stored-text row has no generated-message note`() {
        listOf(
            AgentInstructionTypes.ROLE,
            AgentInstructionTypes.MAIN,
            AgentInstructionTypes.CUSTOM,
            AgentInstructionTypes.MODEL_SPECIFIC,
            "an-unknown-kind",
        ).forEach { type ->
            assertEquals("", generatedMessageNote(dto(type = type)), "unexpected note for $type")
        }
    }

    /** Verifies that the credential-profile row explains that its text comes from the worker. */
    @Test
    fun `the credential-profile row hint names the terminal session worker`() {
        val hint = instructionHint(draft(type = AgentInstructionTypes.CREDENTIAL_PROFILES))

        assertTrue(hint.contains("terminal_session"), hint)
        // The message is generated, so the hint must not suggest the user can write it.
        assertFalse(hint.contains("Write"), hint)
    }

    /** Verifies that an unknown kind is presented as read-only rather than as editable content. */
    @Test
    fun `an unknown kind is presented as read-only`() {
        val hint = instructionHint(draft(type = "skills"))

        assertTrue(hint.contains("read-only"), hint)
    }

    /**
     * @param type Instruction kind under test.
     * @return A library DTO of that kind with no stored message.
     */
    private fun dto(type: String): AgentInstructionDto = AgentInstructionDto(
        id = 1L,
        type = type,
        name = "Credential profiles",
        message = "",
    )

    /**
     * @param type Instruction kind under test.
     * @return A role-instruction draft of that kind with no message.
     */
    private fun draft(type: String): AgentRoleInstructionDraft = AgentRoleInstructionDraft(
        id = null,
        type = type,
        name = "Credential profiles",
        message = "",
    )
}
