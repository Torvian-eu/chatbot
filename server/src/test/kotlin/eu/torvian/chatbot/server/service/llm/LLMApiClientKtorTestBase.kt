package eu.torvian.chatbot.server.service.llm

import eu.torvian.chatbot.common.models.llm.LLMProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Supplies the common MockEngine, strategy, and provider fixtures for focused Ktor client tests.
 */
abstract class LLMApiClientKtorTestBase {
    /** Mock strategy used to isolate transport behavior from provider response mapping. */
    protected val mockStrategy: ChatCompletionStrategy = mockk(relaxed = true)

    /** Strategy map installed in the client for the current test. */
    protected lateinit var strategies: Map<LLMProviderType, ChatCompletionStrategy>

    /** Mock engine retained for the lifetime of the current test client. */
    protected lateinit var mockEngine: MockEngine

    /** HTTP client backed by the test MockEngine. */
    protected lateinit var httpClient: HttpClient

    /** Client under test, initialized after the request handler is configured. */
    protected lateinit var client: LLMApiClientKtor

    /** Shared conversation fixture sent to the client. */
    protected val testMessages = eu.torvian.chatbot.server.testutils.data.TestDefaults.rawChatMessages

    /** Shared model fixture used by completion tests. */
    protected val testModel = eu.torvian.chatbot.server.testutils.data.TestDefaults.llmModel1

    /** Shared OpenAI provider fixture used by transport tests. */
    protected val testProvider = eu.torvian.chatbot.server.testutils.data.TestDefaults.llmProvider1

    /** Shared model settings fixture used by completion tests. */
    protected val testSettings = eu.torvian.chatbot.server.testutils.data.TestDefaults.modelSettings1

    /** API key used by authenticated test requests. */
    protected val testApiKey = "test-api-key"

    /** Installs the default mocked strategy mapping before each isolated test. */
    @BeforeEach
    fun setUpClientFixture() {
        strategies = mapOf(testProvider.type to mockStrategy)
        every { mockStrategy.providerType } returns testProvider.type
    }

    /** Releases MockK state so interactions cannot leak between test cases. */
    @AfterEach
    fun tearDownClientFixture() {
        clearMocks(mockStrategy)
    }

    /** Creates a Ktor client whose requests are handled by the supplied deterministic callback. */
    protected fun createMockHttpClient(responseHandler: MockRequestHandler): HttpClient {
        mockEngine = MockEngine(responseHandler)
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }
}
