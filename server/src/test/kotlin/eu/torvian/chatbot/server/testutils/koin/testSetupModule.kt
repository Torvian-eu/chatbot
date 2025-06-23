package eu.torvian.chatbot.server.testutils.koin

import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.LLMApiClientStub
import eu.torvian.chatbot.server.testutils.data.ExposedTestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import org.koin.dsl.module

/**
 * Koin module for providing test-specific setup components.
 *
 * This module includes:
 * - An instance of [TestDataManager] using [ExposedTestDataManager].
 * - An instance of [LLMApiClient] using [LLMApiClientStub].
 */
fun testSetupModule() = module {
    single<TestDataManager> { ExposedTestDataManager(get()) }
    single<LLMApiClient> { LLMApiClientStub() }
}