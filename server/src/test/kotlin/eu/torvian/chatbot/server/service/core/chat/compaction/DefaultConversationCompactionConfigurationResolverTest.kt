package eu.torvian.chatbot.server.service.core.chat.compaction

import arrow.core.right
import eu.torvian.chatbot.common.models.api.me.ConversationCompactionPreference
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.LLMProviderService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.security.CredentialManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies the runtime auxiliary-configuration resolution for the compaction preference.
 *
 * Covers the single-source-of-truth contract for the auxiliary `LLMConfig`: the preference's
 * `systemMessage` is carried verbatim as the config's system message (empty when the preference has
 * none), the instruction is not part of the config, and no tools are enabled for the auxiliary call.
 */
class DefaultConversationCompactionConfigurationResolverTest {

    private val model = LLMModel(id = 1L, name = "gpt-4o", providerId = 1L, active = true)
    private val settings = ChatModelSettings(id = 2L, modelId = 1L, name = "Default", stream = false)
    private val provider = LLMProvider(
        id = 1L, apiKeyId = null, name = "OpenAI", description = "OpenAI",
        baseUrl = "https://api.openai.com/v1", type = LLMProviderType.OPENAI
    )

    /** All collaborators are mocked; the fixture provider has no API key, so no credential is used. */
    private fun resolver() = DefaultConversationCompactionConfigurationResolver(
        llmModelService = mockk<LLMModelService>().apply {
            coEvery { getModelById(1L) } returns model.right()
        },
        modelSettingsService = mockk<ModelSettingsService>().apply {
            coEvery { getSettingsById(2L) } returns settings.right()
        },
        llmProviderService = mockk<LLMProviderService>().apply {
            coEvery { getProviderById(1L) } returns provider.right()
        },
        credentialManager = mockk<CredentialManager>()
    )

    private fun preferenceWith(systemMessage: String?) = ConversationCompactionPreference(
        modelId = 1L,
        settingsId = 2L,
        instruction = "Summarize faithfully",
        systemMessage = systemMessage
    )

    @Test
    fun `preference system message is carried verbatim into the auxiliary config`() = runTest {
        val config = resolver()
            .resolveAuxiliaryConfig(userId = 1L, preference = preferenceWith("You are a summarizer."))
            .getOrNull()
        assertEquals("You are a summarizer.", config?.systemMessage)
    }

    @Test
    fun `missing preference system message resolves to an empty config system message`() = runTest {
        val config = resolver()
            .resolveAuxiliaryConfig(userId = 1L, preference = preferenceWith(null))
            .getOrNull()
        assertEquals("", config?.systemMessage)
        // No tools are enabled for the auxiliary call (the instruction is not part of the config
        // either — the service appends it as the final user message).
        assertNull(config?.tools)
    }
}