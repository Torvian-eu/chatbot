package eu.torvian.chatbot.common.misc.di

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
