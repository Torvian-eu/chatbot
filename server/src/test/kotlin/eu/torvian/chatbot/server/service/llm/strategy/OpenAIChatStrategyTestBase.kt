package eu.torvian.chatbot.server.service.llm.strategy

import kotlinx.serialization.json.Json

/**
 * Provides the common strategy fixture for focused OpenAI-compatible request and response tests.
 */
abstract class OpenAIChatStrategyTestBase {
    /** Json configuration used by the strategy under test. */
    protected val strategy: OpenAIChatStrategy = OpenAIChatStrategy(
        Json { ignoreUnknownKeys = true }
    )
}
