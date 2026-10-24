package eu.torvian.chatbot.server.service.core.impl

import arrow.core.raise.either
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the credential-profile instruction kind is treated like the other generated-message
 * kinds by the shared per-role and content rules.
 */
class CredentialProfilesInstructionRulesTest {

    /**
     * @param type Instruction kind.
     * @param modelId Model a `model_specific` row targets.
     * @return Rule input for the kind.
     */
    private fun input(type: String, modelId: Long? = null) = AgentRoleInstructionRules.RuleInput(type, modelId)

    /** Verifies that a role may reference the credential advertisement only once. */
    @Test
    fun `rejects a second credential profiles row`() {
        val reason = AgentRoleInstructionRules.violationReason(
            listOf(
                input(AgentInstructionTypes.CREDENTIAL_PROFILES),
                input(AgentInstructionTypes.CREDENTIAL_PROFILES),
            )
        )

        assertTrue(reason.orEmpty().contains(AgentInstructionTypes.CREDENTIAL_PROFILES))
        assertTrue(reason.orEmpty().contains("At most one"))
    }

    /** Verifies that a single credential advertisement row keeps the role valid. */
    @Test
    fun `accepts one credential profiles row`() {
        assertNull(
            AgentRoleInstructionRules.violationReason(
                listOf(
                    input(AgentInstructionTypes.ROLE),
                    input(AgentInstructionTypes.CREDENTIAL_PROFILES),
                )
            )
        )
    }

    /** Verifies that a stored message for the generated kind is rejected with its own wording. */
    @Test
    fun `rejects an authored message for the generated kind`() {
        val failure = either<String, Unit> {
            InstructionContentRules.validate(
                type = AgentInstructionTypes.CREDENTIAL_PROFILES,
                name = "Available credential profiles",
                message = "some text",
                custom = null,
                raise = this,
                validationFailed = { it },
            )
        }

        assertEquals(
            "A 'credential_profiles' instruction takes no message",
            failure.swap().getOrNull(),
        )
    }

    /** Verifies that the generated kind stores no message. */
    @Test
    fun `stores no message for the generated kind`() {
        assertNull(InstructionContentRules.storedMessage(AgentInstructionTypes.CREDENTIAL_PROFILES, "text"))
        assertEquals(
            "text",
            InstructionContentRules.storedMessage(AgentInstructionTypes.CUSTOM, "text"),
        )
    }
}
