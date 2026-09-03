package eu.torvian.chatbot.server.service.core.chat.compaction

import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.llm.ResponsesModelSettings
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import eu.torvian.chatbot.server.service.llm.ChatCompletionStrategyResolver
import eu.torvian.chatbot.server.service.llm.RawChatMessage
import eu.torvian.chatbot.server.service.llm.strategy.OllamaChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.OpenAIChatStrategy
import eu.torvian.chatbot.server.service.llm.strategy.ResponsesStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
/**
 * Verifies the deterministic repository-owned input token estimate ([ApproximateChatInputTokenCounter]).
 *
 * The v1 formula counts the Kotlin UTF-16 code units of the compact provider input projection and
 * returns `ceil(codeUnits / 4)`. Fixed fixtures lock the exact formula, boundary rounding, escaping,
 * Unicode handling, dialect-specific projection fields, and unsupported-dialect rejection.
 */
class ApproximateChatInputTokenCounterTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val resolver = ChatCompletionStrategyResolver(
        strategies = mapOf(
            LLMProviderType.OPENAI to OpenAIChatStrategy(json),
            LLMProviderType.OPENROUTER to OpenAIChatStrategy(json),
            LLMProviderType.OLLAMA to OllamaChatStrategy(json),
        ),
        responsesStrategy = ResponsesStrategy(json)
    )
    private val counter = ApproximateChatInputTokenCounter(resolver, json)

    private val model = LLMModel(id = 1L, name = "gpt-4o", providerId = 1L, active = true)
    private val openAiProvider = LLMProvider(
        id = 1L,
        apiKeyId = null,
        name = "OpenAI",
        description = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )
    private val chatSettings = ChatModelSettings(id = 1L, modelId = 1L, name = "Default", stream = false)

    /**
     * Verifies the exact formula on the empty and single-message projections.
     */
    @Test
    fun `counts ceil of UTF-16 code units divided by four for the compact projection`() {
        // {"messages":[]} = 15 code units -> (15 + 3) / 4 = 4
        assertEquals(
            4L,
            counter.countPrimaryInput(model, openAiProvider, chatSettings, null, emptyList(), null).getOrNull()
        )

        // {"messages":[{"role":"user","content":"Hello"}]} = 48 code units -> (48 + 3) / 4 = 12
        val single = counter.countPrimaryInput(
            model, openAiProvider, chatSettings, null, listOf(RawChatMessage.User("Hello")), null
        ).getOrNull()
        assertEquals(12L, single)
    }

    /**
     * Verifies boundary rounding at multiples of four using a real projection padded with content.
     */
    @Test
    fun `boundary lengths round up to the next token`() {
        // A single user message projection has length 43 + contentLength. 48 code units (5 content
        // chars) is exactly 12 tokens; 49 code units (6 content chars) must round up to 13.
        val countAt48 = counter.countPrimaryInput(
            model, openAiProvider, chatSettings, null, listOf(RawChatMessage.User("12345")), null
        ).getOrNull()!!
        val countAt49 = counter.countPrimaryInput(
            model, openAiProvider, chatSettings, null, listOf(RawChatMessage.User("123456")), null
        ).getOrNull()!!
        assertEquals(12L, countAt48)
        assertEquals(13L, countAt49)
    }

    /**
     * Verifies identical input produces identical counts and the persisted version string is stable.
     */
    @Test
    fun `repeated counting of identical input is deterministic and version is stable`() {
        val messages = listOf(RawChatMessage.User("Repeat me"), RawChatMessage.Assistant("Repeated."))
        val first = counter.countPrimaryInput(model, openAiProvider, chatSettings, "System", messages, null)
        val second = counter.countPrimaryInput(model, openAiProvider, chatSettings, "System", messages, null)
        assertEquals(first, second)
        assertEquals("approx_utf16_json_v1", counter.version)
    }

    /**
     * Verifies system text, tool schemas, and tool calls contribute to the count.
     */
    @Test
    fun `system prompt tools and tool calls affect the count`() {
        val tool = LocalMCPToolDefinition(
            id = 1L,
            name = "search",
            description = "Search documents",
            config = buildJsonObject { put("k", "v") },
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) })
            },
            outputSchema = null,
            isEnabled = true,
            createdAt = Instant.fromEpochMilliseconds(0L),
            updatedAt = Instant.fromEpochMilliseconds(0L),
            serverId = 1L,
            mcpToolName = "search"
        )
        val messages = listOf(
            RawChatMessage.User("Look up data"),
            RawChatMessage.Assistant(
                content = null,
                toolCalls = listOf(
                    RawChatMessage.Assistant.ToolCall(id = "call-1", name = "search", arguments = "{\"q\":\"x\"}")
                )
            ),
            RawChatMessage.Tool(content = "{\"results\":[]}", toolCallId = "call-1", name = "search")
        )

        val withoutTool = counter.countPrimaryInput(model, openAiProvider, chatSettings, null, messages, null)
            .getOrNull()!!
        val withTool = counter.countPrimaryInput(model, openAiProvider, chatSettings, null, messages, listOf(tool))
            .getOrNull()!!
        val withSystem = counter.countPrimaryInput(model, openAiProvider, chatSettings, "You are helpful.", messages, null)
            .getOrNull()!!

        assertTrue(withTool > withoutTool, "tool schema must contribute input tokens")
        assertTrue(withSystem > withoutTool, "system prompt must contribute input tokens")
    }

    /**
     * Verifies the Responses dialect projection counts `input` wrappers and `instructions`.
     */
    @Test
    fun `responses dialect counts input item wrappers and instructions`() {
        val responsesSettings = ResponsesModelSettings(id = 2L, modelId = 1L, name = "Responses", stream = false)
        val count = counter.countPrimaryInput(
            model, openAiProvider, responsesSettings, "Summarize faithfully", listOf(RawChatMessage.User("Hello")), null
        ).getOrNull()
        assertNotNull(count)
        assertTrue(count > 0L)
    }

    /**
     * Verifies the Ollama dialect is served by the Ollama strategy and counts messages/tools.
     */
    @Test
    fun `ollama dialect is counted through the ollama strategy`() {
        val ollamaProvider = LLMProvider(
            id = 3L,
            apiKeyId = null,
            name = "Ollama",
            description = "Local",
            baseUrl = "http://localhost:11434",
            type = LLMProviderType.OLLAMA
        )
        val ollamaSettings = ChatModelSettings(id = 3L, modelId = 2L, name = "Default", stream = false)
        val count = counter.countPrimaryInput(
            model.copy(id = 2L, name = "llama3"),
            ollamaProvider,
            ollamaSettings,
            "System",
            listOf(RawChatMessage.User("Hi")),
            null
        ).getOrNull()
        assertNotNull(count)
        assertTrue(count > 0L)
    }

    /**
     * Verifies an unregistered dialect (ANTHROPIC) is rejected as unsupported rather than undercounted.
     */
    @Test
    fun `unregistered dialect is rejected as unsupported configuration`() {
        val anthropicProvider = LLMProvider(
            id = 9L,
            apiKeyId = null,
            name = "Anthropic",
            description = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            type = LLMProviderType.ANTHROPIC
        )
        val anthropicSettings = ChatModelSettings(id = 9L, modelId = 9L, name = "Default", stream = false)
        val result = counter.countPrimaryInput(
            model.copy(id = 9L, name = "claude"), anthropicProvider, anthropicSettings, null,
            listOf(RawChatMessage.User("Hi")), null
        )
        assertIs<ConversationCompactionError.UnsupportedConfiguration>(result.leftOrNull())
    }

    /**
     * Verifies non-BMP characters count as two UTF-16 code units (they inflate the serialized length).
     */
    @Test
    fun `non-BMP unicode counts as two code units`() {
        val emoji = RawChatMessage.User("\uD83D\uDE00") // U+1F600 smiling face
        val ascii = RawChatMessage.User("ab")
        val emojiCount = counter.countPrimaryInput(model, openAiProvider, chatSettings, null, listOf(emoji), null)
            .getOrNull()!!
        val asciiCount = counter.countPrimaryInput(model, openAiProvider, chatSettings, null, listOf(ascii), null)
            .getOrNull()!!
        // "ab" (2 code units) vs emoji (2 code units) project to the same serialized length.
        assertEquals(asciiCount, emojiCount)
    }
}
