package eu.torvian.chatbot.common.misc.di

import org.koin.core.Koin
import org.koin.core.KoinApplication
import kotlin.reflect.KClass
import org.koin.core.module.Module

/**
 * A Koin-backed implementation of the DIContainer interface.
 *
 * This class provides a bridge between the framework-agnostic DIContainer
 * and the Koin dependency injection framework.
 *
 * @param koin The underlying Koin instance.
 */
class KoinDIContainer(private val koin: Koin) : DIContainer {
    constructor(koinApplication: KoinApplication) : this(koinApplication.koin)

    override fun <T: Any> get(clazz: KClass<T>): T = koin.get(clazz)

    override fun <T: Any> inject(clazz: KClass<T>): Lazy<T> = lazy { get(clazz) }

    /**
     * Closes the underlying KoinApplication.
     * This should be called when the container is no longer needed,
     * especially in test scenarios to ensure proper cleanup.
     */
    override fun close() = koin.close()

    fun addModule(module: Module) {
        koin.loadModules(listOf(module))
    }
}