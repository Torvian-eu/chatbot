package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
import eu.torvian.chatbot.server.testutils.data.ExposedTestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Koin module for providing test-specific setup components.
 *
 * The legacy server-side tool execution path (ToolExecutor + WebSearch/Weather stubs) has been
 * removed; tool calls are now exclusively dispatched to a worker over the worker protocol, so this
 * module only provides the data manager, the LLM stub, and the shared [Json] configuration.
 */
fun testSetupModule() = module {
    single<TestDataManager> { ExposedTestDataManager(get()) }
    single<LLMApiClient> { LLMApiClientStub() }
    // --- JSON Serializer ---
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}