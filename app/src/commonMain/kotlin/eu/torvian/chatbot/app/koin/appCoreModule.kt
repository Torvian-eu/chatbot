package eu.torvian.chatbot.app.koin

import eu.torvian.chatbot.app.config.AppConfiguration
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.repository.impl.*
import eu.torvian.chatbot.app.service.api.*
import eu.torvian.chatbot.app.service.api.ktor.*
import eu.torvian.chatbot.app.service.auth.*
import eu.torvian.chatbot.app.service.misc.EventBus
import eu.torvian.chatbot.app.service.security.CertificateTrustService
import eu.torvian.chatbot.app.service.security.DefaultRequestSigningService
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.app.service.turnnotification.AppFocusState
import eu.torvian.chatbot.app.service.turnnotification.ComposeTurnNotificationTextSource
import eu.torvian.chatbot.app.service.turnnotification.DefaultAppFocusState
import eu.torvian.chatbot.app.service.turnnotification.TurnNotificationDispatcher
import eu.torvian.chatbot.app.service.turnnotification.TurnNotificationTextSource
import eu.torvian.chatbot.app.startup.AppStartupInitializer
import eu.torvian.chatbot.app.startup.DefaultAppStartupInitializer
import eu.torvian.chatbot.app.viewmodel.*
import eu.torvian.chatbot.app.viewmodel.chat.util.DefaultThreadBuilder
import eu.torvian.chatbot.app.viewmodel.chat.util.ThreadBuilder
import eu.torvian.chatbot.app.viewmodel.common.CoroutineScopeProvider
import eu.torvian.chatbot.app.viewmodel.common.DefaultCoroutineScopeProvider
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.app.viewmodel.sessionstatus.InMemorySessionTurnStatusRegistry
import eu.torvian.chatbot.app.viewmodel.sessionstatus.SessionTurnStatusRegistry
import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Koin module holding the application-wide infrastructure every feature resolves against.
 *
 * It provides the application configuration, the shared [Json] serializer, certificate trust and
 * request signing, the authenticated and unauthenticated Ktor clients, authentication and device
 * identity services, the shared event bus, the application clock and coroutine scope, the turn
 * notification pipeline, session selection and turn status state, and the search navigation state.
 *
 * @param config The application configuration containing server URL and other settings.
 * @return A Koin module with the application's foundational dependencies.
 */
fun appCoreModule(config: AppConfiguration): Module = module {
    // Provide application config
    single<AppConfiguration> { config }

    // Provide JSON serializer singleton
    single<Json> { Json }

    // Provide CertificateTrustService singleton for certificate trust decisions
    single<CertificateTrustService> {
        CertificateTrustService()
    }

    // Provide the unauthenticated Ktor HttpClient for auth operations
    single<HttpClient>(named("unauthenticated")) {
        createPlatformHttpClient(
            baseUri = config.network.serverUrl,
            json = get(),
            logLevel = LogLevel.INFO,
            certificateStorage = get(),
            certificateTrustService = get()
        ).apply {
            addDeviceIdInterceptor(get())
        }
    }

    // Provide the authenticated Ktor HttpClient with Auth plugin
    single<HttpClient>(named("authenticated")) {
        createAuthenticatedHttpClient(
            baseClient = get(named("unauthenticated")),
            tokenStorage = get(),
            unauthenticatedHttpClient = get(named("unauthenticated")),
            eventBus = get()
        ).apply {
            addDeviceIdInterceptor(get())
        }
    }

    // Create AuthApi with both authenticated and unauthenticated clients
    single<AuthApi> {
        KtorAuthApiClient(
            unauthenticatedClient = get(named("unauthenticated")),
            authenticatedClient = get(named("authenticated"))
        )
    }

    single<AuthRepository> {
        DefaultAuthRepository(
            authApi = get(),
            userApi = get(),
            tokenStorage = get(),
            eventBus = get(),
            deviceIdentityService = get(),
            authValidationService = get()
        )
    }

    // Device identity service for persistent device ID
    single<DeviceIdentityService> {
        DefaultDeviceIdentityService(
            storage = get(),
            cryptoProvider = get()
        )
    }

    // Generic request-signing service for detached protocol-level signatures.
    single<RequestSigningService> {
        DefaultRequestSigningService(
            deviceIdentityService = get(),
            cryptoProvider = get(),
            json = get()
        )
    }

    // Generic startup initializer that runs once when the app reaches the ready state
    single<AppStartupInitializer> {
        DefaultAppStartupInitializer(
            deviceIdentityService = get()
        )
    }

    // Default HttpClient (authenticated) for backward compatibility
    single<HttpClient> {
        get<HttpClient>(named("authenticated"))
    }

    // Provide the EventBus for cross-cutting concerns like global events
    single<EventBus> {
        EventBus()
    }

    // Provide supporting services
    single<Clock> {
        Clock.System
    }

    single<NotificationService> {
        NotificationService(get())
    }

    // Provide CoroutineScope factory for better testability
    single<CoroutineScopeProvider> {
        DefaultCoroutineScopeProvider()
    }

    // Provide thread building service
    single<ThreadBuilder> {
        DefaultThreadBuilder()
    }

    // Provide SessionSelectionController for shared session selection state
    single<SessionSelectionController> {
        DefaultSessionSelectionController()
    }

    // In-memory registry driving the per-session turn status indicators in the session list; it also
    // publishes the out-of-app turn alert triggers on the shared event bus.
    single<SessionTurnStatusRegistry> {
        InMemorySessionTurnStatusRegistry(get(), get())
    }

    // Window/tab attention state; platform feeders push updates into this single instance.
    single<AppFocusState> {
        DefaultAppFocusState()
    }

    single<TurnNotificationTextSource> {
        ComposeTurnNotificationTextSource()
    }

    // Alert dispatcher fed by the shared application event bus (see the EventBus single above); the
    // platform channel services are registered per platform.
    single<TurnNotificationDispatcher> {
        TurnNotificationDispatcher(
            eventBus = get(),
            preferences = get(),
            focusState = get(),
            sessionRepository = get(),
            soundPlayer = get(),
            osNotifications = get(),
            textSource = get()
        )
    }

    // Provide SearchNavigationState for durable navigation intent
    single<SearchNavigationState> {
        DefaultSearchNavigationState()
    }

    // Provide authentication form validation service
    single<AuthValidationService> {
        DefaultAuthValidationService()
    }

    // Provide application-level CoroutineScope for global tasks
    single(qualifier = named("ApplicationCoroutineScope")) {
        CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}
