package eu.torvian.chatbot.server.utils.misc

import eu.torvian.chatbot.common.misc.di.DIContainer
import io.ktor.server.application.*
import io.ktor.util.*

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