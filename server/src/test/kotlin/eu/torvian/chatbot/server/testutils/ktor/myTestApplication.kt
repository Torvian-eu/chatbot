package eu.torvian.chatbot.server.testutils.ktor

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.server.ktor.configureKtor
import eu.torvian.chatbot.server.utils.misc.DIContainerKey
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json

/**
 * Configures and initializes a custom test application for use in Ktor integration tests.
 *
 * This function sets up the test environment, application, routing, external services, and client configuration
 * for a Ktor test application. It returns a lambda that can further customize or test the application by utilizing
 * the `CustomApplicationTestBuilder`.
 *
 * @param container The DIContainer to use for dependency injection.
 * @param app A lambda to configure the application instance during setup. Default is an empty lambda.
 * @param routing A lambda to configure application routing during setup. Default is an empty lambda.
 * @param environment A lambda to configure the application's environment settings. Default is an empty lambda.
 * @param externalServices A lambda to configure the external services for the application. Default is an empty lambda.
 * @param clientConfig A lambda to configure the HTTP client used in the test application. Default is a pre-defined client configuration.
 * @return A suspending function that accepts a lambda for further testing and interacting with the configured application using `CustomApplicationTestBuilder`.
 */
fun myTestApplication(
    container: DIContainer,
    app: Application.() -> Unit = {},
    routing: Route.() -> Unit = {},
    environment: ApplicationEnvironmentBuilder.() -> Unit = {},
    externalServices: ExternalServicesBuilder.() -> Unit = {},
    clientConfig: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit = defaultClientConfig
): KtorTestApp {
    return { block ->
        testApplication {
            application {
                // Store the DIContainer in the application attributes
                attributes.put(DIContainerKey, container)

                // Configure Ktor
                configureKtor(container.get(), container.get())

                // Additional Ktor configuration
                app()

                // Configure routing
                routing {
                    routing()
                }
            }
            environment {
                environment()
            }
            externalServices {
                externalServices()
            }
            val customBuilder = CustomApplicationTestBuilder(this, clientConfig)
            customBuilder.block()
        }
    }
}

typealias KtorTestApp = (suspend CustomApplicationTestBuilder.() -> Unit) -> Unit

/**
 * Provides a default configuration for the HTTP client used in tests.
 *
 * This lambda configuration installs the `ContentNegotiation` plugin and sets up JSON support
 * for the client, facilitating seamless serialization and deserialization of JSON content in HTTP requests and responses.
 * It is used in the `clientConfig` parameter of the `myTestApplication` function to set up the HTTP client
 * for integration testing.
 */
val defaultClientConfig: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit = {
//    val combinedSerializersModule = SerializersModule {
//        // Add any custom serializers here
//    }
    install(ContentNegotiation) {
//        json(Json {
//            serializersModule = combinedSerializersModule
//        })
        json(Json)
    }
}

/**
 * A utility class for building and customizing a Ktor test application during integration tests.
 *
 * This class provides methods to configure the application, routing, environment, and external services for testing.
 * It also exposes a lazily initialized HTTP client for testing HTTP interactions with the application.
 *
 * @constructor
 * @param delegate An instance of `ApplicationTestBuilder` used to delegate test setup and configuration.
 * @param clientConfig A lambda to configure the HTTP client used for testing the application.
 *
 * Properties:
 * - `client`: Provides a lazily initialized `HttpClient` instance configured with the provided `clientConfig`.
 *
 * Functions:
 * - `application`: Configures the Ktor application logic using the provided lambda.
 * - `routing`: Sets up routing configurations for the Ktor application using the provided lambda.
 * - `externalServices`: Configures external service dependencies using the provided lambda.
 * - `environment`: Sets up the application environment using the provided lambda.
 */
class CustomApplicationTestBuilder(
    private val delegate: ApplicationTestBuilder,
    private val clientConfig: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit
) {
    val client: HttpClient by lazy {
        delegate.createClient {
            clientConfig()
        }
    }

    fun application(block: Application.() -> Unit) = delegate.application(block)
    fun routing(configuration: Route.() -> Unit) = delegate.routing(configuration)
    fun externalServices(block: ExternalServicesBuilder.() -> Unit) = delegate.externalServices(block)
    fun environment(block: ApplicationEnvironmentBuilder.() -> Unit) = delegate.environment(block)
}
