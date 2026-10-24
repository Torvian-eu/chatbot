package eu.torvian.chatbot.common.models.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the kind lists that drive every generated-message and single-instance site.
 *
 * These sets are read by the server rules, the built-in instruction tools and the client's role form, so
 * a kind added to one surface but not to the shared lists would silently behave differently on another.
 */
class AgentInstructionTypesTest {

    /** Verifies that every kind list stays within the kinds the API accepts. */
    @Test
    fun `generated and singleton kinds are known kinds`() {
        assertTrue(AgentInstructionTypes.generatedMessageKinds.all { it in AgentInstructionTypes.allKnown })
        assertTrue(AgentInstructionTypes.singletonKinds.all { it in AgentInstructionTypes.allKnown })
    }

    /** Verifies that both dynamic guidance kinds store no message. */
    @Test
    fun `dynamic guidance kinds are generated`() {
        assertEquals(
            setOf(AgentInstructionTypes.SPAWNABLE_AGENTS, AgentInstructionTypes.CREDENTIAL_PROFILES),
            AgentInstructionTypes.generatedMessageKinds,
        )
    }

    /** Verifies that the credential advertisement is a singleton kind. */
    @Test
    fun `credential profiles is a singleton kind`() {
        assertTrue(AgentInstructionTypes.CREDENTIAL_PROFILES in AgentInstructionTypes.singletonKinds)
    }

    /** Verifies that multi-instance kinds stay out of the singleton set. */
    @Test
    fun `multi instance kinds are not singletons`() {
        assertFalse(AgentInstructionTypes.CUSTOM in AgentInstructionTypes.singletonKinds)
        assertFalse(AgentInstructionTypes.MODEL_SPECIFIC in AgentInstructionTypes.singletonKinds)
    }

    /** Verifies that the new kind is advertised to clients and tools. */
    @Test
    fun `credential profiles is a known kind`() {
        assertTrue(AgentInstructionTypes.CREDENTIAL_PROFILES in AgentInstructionTypes.allKnown)
        assertEquals("credential_profiles", AgentInstructionTypes.CREDENTIAL_PROFILES)
    }
}
