package eu.torvian.chatbot.common.misc.di

import org.koin.core.KoinApplication
import kotlin.reflect.KClass
import org.koin.core.module.Module

/**
 * A Koin-backed implementation of the DIContainer interface.
 *
 * This class provides a bridge between the framework-agnostic DIContainer
 * and the Koin dependency injection framework.
 *
 * @param koinApp The KoinApplication instance to use for dependency resolution.
 */
class KoinDIContainer(val koinApp: KoinApplication) : DIContainer {
    private val koin = koinApp.koin

    override fun <T: Any> get(clazz: KClass<T>): T = koin.get(clazz)

    override fun <T: Any> inject(clazz: KClass<T>): Lazy<T> = lazy { get(clazz) }

    /**
     * Closes the underlying KoinApplication.
     * This should be called when the container is no longer needed,
     * especially in test scenarios to ensure proper cleanup.
     */
    override fun close() = koinApp.close()

    fun addModule(module: Module) {
        koinApp.modules(module)
    }
}