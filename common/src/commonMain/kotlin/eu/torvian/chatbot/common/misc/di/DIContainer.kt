package eu.torvian.chatbot.common.misc.di

import io.ktor.server.application.*
import io.ktor.util.*
import kotlin.reflect.KClass

/**
 * A framework-agnostic dependency injection container interface.
 *
 * This interface provides a common abstraction over different DI frameworks,
 * allowing for better testability and parallel test safety.
 */
interface DIContainer {
    /**
     * Retrieves a dependency immediately.
     *
     * @param clazz The class of the dependency to retrieve.
     * @return An instance of the requested dependency.
     */
    fun <T : Any> get(clazz: KClass<T>): T


    /**
     * Retrieves a dependency lazily.
     *
     * @param clazz The class of the dependency to retrieve.
     * @return A lazy wrapper around the requested dependency.
     */
    fun <T : Any> inject(clazz: KClass<T>): Lazy<T>

    /**
     * Closes the DI container.
     *
     * This should be called when the container is no longer needed,
     * especially in test scenarios to ensure proper cleanup.
     */
    fun close()
}

/**
 * Extension function to retrieve a dependency immediately from the DIContainer.
 */
inline fun <reified T : Any> DIContainer.get(): T = get(T::class)

/**
 * Extension function to retrieve a dependency lazily from the DIContainer.
 *
 * @return A lazy wrapper around the requested dependency.
 */
inline fun <reified T : Any> DIContainer.inject(): Lazy<T> = inject(T::class)


/**
 * Application attribute key for storing the DIContainer.
 */
val DIContainerKey = AttributeKey<DIContainer>("DIContainer")

/**
 * Extension property to access the DIContainer from an Application.
 */
val Application.di: DIContainer get() = attributes[DIContainerKey]

/**
 * Extension property to access the DIContainer from an ApplicationCall.
 */
val ApplicationCall.di: DIContainer get() = application.di

/**
 * Extension function to retrieve a dependency immediately from an ApplicationCall.
 *
 * @return An instance of the requested dependency.
 */
inline fun <reified T : Any> ApplicationCall.get(): T = di.get(T::class)

/**
 * Extension function to retrieve a dependency lazily from an ApplicationCall.
 *
 * @return A lazy wrapper around the requested dependency.
 */
inline fun <reified T : Any> ApplicationCall.inject(): Lazy<T> = di.inject(T::class)