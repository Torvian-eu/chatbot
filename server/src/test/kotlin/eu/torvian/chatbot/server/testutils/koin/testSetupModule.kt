package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
import eu.torvian.chatbot.server.testutils.data.ExposedTestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Koin module for providing test-specific setup components.
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