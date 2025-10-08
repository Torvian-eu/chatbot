package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.resources.ProviderResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProvidersAuthTest {
    private lateinit var container: DIContainer
    private lateinit var app: KtorTestApp
    private lateinit var testDataManager: TestDataManager

    @BeforeEach
    fun setup() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        app = myTestApplication(container = container, routing = { apiRoutesKtor.configureProviderRoutes(this) })
        testDataManager = container.get()
        testDataManager.createTables(setOf(Table.API_SECRETS, Table.LLM_PROVIDERS, Table.LLM_MODELS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `GET providers list without auth returns 401`() = app {
        val response = client.get(href(ProviderResource()))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `GET provider by id without auth returns 401`() = app {
        val response = client.get(href(ProviderResource.ById(providerId = 1L)))
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST provider without auth returns 401`() = app {
        val response = client.post(href(ProviderResource())) {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}

