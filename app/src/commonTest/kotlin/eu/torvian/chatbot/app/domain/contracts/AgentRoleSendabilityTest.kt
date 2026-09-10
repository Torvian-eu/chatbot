package eu.torvian.chatbot.app.domain.contracts

import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.CompletionModelSettings
import eu.torvian.chatbot.common.models.llm.EmbeddingModelSettings
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [resolveAgentRoleSendability] (every branch, with its user-facing reason) and the
 * [isChatCapable] classifier it relies on.
 */
class AgentRoleSendabilityTest {

    private fun preset(
        id: Long = 1L,
        modelId: Long? = 10L,
        modelSettingsId: Long? = 20L
    ): ModelPresetDto = ModelPresetDto(
        id = id,
        name = "preset-$id",
        displayName = null,
        description = "",
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        createdAt = Instant.fromEpochSeconds(id),
        updatedAt = Instant.fromEpochSeconds(id)
    )

    private fun role(
        modelId: Long? = 10L,
        modelSettingsId: Long? = 20L,
        modelPresetId: Long? = 1L
    ): AgentRoleDto = AgentRoleDto(
        id = 5L,
        name = "writer",
        displayName = null,
        description = "",
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        modelPresetId = modelPresetId,
        tools = emptySet(),
        instructions = emptyList()
    )

    private fun chatSettings(id: Long = 20L, modelId: Long = 10L): ChatModelSettings =
        ChatModelSettings(id = id, modelId = modelId, name = "Default")

    @Test
    fun `preset-less role is not sendable`() {
        val result = resolveAgentRoleSendability(
            role = role(modelId = null, modelSettingsId = null, modelPresetId = null),
            preset = null,
            settings = null
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "No model preset is attached; the role has no model or settings profile to send with."
            ),
            result
        )
    }

    @Test
    fun `preset without a model reference is not sendable`() {
        val result = resolveAgentRoleSendability(
            role = role(modelId = null, modelSettingsId = 20L),
            preset = preset(modelId = null),
            settings = chatSettings()
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "The attached preset has no usable model (its model reference is unset, or the model was deleted)."
            ),
            result
        )
    }

    @Test
    fun `preset without a settings reference is not sendable`() {
        val result = resolveAgentRoleSendability(
            role = role(modelId = 10L, modelSettingsId = null),
            preset = preset(modelSettingsId = null),
            settings = null
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "The attached preset has no usable settings profile (its reference is unset, or the profile was deleted)."
            ),
            result
        )
    }

    @Test
    fun `unresolvable settings profile is not sendable`() {
        // The reference exists but this client cannot resolve it (deleted, or not accessible).
        val result = resolveAgentRoleSendability(
            role = role(),
            preset = preset(),
            settings = null
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "The attached preset's settings profile #20 is not available (it may have been deleted or is not accessible to you)."
            ),
            result
        )
    }

    @Test
    fun `non-chat settings profile is not sendable`() {
        val result = resolveAgentRoleSendability(
            role = role(),
            preset = preset(),
            settings = EmbeddingModelSettings(id = 20L, modelId = 10L, name = "Embeddings")
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "The attached preset's settings profile 'Embeddings' is not a chat profile (its model type is EMBEDDING)."
            ),
            result
        )
    }

    @Test
    fun `settings profile belonging to another model is not sendable`() {
        val result = resolveAgentRoleSendability(
            role = role(),
            preset = preset(),
            settings = chatSettings(modelId = 99L)
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "The attached preset's settings profile 'Default' belongs to a different model than the preset."
            ),
            result
        )
    }

    @Test
    fun `chat profile on the matching model is sendable`() {
        assertEquals(
            AgentRoleSendability.Sendable,
            resolveAgentRoleSendability(role = role(), preset = preset(), settings = chatSettings())
        )
    }

    @Test
    fun `responses profile on the matching model is sendable`() {
        val responsesSettings = ResponsesModelSettings(id = 20L, modelId = 10L, name = "Reasoning")

        assertEquals(
            AgentRoleSendability.Sendable,
            resolveAgentRoleSendability(role = role(), preset = preset(), settings = responsesSettings)
        )
    }

    @Test
    fun `a draft without server-resolved ids resolves through the selected preset`() {
        // A form draft carries only the preset reference (the derived role ids appear after the
        // server resolves a saved role), so the preset supplies the effective configuration.
        val draftShapedRole = role(modelId = null, modelSettingsId = null, modelPresetId = 1L)

        assertEquals(
            AgentRoleSendability.Sendable,
            resolveAgentRoleSendability(
                role = draftShapedRole,
                preset = preset(modelId = 10L, modelSettingsId = 20L),
                settings = chatSettings()
            )
        )
    }

    @Test
    fun `a draft whose selected preset has no model is not sendable`() {
        val draftShapedRole = role(modelId = null, modelSettingsId = null, modelPresetId = 1L)

        val result = resolveAgentRoleSendability(
            role = draftShapedRole,
            preset = preset(modelId = null, modelSettingsId = 20L),
            settings = chatSettings()
        )

        assertEquals(
            AgentRoleSendability.NotSendable(
                "The attached preset has no usable model (its model reference is unset, or the model was deleted)."
            ),
            result
        )
    }

    @Test
    fun `isChatCapable accepts chat and responses and rejects the other model types`() {
        assertTrue(chatSettings().isChatCapable())
        assertTrue(ResponsesModelSettings(id = 1L, modelId = 1L, name = "Reasoning").isChatCapable())
        assertFalse(EmbeddingModelSettings(id = 1L, modelId = 1L, name = "Embeddings").isChatCapable())
        assertFalse(CompletionModelSettings(id = 1L, modelId = 1L, name = "Legacy").isChatCapable())
    }
}
