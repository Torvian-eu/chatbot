package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
import eu.torvian.chatbot.server.service.tool.ToolExecutor
import eu.torvian.chatbot.server.testutils.data.ExposedTestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.service.WebSearchToolExecutorStub
import kotlinx.serialization.json.Json
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for providing test-specific setup components.
 *
 * This module includes:
 * - An instance of [TestDataManager] using [ExposedTestDataManager].
 * - An instance of [LLMApiClient] using [LLMApiClientStub].
 * - An instance of [ToolExecutor] using [WebSearchToolExecutorStub].
 */
fun testSetupModule() = module {
    single<TestDataManager> { ExposedTestDataManager(get()) }
    single<LLMApiClient> { LLMApiClientStub() }
    single<ToolExecutor>(named("web_search")) { WebSearchToolExecutorStub() }
    // --- JSON Serializer ---
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}